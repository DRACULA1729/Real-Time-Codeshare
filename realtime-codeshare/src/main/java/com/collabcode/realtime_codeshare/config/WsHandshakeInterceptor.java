package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates the handshake before the socket opens. A connection with a missing,
 * malformed, or unknown {@code sessionId} is refused at the upgrade — so no room
 * is ever auto-created and there is no shared "default" fallback. Query params
 * are parsed once here into session attributes for the handler.
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

    private final SessionService sessionService;

    public WsHandshakeInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Map<String, String> q = parseQuery(request.getURI().getRawQuery());
        String sessionId = q.get("sessionId");

        if (!SessionService.isValidIdFormat(sessionId) || !sessionService.exists(sessionId)) {
            log.warn("Rejecting handshake for unknown/malformed sessionId '{}'", sessionId);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        String token = q.get("token");
        attributes.put("sessionId", sessionId);
        attributes.put("token", token);
        attributes.put("clientId", q.get("clientId"));
        attributes.put("name", q.get("name"));
        attributes.put("color", q.get("color"));

        Session session = sessionService.get(sessionId);
        attributes.put("roleIntent", (token != null && session != null && session.isAdmin(token)) ? "CREATOR" : "JOINER");
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, val);
        }
        return out;
    }
}
