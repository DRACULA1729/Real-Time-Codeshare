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
    }

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