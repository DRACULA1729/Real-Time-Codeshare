package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.ot.InvalidOpException;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.ratelimit.TokenBucket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OTWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OTWebSocketHandler.class);

    /** Per-session send serialization limits for ConcurrentWebSocketSessionDecorator. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_BYTES = 512 * 1024;

    // WS-session attribute keys.
    static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_CLIENT_ID = "clientId";
    private static final String ATTR_BUCKET = "bucket";

    private final OTService otService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int opsPerSec;

    // sessionId -> (wsSessionId -> decorated session). Keying by ws id is identity-safe
    // across raw vs decorated sessions and makes add/remove O(1).
    private final Map<String, Map<String, WebSocketSession>> roomClients = new ConcurrentHashMap<>();
    // sessionId -> (clientId -> cursor index)
    private final Map<String, Map<String, Integer>> cursorPositions = new ConcurrentHashMap<>();
    // sessionId -> last activity epoch millis (for the idle reaper)
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();

    public OTWebSocketHandler(OTService otService,
                              @Value("${codeshare.rate.ops-per-sec:50}") int opsPerSec) {
        this.otService = otService;
        this.opsPerSec = opsPerSec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = getSessionId(session);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("Rejecting connection {} with no sessionId", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // Wrap so concurrent broadcasts to this socket are serialized.
        WebSocketSession decorated =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);
        session.getAttributes().put(ATTR_BUCKET, new TokenBucket(opsPerSec));

        roomClients.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), decorated);
        touch(sessionId);
        log.info("Client {} joined room {}", session.getId(), sessionId);

        Map<String, Object> initPayload = Map.of(
                "type", "init",
                "document", otService.getDocument(sessionId),
                "version", otService.getVersion(sessionId)
        );
        decorated.sendMessage(new TextMessage(mapper.writeValueAsString(initPayload)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        if (sessionId == null) return;
        touch(sessionId);

        String payload = message.getPayload();
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            log.warn("[{}] dropping non-JSON message: {}", sessionId, e.getMessage());
            return;
        }

        String type = node.path("type").asText("");
        String clientId = node.hasNonNull("clientId") ? node.get("clientId").asText() : null;
        if (clientId != null) session.getAttributes().put(ATTR_CLIENT_ID, clientId);

        switch (type.toLowerCase(Locale.ROOT)) {
            case "cursor" -> handleCursor(session, sessionId, node, clientId, payload);
            case "insert", "delete" -> handleOp(session, sessionId, node);
            default -> log.warn("[{}] ignoring message of unknown type '{}'", sessionId, type);
        }
    }

    private void handleCursor(WebSocketSession session, String sessionId, JsonNode node,
                              String clientId, String payload) {
        if (clientId == null || !node.has("pos")) return;
        cursorPositions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(clientId, node.get("pos").asInt());
        // Relay the raw cursor message to everyone else; no OT processing.
        broadcast(sessionId, payload, session.getId());
    }

    private void handleOp(WebSocketSession session, String sessionId, JsonNode node) {
        TokenBucket bucket = (TokenBucket) session.getAttributes().get(ATTR_BUCKET);
        if (bucket != null && !bucket.tryAcquire()) {
            log.warn("[{}] rate limit exceeded for {}; dropping op", sessionId, session.getId());
            return;
        }

        OTService.OpDTO dto;
        try {
            dto = mapper.treeToValue(node, OTService.OpDTO.class);
        } catch (Exception e) {
            log.warn("[{}] dropping malformed op: {}", sessionId, e.getMessage());
            return;
        }

        OTService.ApplyResult result;
        try {
            result = otService.applyOp(sessionId, dto);
        } catch (InvalidOpException e) {
            log.warn("[{}] rejected invalid op: {}", sessionId, e.getMessage());
            return;
        }

        Map<String, Object> opPayload = Map.of(
                "type", "op",
                "op", result.getTransformedOp(),
                "version", result.getNewVersion()
        );
        try {
            // Broadcast to everyone including the sender — the client relies on
            // receiving its own committed op back to advance its pending queue.
            broadcast(sessionId, mapper.writeValueAsString(opPayload), null);
        } catch (Exception e) {
            log.error("[{}] failed to serialize op payload", sessionId, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        if (sessionId == null) return;

        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room != null) {
            room.remove(session.getId());

            String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
            if (clientId != null) {
                Map<String, Integer> cursors = cursorPositions.get(sessionId);
                if (cursors != null) cursors.remove(clientId);
                broadcastCursorRemove(sessionId, clientId, session.getId());
            }

            if (room.isEmpty()) {
                onRoomEmpty(sessionId);
            } else {
                touch(sessionId);
            }
        }
        log.info("Client {} left room {}", session.getId(), sessionId);
    }

    /** Tear down all in-memory state for a room once its last client leaves. */
    private void onRoomEmpty(String sessionId) {
        roomClients.remove(sessionId);
        cursorPositions.remove(sessionId);
        lastActivity.remove(sessionId);
        otService.evict(sessionId);
        log.info("Room {} is empty -> evicted", sessionId);
    }

    /**
     * Backstop sweep for the {@code RoomReaper}: evict rooms that are empty and
     * have seen no activity past the idle TTL. Eager eviction on last-disconnect
     * normally handles this; the reaper catches any room that slips through.
     */
    public List<String> reapIdleRooms(long idleTtlMs, long nowMs) {
        List<String> reaped = new ArrayList<>();
        for (Map.Entry<String, Map<String, WebSocketSession>> e : roomClients.entrySet()) {
            String sid = e.getKey();
            Long last = lastActivity.get(sid);
            boolean idle = last == null || (nowMs - last) > idleTtlMs;
            if (e.getValue().isEmpty() && idle) {
                onRoomEmpty(sid);
                reaped.add(sid);
            }
        }
        return reaped;
    }

    private void broadcastCursorRemove(String sessionId, String clientId, String excludeWsId) {
        try {
            String json = mapper.writeValueAsString(Map.of("type", "cursorRemove", "clientId", clientId));
            broadcast(sessionId, json, excludeWsId);
        } catch (Exception e) {
            log.error("[{}] failed to serialize cursorRemove", sessionId, e);
        }
    }

    private void broadcast(String sessionId, String json, String excludeWsId) {
        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room == null) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : room.values()) {
            if (excludeWsId != null && s.getId().equals(excludeWsId)) continue;
            if (!s.isOpen()) continue;
            try {
                s.sendMessage(msg);
            } catch (IOException e) {
                log.warn("[{}] send to {} failed: {}", sessionId, s.getId(), e.getMessage());
            }
        }
    }

    private void touch(String sessionId) {
        lastActivity.put(sessionId, System.currentTimeMillis());
    }

    private static String getSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            if (pair.startsWith("sessionId=")) {
                return URLDecoder.decode(pair.substring("sessionId=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
