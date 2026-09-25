package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.service.SessionService;
import com.collabcode.realtime_codeshare.ticket.Ticket;
import com.collabcode.realtime_codeshare.ticket.TicketStore;
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
 * is ever auto-created and there is no shared "default" fallback.
 *
 * <p>The session token never appears in the URL (CWE-598). The client mints a
 * single-use ticket over an authenticated HTTPS POST; this interceptor redeems it
 * to recover the real {sessionId, token, clientId} binding. Only the opaque ticket
 * and non-secret name/color ride the upgrade query string.
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

    private final SessionService sessionService;
    private final TicketStore ticketStore;

    public WsHandshakeInterceptor(SessionService sessionService, TicketStore ticketStore) {
        this.sessionService = sessionService;
        this.ticketStore = ticketStore;
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
        if (sessionService.isEnded(sessionId)) {
            response.setStatusCode(HttpStatus.GONE);
            return false;
        }

        // Redeem the single-use connection ticket; reject if missing/expired/consumed
        // or bound to a different room.
        Ticket ticket = ticketStore.redeem(q.get("ticket"));
        if (ticket == null || !sessionId.equals(ticket.sessionId())) {
            log.warn("Rejecting handshake for session {}: invalid or expired ticket", sessionId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put("sessionId", sessionId);
        attributes.put("token", ticket.token());          // from the ticket, never the URL
        attributes.put("clientId", ticket.clientId());    // from the ticket
        // Sanitize name/color here so the values seeded into WS attributes (and later
        // fanned out in presence/joinRequest broadcasts) are already bounded/validated,
        // not the raw query string.
        attributes.put("name", SessionService.safeName(q.get("name")));
        attributes.put("color", SessionService.safeColor(q.get("color")));
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
