package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.ratelimit.RateLimiter;
import com.collabcode.realtime_codeshare.service.SessionService;
import com.collabcode.realtime_codeshare.ticket.TicketStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Session lifecycle REST API. {@code POST /api/sessions} is the only path that
 * mints a session id — WebSocket upgrades never create rooms. Creation is
 * unauthenticated, so it is per-IP rate limited and globally capped to prevent a
 * memory-exhaustion DoS (CWE-770).
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final TicketStore ticketStore;
    private final RateLimiter createRateLimiter;
    private final int maxActiveSessions;

    public SessionController(SessionService sessionService,
                            TicketStore ticketStore,
                            RateLimiter createRateLimiter,
                            @Value("${codeshare.session.max-active:10000}") int maxActiveSessions) {
        this.sessionService = sessionService;
        this.ticketStore = ticketStore;
        this.createRateLimiter = createRateLimiter;
        this.maxActiveSessions = maxActiveSessions;
    }

    public static class CreateRequest {
        @Size(max = 80, message = "name too long")
        public String name;
        @Size(max = 40, message = "color too long")
        public String color;
    }

    /** Body for {@code POST /{id}/ticket}: the credential travels here, not in the WS URL. */
    public static class TicketRequest {
        public String token;     // member/admin token; null/blank for a brand-new joiner
        public String clientId;  // tab-local client id
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody(required = false) CreateRequest req,
                                    HttpServletRequest request) {
        // Per-IP throttle (remote addr honors X-Forwarded-For via forward-headers-strategy).
        if (!createRateLimiter.tryAcquire(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many sessions created; slow down");
        }
        // Global capacity guard.
        if (sessionService.count() >= maxActiveSessions) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "server at session capacity; try again later");
        }
        String name = req == null ? null : req.name;
        String color = req == null ? null : req.color;
        Session session = sessionService.createSession(name, color);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "sessionId", session.getSessionId(),
                "adminToken", session.getCreatorToken(),
                "wsUrl", "/ws/ot?sessionId=" + session.getSessionId()
        ));
    }

    /**
     * Mint a single-use, short-TTL connection ticket so the session token can be
     * sent in this POST body (over HTTPS) instead of riding the WebSocket URL.
     * The client presents only the opaque ticket on the upgrade.
     */
    @PostMapping("/{id}/ticket")
    public ResponseEntity<?> ticket(@PathVariable String id,
                                    @RequestBody(required = false) TicketRequest req) {
        if (!SessionService.isValidIdFormat(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed session id");
        }
        if (!sessionService.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown session");
        }
        String token = req == null ? null : req.token;
        String clientId = req == null ? null : req.clientId;
        String ticket = ticketStore.issue(id, token, clientId);
        return ResponseEntity.ok(Map.of("ticket", ticket, "ttlSeconds", ticketStore.ttlSeconds()));
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
