package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.ot.InvalidOpException;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.ot.ResyncRequiredException;
import com.collabcode.realtime_codeshare.ratelimit.TokenBucket;
import com.collabcode.realtime_codeshare.security.Role;
import com.collabcode.realtime_codeshare.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meet-style gateway over the OT engine. The handshake interceptor has already
 * validated the session id; this handler enforces admission (creator/approved
 * tokens edit immediately, new joiners wait in a room for host approval),
 * relays ops/cursors only for admitted clients, services admin actions, and
 * transfers admin when the host leaves.
 */
@Component
public class OTWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OTWebSocketHandler.class);

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_BYTES = 512 * 1024;

    // WS-session attribute keys (sessionId/token/clientId/name/color seeded by the interceptor).
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_TOKEN = "token";
    private static final String ATTR_CLIENT_ID = "clientId";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_COLOR = "color";
    private static final String ATTR_ROLE = "role";
    private static final String ATTR_JOIN_KEY = "joinKey";
    private static final String ATTR_ADMITTED = "admitted";
    private static final String ATTR_BUCKET = "bucket";
    private static final String ATTR_CURSOR_BUCKET = "cursorBucket";

    private final OTService otService;
    private final SessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int opsPerSec;
    private final int cursorsPerSec;

    // Metrics: committed ops, throttled inbound messages, failed broadcast sends.
    private final Counter opsCommitted;
    private final Counter rateLimitDrops;
    private final Counter sendFailures;

    // sessionId -> (wsId -> decorated socket). Admitted clients only.
    private final Map<String, Map<String, WebSocketSession>> roomClients = new ConcurrentHashMap<>();
    // sessionId -> (wsId -> decorated socket). Clients awaiting approval.
    private final Map<String, Map<String, WebSocketSession>> pendingSockets = new ConcurrentHashMap<>();
    // sessionId -> (clientId -> cursor index)
    private final Map<String, Map<String, Integer>> cursorPositions = new ConcurrentHashMap<>();
    // sessionId -> last activity epoch millis (idle-reaper backstop)
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();

    // Closes slow/dead peers off the broadcast thread, so afterConnectionClosed
    // (which mutates roomClients and may re-broadcast) never runs re-entrantly inside
    // the broadcast loop that is iterating the same room.
    private final java.util.concurrent.ExecutorService closeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ws-peer-close");
                t.setDaemon(true);
                return t;
            });

    public OTWebSocketHandler(OTService otService,
                              SessionService sessionService,
                              MeterRegistry meters,
                              @Value("${codeshare.rate.ops-per-sec:50}") int opsPerSec,
                              @Value("${codeshare.rate.cursors-per-sec:30}") int cursorsPerSec) {
        this.otService = otService;
        this.sessionService = sessionService;
        this.opsPerSec = opsPerSec;
        this.cursorsPerSec = cursorsPerSec;
        this.opsCommitted = meters.counter("codeshare.ops.committed");
        this.rateLimitDrops = meters.counter("codeshare.ratelimit.drops");
        this.sendFailures = meters.counter("codeshare.broadcast.send.failures");
    }

    /** Total admitted WebSocket connections across all rooms (for the connections gauge). */
    public int connectionCount() {
        int n = 0;
        for (Map<String, WebSocketSession> room : roomClients.values()) n += room.size();
        return n;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String sessionId = attr(raw, ATTR_SESSION_ID);
        if (sessionId == null) { // interceptor should have guaranteed this
            raw.close(CloseStatus.BAD_DATA);
            return;
        }
        // TERMINATE: a peer that can't keep up (send buffer/time limit exceeded) is
        // closed rather than blocking the room's fan-out — no head-of-line stall on one
        // slow client (P2-2). The decorator serializes sends per session, preserving order.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        raw.getAttributes().put(ATTR_BUCKET, new TokenBucket(opsPerSec));
        raw.getAttributes().put(ATTR_CURSOR_BUCKET, new TokenBucket(cursorsPerSec));

        String clientId = attr(raw, ATTR_CLIENT_ID);
        SessionService.JoinResult jr = sessionService.evaluateJoin(
                sessionId, attr(raw, ATTR_TOKEN), clientId, attr(raw, ATTR_NAME), attr(raw, ATTR_COLOR));
        raw.getAttributes().put(ATTR_TOKEN, jr.token());           // effective (possibly minted) token
        raw.getAttributes().put(ATTR_ROLE, jr.role() == null ? Role.JOINER : jr.role());

        switch (jr.outcome()) {
            case ADMITTED -> admit(session, sessionId);
            case PENDING -> {
                raw.getAttributes().put(ATTR_ADMITTED, false);
                raw.getAttributes().put(ATTR_JOIN_KEY, jr.joinKey());
                pendingSockets.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                        .put(raw.getId(), session);
                touch(sessionId);
                sendJson(session, Map.of("type", "waitingForApproval", "token", jr.token()));
                notifyAdminsOfRequest(sessionId, jr.joinKey(), attr(raw, ATTR_NAME), attr(raw, ATTR_COLOR));
                log.info("Session {}: {} pending approval", sessionId, clientId);
            }
            case DENIED -> {
                sendJson(session, Map.of("type", "joinDenied", "reason", jr.reason() == null ? "denied" : jr.reason()));
                session.close(CloseStatus.POLICY_VIOLATION);
                log.info("Session {}: denied join ({})", sessionId, jr.reason());
            }
        }
    }

    /** Admit an already-decided socket: register it, send init, refresh presence. */
    private void admit(WebSocketSession session, String sessionId) throws IOException {
        String token = attr(session, ATTR_TOKEN);
        session.getAttributes().put(ATTR_ADMITTED, true);
        roomClients.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(session.getId(), session);
        sessionService.markActive(sessionId, token);
        touch(sessionId);

        sendJson(session, Map.of(
                "type", "init",
                "document", otService.getDocument(sessionId),
                "version", otService.getVersion(sessionId),
                "token", token,
                "role", roleOf(sessionId, token),
                "clientId", nullSafe(attr(session, ATTR_CLIENT_ID)),
                "fileName", otService.getName(sessionId)
        ));
        broadcastPresence(sessionId);
        if (sessionService.isAdmin(sessionId, token)) sendPendingListTo(session, sessionId);
        log.info("Session {}: admitted {} as {}", sessionId, attr(session, ATTR_CLIENT_ID), roleOf(sessionId, token));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = attr(session, ATTR_SESSION_ID);
        if (sessionId == null) return;
        sessionService.touch(sessionId);
        touch(sessionId);

        JsonNode node;
        try {
            node = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("[{}] dropping non-JSON message: {}", sessionId, e.getMessage());
            return;
        }
        String type = node.path("type").asText("").toLowerCase(Locale.ROOT);
        String token = attr(session, ATTR_TOKEN);

        switch (type) {
            case "insert", "delete" -> { if (requireAdmitted(session, sessionId)) handleOp(session, sessionId, node); }
            case "cursor" -> { if (requireAdmitted(session, sessionId)) handleCursor(session, sessionId, node); }
            case "resync" -> { if (requireAdmitted(session, sessionId)) handleResync(session, sessionId); }
            case "rename" -> { if (requireAdmitted(session, sessionId)) handleRename(session, sessionId, node); }
            case "approve" -> { if (requireAdmin(sessionId, token)) handleApprove(sessionId, token, node); }
            case "reject" -> { if (requireAdmin(sessionId, token)) handleReject(sessionId, token, node); }
            case "kick" -> { if (requireAdmin(sessionId, token)) handleKick(sessionId, token, node); }
            case "endsession" -> { if (requireAdmin(sessionId, token)) endSession(sessionId); }
            default -> log.warn("[{}] ignoring message of unknown type '{}'", sessionId, type);
        }
    }

    // ---------------- edit / cursor ----------------

    private void handleOp(WebSocketSession session, String sessionId, JsonNode node) {
        TokenBucket bucket = (TokenBucket) session.getAttributes().get(ATTR_BUCKET);
        if (bucket != null && !bucket.tryAcquire()) {
            rateLimitDrops.increment();
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
        } catch (ResyncRequiredException e) {
            // Client is too far behind to transform safely; force it to rebase its
            // unacked edits onto the authoritative snapshot rather than apply a corrupt op.
            log.info("[{}] forcing resync for {}: {}", sessionId, session.getId(), e.getMessage());
            sendResync(session, sessionId, true);
            return;
        }
        opsCommitted.increment();
        // Broadcast to everyone including sender — the client advances its pending
        // queue on its own ack. Drift is caught by the idle SHA-256 resync probe,
        // so no per-op hash is broadcast.
        broadcast(sessionId, json(Map.of(
                "type", "op",
                "op", result.getTransformedOp(),
                "version", result.getNewVersion())), null);
    }

    /** A caught-up client that detected drift asks for the authoritative document. */
    private void handleResync(WebSocketSession session, String sessionId) {
        sendResync(session, sessionId, false);
        log.info("Session {}: re-synced a drifted client", sessionId);
    }

    /** Send the authoritative snapshot. {@code required} marks a server-forced resync
     *  (the client must rebase its unacked edits), vs a routine idle self-heal. */
    private void sendResync(WebSocketSession session, String sessionId, boolean required) {
        OTService.Snapshot snap = otService.snapshot(sessionId);
        sendJson(session, Map.of(
                "type", "resync",
                "document", snap.document(),
                "version", snap.version(),
                "hash", snap.docHash(),
                "required", required));
    }

    private void handleCursor(WebSocketSession session, String sessionId, JsonNode node) {
        // Cursor floods amplify room-wide, so they get their own budget; drop silently
        // when exceeded (a dropped cursor frame is cosmetic, unlike a dropped edit).
        TokenBucket bucket = (TokenBucket) session.getAttributes().get(ATTR_CURSOR_BUCKET);
        if (bucket != null && !bucket.tryAcquire()) { rateLimitDrops.increment(); return; }

        String clientId = attr(session, ATTR_CLIENT_ID);
        if (clientId == null || !node.has("pos")) return;
        int pos = Math.max(0, node.get("pos").asInt());
        cursorPositions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(clientId, pos);
        // Reserialize to a known {type,clientId,pos} shape — never relay raw client JSON.
        broadcast(sessionId, json(Map.of("type", "cursor", "clientId", clientId, "pos", pos)), session.getId());
    }

    /** Any admitted participant may rename the room's single document. Charged against
     *  the ops budget (renames are rare; a flood shouldn't get its own free lane). */
    private void handleRename(WebSocketSession session, String sessionId, JsonNode node) {
        TokenBucket bucket = (TokenBucket) session.getAttributes().get(ATTR_BUCKET);
        if (bucket != null && !bucket.tryAcquire()) {
            rateLimitDrops.increment();
            log.warn("[{}] rate limit exceeded for {}; dropping rename", sessionId, session.getId());
            return;
        }
        JsonNode raw = node.get("fileName");
        if (raw == null || !raw.isTextual()) {
            log.warn("[{}] dropping rename with missing/non-string fileName", sessionId);
            return;
        }
        String clean = OTService.sanitizeBaseName(raw.asText());
        otService.setName(sessionId, clean);
        // Echo to the sender too — the server-sanitized value is authoritative, and
        // concurrent renames converge last-write-wins in broadcast order.
        broadcast(sessionId, json(Map.of("type", "rename", "fileName", clean)), null);
    }

    // ---------------- admin actions ----------------

    private void handleApprove(String sessionId, String adminToken, JsonNode node) {
        String joinKey = node.path("joinKey").asText(null);
        if (joinKey == null) return;
        User u = sessionService.approve(sessionId, adminToken, joinKey);
        if (u == null) return;
        WebSocketSession pending = takePendingByJoinKey(sessionId, joinKey);
        if (pending != null && pending.isOpen()) {
            try {
                admit(pending, sessionId);
            } catch (IOException e) {
                log.warn("[{}] failed to admit approved client: {}", sessionId, e.getMessage());
            }
        }
        sendToAdmins(sessionId, json(Map.of("type", "joinResolved", "joinKey", joinKey)));
    }

    private void handleReject(String sessionId, String adminToken, JsonNode node) {
        String joinKey = node.path("joinKey").asText(null);
        if (joinKey == null) return;
        User u = sessionService.reject(sessionId, adminToken, joinKey);
        if (u == null) return;
        WebSocketSession pending = takePendingByJoinKey(sessionId, joinKey);
        if (pending != null) {
            sendJson(pending, Map.of("type", "joinDenied", "reason", "rejected by host"));
            closeQuietly(pending, CloseStatus.POLICY_VIOLATION);
        }
        sendToAdmins(sessionId, json(Map.of("type", "joinResolved", "joinKey", joinKey)));
    }

    private void handleKick(String sessionId, String adminToken, JsonNode node) {
        String targetClientId = node.path("clientId").asText(null);
        if (targetClientId == null) return;
        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room == null) return;
        for (WebSocketSession s : room.values()) {
            if (targetClientId.equals(attr(s, ATTR_CLIENT_ID))) {
                String token = attr(s, ATTR_TOKEN);
                if (token != null && token.equals(adminToken)) continue; // don't let admin kick self
                sessionService.revoke(sessionId, token);
                sendJson(s, Map.of("type", "kicked"));
                closeQuietly(s, CloseStatus.POLICY_VIOLATION);
                log.info("Session {}: kicked {}", sessionId, targetClientId);
                break;
            }
        }
    }

    private void endSession(String sessionId) {
        log.info("Session {}: ended by admin", sessionId);
        closeAll(roomClients.remove(sessionId), "sessionEnded");
        closeAll(pendingSockets.remove(sessionId), "sessionEnded");
        cursorPositions.remove(sessionId);
        lastActivity.remove(sessionId);
        otService.evict(sessionId);
        sessionService.evict(sessionId);
    }

    // ---------------- lifecycle ----------------

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = attr(session, ATTR_SESSION_ID);
        if (sessionId == null) return;
        String token = attr(session, ATTR_TOKEN);
        String clientId = attr(session, ATTR_CLIENT_ID);
        boolean wasAdmitted = Boolean.TRUE.equals(session.getAttributes().get(ATTR_ADMITTED));

        if (wasAdmitted) {
            Map<String, WebSocketSession> room = roomClients.get(sessionId);
            if (room != null) {
                room.remove(session.getId());
                if (clientId != null) {
                    Map<String, Integer> cursors = cursorPositions.get(sessionId);
                    if (cursors != null) cursors.remove(clientId);
                    broadcast(sessionId, json(Map.of("type", "cursorRemove", "clientId", clientId)), session.getId());
                }
                String newAdmin = sessionService.markInactive(sessionId, token);
                if (newAdmin != null) announceAdmin(sessionId, newAdmin);
                if (room.isEmpty()) {
                    onRoomEmpty(sessionId);
                } else {
                    broadcastPresence(sessionId);
                }
            }
        } else {
            Map<String, WebSocketSession> pend = pendingSockets.get(sessionId);
            if (pend != null) pend.remove(session.getId());
            String joinKey = attr(session, ATTR_JOIN_KEY);
            if (joinKey != null) sendToAdmins(sessionId, json(Map.of("type", "joinResolved", "joinKey", joinKey)));
        }
        log.info("Session {}: {} disconnected", sessionId, clientId);
    }

    /** Last admitted client left: persist a snapshot and keep the document resident
     *  (not evicted) so a transient disconnect-reconnect serves the real document, not
     *  an empty one. The Session and its document are reclaimed together by the reaper
     *  once the idle TTL elapses. */
    private void onRoomEmpty(String sessionId) {
        roomClients.remove(sessionId);
        cursorPositions.remove(sessionId);
        lastActivity.remove(sessionId);
        otService.persist(sessionId);
        // Nobody can approve queued joiners now; release them.
        closeAll(pendingSockets.remove(sessionId), "host left");
        log.info("Room {} has no admitted clients -> snapshot persisted, document retained", sessionId);
    }

    /** Backstop sweep for the reaper: evict any room left empty and idle. */
    public List<String> reapIdleRooms(long idleTtlMs, long nowMs) {
        List<String> reaped = new ArrayList<>();
        for (Map.Entry<String, Map<String, WebSocketSession>> e : roomClients.entrySet()) {
            Long last = lastActivity.get(e.getKey());
            boolean idle = last == null || (nowMs - last) > idleTtlMs;
            if (e.getValue().isEmpty() && idle) {
                onRoomEmpty(e.getKey());
                reaped.add(e.getKey());
            }
        }
        return reaped;
    }

    // ---------------- presence / admin notifications ----------------

    private void broadcastPresence(String sessionId) {
        broadcast(sessionId, buildPresence(sessionId), null);
    }

    private String buildPresence(String sessionId) {
        List<Map<String, Object>> users = new ArrayList<>();
        Map<String, WebSocketSession> room = roomClients.getOrDefault(sessionId, Map.of());
        for (WebSocketSession s : room.values()) {
            users.add(Map.of(
                    "clientId", nullSafe(attr(s, ATTR_CLIENT_ID)),
                    "name", nullSafe(attr(s, ATTR_NAME)),
                    "color", nullSafe(attr(s, ATTR_COLOR)),
                    "role", roleOf(sessionId, attr(s, ATTR_TOKEN))
            ));
        }
        return json(Map.of("type", "presence", "users", users));
    }

    private void notifyAdminsOfRequest(String sessionId, String joinKey, String name, String color) {
        sendToAdmins(sessionId, json(Map.of(
                "type", "joinRequest", "joinKey", joinKey,
                "name", nullSafe(name), "color", nullSafe(color))));
    }

    /** Tell the room who the admin is, hand the new admin the pending queue. */
    private void announceAdmin(String sessionId, String newAdminToken) {
        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room == null) return;
        String adminClientId = null;
        WebSocketSession adminSocket = null;
        for (WebSocketSession s : room.values()) {
            if (newAdminToken.equals(attr(s, ATTR_TOKEN))) {
                adminSocket = s;
                adminClientId = attr(s, ATTR_CLIENT_ID);
                break;
            }
        }
        broadcast(sessionId, json(Map.of("type", "adminChanged", "clientId", nullSafe(adminClientId))), null);
        broadcastPresence(sessionId);
        if (adminSocket != null) sendPendingListTo(adminSocket, sessionId);
    }

    private void sendPendingListTo(WebSocketSession session, String sessionId) {
        var sess = sessionService.get(sessionId);
        if (sess == null) return;
        sess.getPendingByJoinKey().forEach((joinKey, u) -> sendJson(session, Map.of(
                "type", "joinRequest", "joinKey", joinKey,
                "name", nullSafe(u.getName()), "color", nullSafe(u.getColor()))));
    }

    private void sendToAdmins(String sessionId, String json) {
        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room == null) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : room.values()) {
            if (sessionService.isAdmin(sessionId, attr(s, ATTR_TOKEN)) && s.isOpen()) {
                trySend(s, msg, sessionId);
            }
        }
    }

    // ---------------- transport helpers ----------------

    private boolean requireAdmitted(WebSocketSession session, String sessionId) {
        boolean ok = Boolean.TRUE.equals(session.getAttributes().get(ATTR_ADMITTED))
                && sessionService.isAdmitted(sessionId, attr(session, ATTR_TOKEN));
        if (!ok) log.warn("[{}] dropping message from non-admitted client {}", sessionId, session.getId());
        return ok;
    }

    private boolean requireAdmin(String sessionId, String token) {
        boolean ok = sessionService.isAdmin(sessionId, token);
        if (!ok) log.warn("[{}] dropping admin action from non-admin", sessionId);
        return ok;
    }

    private WebSocketSession takePendingByJoinKey(String sessionId, String joinKey) {
        Map<String, WebSocketSession> pend = pendingSockets.get(sessionId);
        if (pend == null) return null;
        for (Map.Entry<String, WebSocketSession> e : pend.entrySet()) {
            if (joinKey.equals(attr(e.getValue(), ATTR_JOIN_KEY))) {
                pend.remove(e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    private void broadcast(String sessionId, String json, String excludeWsId) {
        Map<String, WebSocketSession> room = roomClients.get(sessionId);
        if (room == null) return;
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : room.values()) {
            if (excludeWsId != null && s.getId().equals(excludeWsId)) continue;
            if (s.isOpen()) trySend(s, msg, sessionId);
        }
    }

    private void closeAll(Map<String, WebSocketSession> sockets, String type) {
        if (sockets == null) return;
        for (WebSocketSession s : sockets.values()) {
            sendJson(s, Map.of("type", type));
            closeQuietly(s, CloseStatus.NORMAL);
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        if (session.isOpen()) trySend(session, new TextMessage(json(payload)), attr(session, ATTR_SESSION_ID));
    }

    private void trySend(WebSocketSession session, TextMessage msg, String sessionId) {
        try {
            session.sendMessage(msg);
        } catch (SessionLimitExceededException e) {
            // The decorator's TERMINATE strategy flags the session and throws once the
            // send buffer/time limit is exceeded, but it does NOT close the socket — so
            // close it (off this thread) to evict the slow peer; afterConnectionClosed
            // then does presence/admin cleanup without re-entering this broadcast loop.
            sendFailures.increment();
            log.warn("[{}] closing slow peer {}: {}", sessionId, session.getId(), e.getMessage());
            closeExecutor.execute(() -> closeQuietly(session, CloseStatus.SESSION_NOT_RELIABLE));
        } catch (IOException | RuntimeException e) {
            // IOException on a broken pipe, or IllegalStateException when the peer
            // socket is mid-close during a broadcast. Best-effort send: never let
            // one dead socket disrupt the room.
            sendFailures.increment();
            log.warn("[{}] send to {} failed: {}", sessionId, session.getId(), e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // socket already gone
        }
    }

    private String roleOf(String sessionId, String token) {
        return sessionService.isAdmin(sessionId, token) ? "CREATOR" : "JOINER";
    }

    private void touch(String sessionId) {
        lastActivity.put(sessionId, System.currentTimeMillis());
    }

    private String json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize payload", e);
        }
    }

    private static String attr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v == null ? null : v.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
