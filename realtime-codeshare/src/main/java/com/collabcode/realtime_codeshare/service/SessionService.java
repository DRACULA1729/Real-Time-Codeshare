package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Authoritative in-memory registry of {@link Session} rooms keyed by an
 * unguessable id. Compound join/approve/reject operations synchronize on the
 * individual {@code Session} so the pending/approved collections stay consistent.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** Single source of truth for valid id shape, reused by handler + REST + interceptor. */
    private static final Pattern ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final int maxRejected;

    public SessionService(@Value("${codeshare.session.max-rejected-attempts:3}") int maxRejected) {
        this.maxRejected = maxRejected;
    }

    public static boolean isValidIdFormat(String id) {
        return id != null && ID_FORMAT.matcher(id).matches();
    }

    public enum JoinOutcome { ADMITTED, PENDING, DENIED }

    /** Result of a connection's admission attempt. {@code token} is the effective member token. */
    public record JoinResult(JoinOutcome outcome, String token, String joinKey, Role role, String reason) {}

    // ---------------- lifecycle ----------------

    public Session createSession(String creatorName, String color) {
        String id;
        Session session;
        do {
            id = randomId();
            String adminToken = randomToken();
            User creator = new User(adminToken, null, safeName(creatorName), safeColor(color), Role.CREATOR);
            session = new Session(id, creator);
        } while (sessions.putIfAbsent(id, session) != null); // retry on the astronomically rare id clash
        session.touch(System.currentTimeMillis());
        log.info("Created session {}", id);
        return session;
    }

    public Session get(String id) { return sessions.get(id); }

    public boolean exists(String id) { return sessions.containsKey(id); }

    public boolean isAlive(String id) {
        Session s = sessions.get(id);
        return s != null && !s.getActiveOrder().isEmpty();
    }

    public void evict(String id) {
        if (sessions.remove(id) != null) log.info("Evicted session {}", id);
    }

    public void touch(String id) {
        Session s = sessions.get(id);
        if (s != null) s.touch(System.currentTimeMillis());
    }

    // ---------------- admission ----------------

    /**
     * Decide whether a connecting client is admitted, pending approval, or denied.
     * A client with no token is minted one (returned so the client can persist it
     * and skip re-approval on reconnect).
     */
    public JoinResult evaluateJoin(String sessionId, String token, String clientId, String name, String color) {
        Session session = sessions.get(sessionId);
        if (session == null) return new JoinResult(JoinOutcome.DENIED, null, null, null, "unknown session");

        synchronized (session) {
            session.touch(System.currentTimeMillis());

            // Already approved (creator or a previously-admitted joiner reconnecting).
            if (session.isApproved(token)) {
                User member = session.getMembers().get(token);
                Role role = session.isAdmin(token) ? Role.CREATOR : (member != null ? member.getRole() : Role.JOINER);
                if (member != null) {
                    member.setClientId(clientId);
                    if (name != null) member.setName(safeName(name));
                    if (color != null) member.setColor(safeColor(color));
                } else {
                    session.getMembers().put(token, new User(token, clientId, safeName(name), safeColor(color), role));
                }
                return new JoinResult(JoinOutcome.ADMITTED, token, null, role, null);
            }

            String effectiveToken = (token == null || token.isBlank()) ? randomToken() : token;

            if (session.getRejectedByToken().getOrDefault(effectiveToken, 0) >= maxRejected) {
                return new JoinResult(JoinOutcome.DENIED, effectiveToken, null, null, "rejected too many times");
            }

            // Replace any stale pending entry for this token, then enqueue a fresh request.
            session.getPendingByJoinKey().values().removeIf(u -> effectiveToken.equals(u.getToken()));
            String joinKey = randomJoinKey();
            session.getPendingByJoinKey().put(joinKey,
                    new User(effectiveToken, clientId, safeName(name), safeColor(color), Role.JOINER));
            return new JoinResult(JoinOutcome.PENDING, effectiveToken, joinKey, Role.JOINER, null);
        }
    }

    /** Admin approves a pending request. Returns the approved user, or null if not pending / not admin. */
    public User approve(String sessionId, String adminToken, String joinKey) {
        Session session = sessions.get(sessionId);
        if (session == null) return null;
        synchronized (session) {
            if (!session.isAdmin(adminToken)) return null;
            User u = session.getPendingByJoinKey().remove(joinKey);
            if (u == null) return null;
            session.getApprovedTokens().add(u.getToken());
            session.getMembers().put(u.getToken(), u);
            session.touch(System.currentTimeMillis());
            log.info("Session {}: approved join {}", sessionId, joinKey);
            return u;
        }
    }

    /** Admin rejects a pending request. Returns the rejected user, or null if not pending / not admin. */
    public User reject(String sessionId, String adminToken, String joinKey) {
        Session session = sessions.get(sessionId);
        if (session == null) return null;
        synchronized (session) {
            if (!session.isAdmin(adminToken)) return null;
            User u = session.getPendingByJoinKey().remove(joinKey);
            if (u == null) return null;
            session.getRejectedByToken().merge(u.getToken(), 1, Integer::sum);
            session.touch(System.currentTimeMillis());
            log.info("Session {}: rejected join {}", sessionId, joinKey);
            return u;
        }
    }

    /** Revoke a member's access (kick): they may no longer be admitted without re-approval. */
    public void revoke(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        if (s == null || token == null) return;
        synchronized (s) {
            s.getApprovedTokens().remove(token);
            s.getMembers().remove(token);
            s.getActiveOrder().remove(token);
        }
    }

    public boolean isAdmitted(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        return s != null && s.isApproved(token);
    }

    public boolean isAdmin(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        return s != null && s.isAdmin(token);
    }

    public User member(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        return s == null ? null : s.getMembers().get(token);
    }

    /** Mark an admitted token as connected (admission-ordered). */
    public void markActive(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        if (s == null) return;
        synchronized (s) {
            s.getActiveOrder().add(token);
            s.touch(System.currentTimeMillis());
        }
    }

    /**
     * Mark a token disconnected. If it held admin and others remain connected,
     * promote the longest-present remaining member to CREATOR.
     *
     * @return the new admin token if admin transferred, otherwise null.
     */
    public String markInactive(String sessionId, String token) {
        Session s = sessions.get(sessionId);
        if (s == null) return null;
        synchronized (s) {
            s.getActiveOrder().remove(token);
            s.touch(System.currentTimeMillis());
            if (!token.equals(s.getCurrentAdminToken())) return null;

            String next = s.getActiveOrder().stream().findFirst().orElse(null); // longest-present
            if (next == null) return null; // room empty; admin role stays parked on the creator token
            s.setCurrentAdminToken(next);
            User promoted = s.getMembers().get(next);
            if (promoted != null) promoted.setRole(Role.CREATOR);
            log.info("Session {}: admin transferred to {}", sessionId, next);
            return next;
        }
    }

    /** Reap idle sessions with no connected members. Returns the evicted ids. */
    public List<String> reapIdle(long idleTtlMs, long nowMs) {
        List<String> reaped = new ArrayList<>();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            synchronized (s) {
                if (s.getActiveOrder().isEmpty() && (nowMs - s.getLastActivityMs()) > idleTtlMs) {
                    reaped.add(e.getKey());
                }
            }
        }
        reaped.forEach(sessions::remove);
        if (!reaped.isEmpty()) log.info("Reaped {} idle session(s)", reaped.size());
        return reaped;
    }

    // ---------------- helpers ----------------

    private String randomId() { return randomUrlSafe(18); }      // 24 chars
    private String randomToken() { return randomUrlSafe(24); }   // 32 chars
    private String randomJoinKey() { return randomUrlSafe(12); } // 16 chars

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "Anonymous";
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private static String safeColor(String color) {
        return (color == null || color.isBlank()) ? "#7aa2f7" : color;
    }
}
