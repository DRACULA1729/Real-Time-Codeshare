package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.ot.OTService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OTWebSocketHandler extends TextWebSocketHandler {

    private final OTService otService;
    private final ObjectMapper mapper = new ObjectMapper();
    //private final CodeRunnerService codeRunnerService = new CodeRunnerService();


    // sessionId -> admitted clients (only approved clients live here)
    private final Map<String, Set<WebSocketSession>> roomClients = new ConcurrentHashMap<>();
    private final Map<String , Map<String, Integer>> cursorPositions= new ConcurrentHashMap<>();
    public OTWebSocketHandler(OTService otService) {
        this.otService = otService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = getSessionId(session);
        roomClients.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(session);
        System.out.println("Client joined room: " + sessionId);

        // Send current doc + version to new client
        String currentDoc = otService.getDocument(sessionId);
        int currentVersion = otService.getVersion(sessionId);

        Map<String, Object> initPayload = Map.of(
                "type", "init",
                "document", currentDoc,
                "version", currentVersion
        );
        session.sendMessage(new TextMessage(mapper.writeValueAsString(initPayload)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = getSessionId(session);
        String payload = message.getPayload();
        System.out.println("Received a payload: "+payload);
        // Parse just enough to detect the type
        JsonNode node = mapper.readTree(payload);
        String type = node.has("type") ? node.get("type").asText() : "";
        String clientId= node.has("clientId") ? node.get("clientId").asText(): null;
        cursorPositions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());

        if ("cursor".equalsIgnoreCase(type) && clientId!= null) {
            int pos= node.get("pos").asInt();
            cursorPositions.get(sessionId).put(clientId, pos);
            // Broadcast cursor position to others in the same session
            for (WebSocketSession s : roomClients.getOrDefault(sessionId, Collections.emptySet())) {
                if (s.isOpen() && !s.getId().equals(session.getId())) {
                    s.sendMessage(new TextMessage(payload));
                }
            }
            return; // No OT processing for cursor messages
        }

        if ("op".equalsIgnoreCase(type) || !type.isEmpty()) {
            // Try to parse as OpDTO and apply OT
            OTService.OpDTO dto = mapper.treeToValue(node, OTService.OpDTO.class);

            // Apply OT transformation
            OTService.ApplyResult result = otService.applyOp(sessionId, dto);

            // Build op payload
            Map<String, Object> opPayload = Map.of(
                    "type", "op",
                    "op", result.getTransformedOp(),
                    "version", result.getNewVersion()
            );
            String json = mapper.writeValueAsString(opPayload);

            // Broadcast to all clients in the room
            for (WebSocketSession s : roomClients.getOrDefault(sessionId, Collections.emptySet())) {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(json));
                }
            }
        }
//
//        if ("saveAndRun".equalsIgnoreCase(type)) {
//            //Logging
//            System.out.println("Processing for: "+sessionId);
//            String code = otService.getDocument(sessionId);
//            String result = saveAndRunCode(code);
//            Map<String, Object> runPayload = Map.of(
//                    "type", "runResult",
//                    "result", result
//            );
//            session.sendMessage(new TextMessage(mapper.writeValueAsString(runPayload)));
//        }
    }
    /*  // Added: Method to prompt for file name
      private String promptFileName() {
          // Simulate user prompt (adjust for WebSocket context if needed)
          Scanner scanner = new Scanner(System.in);
          System.out.print("Enter file name (without .java): ");
          return scanner.nextLine().trim();
      }
      private String extractClassName(String code) {
          String[] lines = code.split("\n");
          for (String line : lines) {
              if (line.trim().startsWith("public class")) {
                  String[] parts = line.trim().split("\\s+");
                  if (parts.length >= 3 && parts[1].equals("class")) {
                      return parts[2].replace("{", "").trim();
                  }
              }
          }
          return null;
      }

      public String saveAndRunCode(String code) {
          try {
              System.out.println("Starting save and Run for: "+code);
              // Delete previous file if exists
              if (CodeRunnerService.currentFile != null &&
                      CodeRunnerService.currentFile.exists()) {
                  Files.delete(CodeRunnerService.currentFile.toPath());
              }

              // Save new code to temp file
            //  CodeRunnerService.currentFile = File.createTempFile("code_", ".java", new File(CodeRunnerService.TEMP_DIR));
            //  String className = CodeRunnerService.currentFile.getName().replace(".java", "");
              String fileName = promptFileName();
              if (fileName == null) return "Filename not provided so operation cancelled";
              File file = new File(CodeRunnerService.TEMP_DIR, fileName + ".java");
              String className = extractClassName(code);
              if (className == null || !className.equals(fileName)) {
                  return "Error: Class name '" + (className != null ? className : "not found") + "' does not match file name '" + fileName + "'";
              }
              String wrappedCode = "public class " + className + " {\n" + code + "\n}";
              //System.out.println("PATH IS: "+  CodeRunnerService.currentFile);
              try (FileWriter writer = new FileWriter(CodeRunnerService.currentFile)) {
                  writer.write(code);
              }

              // Added: Log file path and content
              System.out.println("Saved file at: " + CodeRunnerService.currentFile.getAbsolutePath());
              System.out.println("File content: " + wrappedCode);

              // Compile
              ProcessBuilder compilePb = new ProcessBuilder("javac", CodeRunnerService.currentFile.getAbsolutePath());
              Process compileProcess = compilePb.start();
              String compileError = readStream(compileProcess.getErrorStream());
              if (compileProcess.waitFor() != 0) {
                  return "Compilation error: " + compileError;
              }

              // Run
              //String className = CodeRunnerService.currentFile.getName().replace(".java", "");
              ProcessBuilder runPb = new ProcessBuilder("java", "-Djava.security.manager", className);
              runPb.directory(CodeRunnerService.currentFile.getParentFile());
              Process runProcess = runPb.start();
              String output = readStream(runProcess.getInputStream());
              String error = readStream(runProcess.getErrorStream());
              runProcess.waitFor();

              // Clean up class file
              new File(className + ".class").delete();

              return error.isEmpty() ? output : "Runtime error: " + error;
          } catch (Exception e) {
              return "Error: " + e.getMessage();
          }
      }

      private String readStream(InputStream stream) throws IOException {
          StringBuilder result = new StringBuilder();
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
              String line;
              while ((line = reader.readLine()) != null) {
                  result.append(line).append("\n");
              }
          }
          return result.toString();
      }
  */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = getSessionId(session);
        roomClients.getOrDefault(sessionId, Collections.emptySet()).remove(session);
        cursorPositions.getOrDefault(sessionId, new ConcurrentHashMap<>()). entrySet().removeIf(e-> e.getValue()==null);
        System.out.println("Client left room: " + sessionId);
    }

    private String getSessionId(WebSocketSession session) {
        // Expect sessionId in query param: ws://localhost:8080/ws/ot?sessionId=alpha123
        String query = Objects.requireNonNull(session.getUri()).getQuery();
        if (query != null && query.startsWith("sessionId=")) {
            return query.substring("sessionId=".length());
        }
        return "default";
    }

    public Map<String, Set<WebSocketSession>> getRoomClients() {
        return roomClients;
    }
}