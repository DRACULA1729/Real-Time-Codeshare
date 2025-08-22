package com.collabcode.realtime_codeshare.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.collabcode.realtime_codeshare.ot.OTService;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final OTService otService;
    private final RestTemplate rt = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GitHubController(OTService otService) { this.otService = otService; }

    public static class PushRequest {
        public String token;
        public String owner;
        public String repo;
        public String path;         // e.g., "src/Main.java" or "alpha123.java"
        public String message;      // commit message
        public String sessionId;    // which session to get code from
    }

    @PostMapping("/push")
    public ResponseEntity<?> pushFile(@RequestBody PushRequest req) throws Exception {
        String content = otService.getDocument(req.sessionId);
        String encoded = Base64.getEncoder().encodeToString(content.getBytes());

        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s", req.owner, req.repo, req.path);

        // We should first check if the file exists to get sha
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(req.token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Attempt to GET existing file to obtain sha
        ResponseEntity<String> getRes = null;
        String sha = null;
        try {
            getRes = rt.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (getRes.getStatusCode().is2xxSuccessful()) {
                Map<?,?> body = mapper.readValue(getRes.getBody(), Map.class);
                sha = (String) body.get("sha");
            }
        } catch (Exception e) {
            // not found or other — proceed with create
        }

        // build request body
        Map<String, Object> body = Map.of(
                "message", req.message == null ? "Commit from Ghost CodeShare" : req.message,
                "content", encoded,
                "branch", "main"
        );
        if (sha != null) {
            String finalSha = sha;
            body = new java.util.HashMap<>(body) {{ put("sha", finalSha); }};
        }

        HttpEntity<String> ent = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        ResponseEntity<String> res = rt.exchange(url, HttpMethod.PUT, ent, String.class);
        return ResponseEntity.status(res.getStatusCode()).body(res.getBody());
    }
}
