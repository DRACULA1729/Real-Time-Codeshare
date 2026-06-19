package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Session lifecycle REST API. {@code POST /api/sessions} is the only path that
 * mints a session id — WebSocket upgrades never create rooms.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public static class CreateRequest {
        public String name;
        public String color;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) CreateRequest req) {
        String name = req == null ? null : req.name;
        String color = req == null ? null : req.color;
        Session session = sessionService.createSession(name, color);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "sessionId", session.getSessionId(),
                "adminToken", session.getCreatorToken(),
                "wsUrl", "/ws/ot?sessionId=" + session.getSessionId()
        ));
    }

    @GetMapping("/{id}/exists")
    public Map<String, Object> exists(@PathVariable String id) {
        if (!SessionService.isValidIdFormat(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed session id");
        }
        return Map.of(
                "exists", sessionService.exists(id),
                "alive", sessionService.isAlive(id)
        );
    }
}
