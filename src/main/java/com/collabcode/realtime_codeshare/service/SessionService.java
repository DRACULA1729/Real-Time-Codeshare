package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.SessionState;
import com.collabcode.realtime_codeshare.persistence.InMemorySessionRepository;
import com.collabcode.realtime_codeshare.persistence.SessionRepository;
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

    /** A CSS hex color (#rgb .. #rrggbbaa). Anything else falls back to the default. */
    private static final Pattern COLOR_FORMAT = Pattern.compile("^#[0-9a-fA-F]{3,8}$");
    private static final String DEFAULT_COLOR = "#7aa2f7";

    private final SecureRandom random = new SecureRandom();
    /** Resident sessions. The repository is the durable copy: a miss here is tried
     *  there before it counts as "unknown", so a restart keeps rooms and tokens valid. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SessionRepository repo;
    private final int maxRejected;
    private final long adminOfferTimeoutMs;

    @org.springframework.beans.factory.annotation.Autowired
    public SessionService(SessionRepository repo,
                          @Value("${codeshare.session.max-rejected-attempts:3}") int maxRejected,
                          @Value("${codeshare.session.admin-offer-timeout-ms:30000}") long adminOfferTimeoutMs) {
        this.repo = repo;
        this.maxRejected = maxRejected;
        this.adminOfferTimeoutMs = adminOfferTimeoutMs;
    }

    /** Single-process convenience (tests). */
    public SessionService(int maxRejected, long adminOfferTimeoutMs) {
        this(new InMemorySessionRepository(), maxRejected, adminOfferTimeoutMs);
    }

    /** Resident session, or the durable copy loaded on a miss. */
    private Session find(String id) {
        if (id == null) return null;
        Session s = sessions.get(id);
        if (s != null || !isValidIdFormat(id)) return s;
        return repo.load(id).map(st -> sessions.computeIfAbsent(id, k -> st.toSession())).orElse(null);
    }

    /** Durable write-through. Caller holds the session's monitor. */
    private void save(Session s) {
        repo.save(SessionState.of(s));
        s.clearDirty();
    }

    /** Refresh last-activity for sessions touched since their last save (reaper tick). */
    public void persistDirty() {
        for (Session s : sessions.values()) {
            if (s.isDirty()) synchronized (s) { save(s); }
        }
    }

    /** How long a member has to accept a vacated admin seat before it moves on. */
    public long adminOfferTimeoutMs() { return adminOfferTimeoutMs; }

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
        synchronized (session) { save(session); }
        log.info("Created session {}", id);
        return session;
    }

    public Session get(String id) { return find(id); }

    public boolean exists(String id) { return find(id) != null; }

    /** Ended by its admin: kept only until the reap deletes it and its snapshot. */
    public boolean isEnded(String id) {
        Session s = find(id);
        return s != null && s.isEnded();
    }

    /** Number of live sessions (for the global active-session cap). */
    public int count() { return sessions.size(); }

    public boolean isAlive(String id) {
        Session s = find(id);
        return s != null && !s.getActiveOrder().isEmpty();
    }

    public void evict(String id) {
        repo.delete(id);
        if (sessions.remove(id) != null) log.info("Evicted session {}", id);
    }

    public void touch(String id) {
        Session s = find(id);
        if (s != null) s.touch(System.currentTimeMillis());
    }

    // ---------------- admission ----------------

    /**
     * Decide whether a connecting client is admitted, pending approval, or denied.
     * A client with no token is minted one (returned so the client can persist it
     * and skip re-approval on reconnect).
     */
    public JoinResult evaluateJoin(String sessionId, String token, String clientId, String name, String color) {
        Session session = find(sessionId);
        if (session == null) return new JoinResult(JoinOutcome.DENIED, null, null, null, "unknown session");

        synchronized (session) {
            if (session.isEnded()) return new JoinResult(JoinOutcome.DENIED, null, null, null, "session ended");
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
                save(session);
                return new JoinResult(JoinOutcome.ADMITTED, token, null, role, null);
            }

            String effectiveToken = (token == null || token.isBlank()) ? randomToken() : token;

            if (session.getRejectedByToken().getOrDefault(effectiveToken, 0) >= maxRejected) {
                return new JoinResult(JoinOutcome.DENIED, effectiveToken, null, null, "rejected too many times");
            }

            // A reconnecting waiter keeps its join key, so the admin's list doesn't
            // grow a duplicate request (the client de-dupes by key).
            String joinKey = session.getPendingByJoinKey().entrySet().stream()
                    .filter(e -> effectiveToken.equals(e.getValue().getToken()))
                    .map(Map.Entry::getKey).findFirst().orElseGet(this::randomJoinKey);
            session.getPendingByJoinKey().put(joinKey,
                    new User(effectiveToken, clientId, safeName(name), safeColor(color), Role.JOINER));
            save(session);
            return new JoinResult(JoinOutcome.PENDING, effectiveToken, joinKey, Role.JOINER, null);
        }
    }

    /** Admin approves a pending request. Returns the approved user, or null if not pending / not admin. */
    public User approve(String sessionId, String adminToken, String joinKey) {
        Session session = find(sessionId);
        if (session == null) return null;
        synchronized (session) {
            if (!session.isAdmin(adminToken)) return null;
            User u = session.getPendingByJoinKey().remove(joinKey);
            if (u == null) return null;
            session.getApprovedTokens().add(u.getToken());
            session.getMembers().put(u.getToken(), u);
            session.touch(System.currentTimeMillis());
            save(session);
            log.info("Session {}: approved join {}", sessionId, joinKey);
            return u;
        }
    }

    /** Admin rejects a pending request. Returns the rejected user, or null if not pending / not admin. */
    public User reject(String sessionId, String adminToken, String joinKey) {
        Session session = find(sessionId);
        if (session == null) return null;
        synchronized (session) {
            if (!session.isAdmin(adminToken)) return null;
            User u = session.getPendingByJoinKey().remove(joinKey);
            if (u == null) return null;
            session.getRejectedByToken().merge(u.getToken(), 1, Integer::sum);
            session.touch(System.currentTimeMillis());
            save(session);
            log.info("Session {}: rejected join {}", sessionId, joinKey);
            return u;
        }
    }

    /** Revoke a member's access (kick): they may no longer be admitted without re-approval. */
    public void revoke(String sessionId, String token) {
        Session s = find(sessionId);
        if (s == null || token == null) return;
        synchronized (s) {
            s.getApprovedTokens().remove(token);
            s.getMembers().remove(token);
            s.getActiveOrder().remove(token);
            save(s);
        }
    }

    public boolean isAdmitted(String sessionId, String token) {
        Session s = find(sessionId);
        return s != null && s.isApproved(token);
    }

    public boolean isAdmin(String sessionId, String token) {
        Session s = find(sessionId);
        return s != null && s.isAdmin(token);
    }

    public User member(String sessionId, String token) {
        Session s = find(sessionId);
        return s == null ? null : s.getMembers().get(token);
    }

    /**
     * Mark an admitted token as connected (admission-ordered). A parked admin coming
     * back while its seat is on offer simply resumes, and the offer is withdrawn.
     *
     * @return the token whose admin offer was withdrawn, otherwise null.
     */
    public String markActive(String sessionId, String token) {
        Session s = find(sessionId);
        if (s == null) return null;
        synchronized (s) {
            s.getActiveOrder().add(token);
            s.touch(System.currentTimeMillis());
            String offered = s.getAdminOfferToken();
            if (offered == null || !s.isAdmin(token)) return null;
            clearAdminOffer(s);
            log.info("Session {}: admin returned, offer withdrawn", sessionId);
            return offered;
        }
    }

    /**
     * Mark a token disconnected. If it held admin (or the pending admin offer) and
     * others remain connected, offer the seat to the next member in admission order.
     * Nobody is promoted until they accept: the admin token stays parked meanwhile,
     * so admin actions are locked.
     *
     * @return the token newly offered admin, otherwise null.
     */
    public String markInactive(String sessionId, String token) {
        Session s = find(sessionId);
        if (s == null) return null;
        synchronized (s) {
            s.getActiveOrder().remove(token);
            s.touch(System.currentTimeMillis());
            if (token.equals(s.getCurrentAdminToken())) {
                s.getAdminOfferDeclined().clear(); // fresh vacancy
                return offerAdminSeat(sessionId, s, null);
            }
            if (token.equals(s.getAdminOfferToken())) return offerAdminSeat(sessionId, s, null);
            return null;
        }
    }

    /** The offered member takes the admin seat. Returns false if no offer is out for them. */
    public boolean acceptAdmin(String sessionId, String token) {
        Session s = find(sessionId);
        if (s == null || token == null) return false;
        synchronized (s) {
            if (!token.equals(s.getAdminOfferToken())) return false;
            s.setCurrentAdminToken(token);
            User promoted = s.getMembers().get(token);
            if (promoted != null) promoted.setRole(Role.CREATOR);
            clearAdminOffer(s);
            save(s);
            log.info("Session {}: admin offer accepted, admin transferred", sessionId);
            return true;
        }
    }

    /** The offered member declines; the seat is offered to the next member, if any.
     *  @return the token newly offered admin, otherwise null (room stays admin-locked). */
    public String declineAdmin(String sessionId, String token) {
        Session s = find(sessionId);
        if (s == null || token == null) return null;
        synchronized (s) {
            if (!token.equals(s.getAdminOfferToken())) return null;
            s.getAdminOfferDeclined().add(token);
            return offerAdminSeat(sessionId, s, token);
        }
    }

    /** An unanswered offer lapses to the next member (wrapping, so a lone candidate is
     *  re-offered). A no-op unless {@code token} still holds an offer that has expired,
     *  so stale timers are harmless.
     *  @return the token now offered admin, or null if nothing changed. */
    public String lapseAdminOffer(String sessionId, String token, long nowMs) {
        Session s = find(sessionId);
        if (s == null || token == null) return null;
        synchronized (s) {
            if (!token.equals(s.getAdminOfferToken()) || nowMs < s.getAdminOfferDeadlineMs()) return null;
            return offerAdminSeat(sessionId, s, token);
        }
    }

    /** Admin ended the room: it is marked ended (no more tickets or joins) and idles
     *  toward the normal reap, which alone deletes the durable snapshot. */
    public void disconnectAll(String sessionId) {
        Session s = find(sessionId);
        if (s == null) return;
        synchronized (s) {
            s.markEnded();
            s.getActiveOrder().clear();
            clearAdminOffer(s);
            s.touch(System.currentTimeMillis());
            save(s);
        }
    }

    /** Token currently holding the admin offer, or null. */
    public String adminOfferToken(String sessionId) {
        Session s = find(sessionId);
        if (s == null) return null;
        synchronized (s) { return s.getAdminOfferToken(); }
    }

    /**
     * If the admin seat is vacant (admin not connected) with no offer out but members
     * are connected, start a fresh round of offers. Covers "everyone declined" (retried
     * later) and a member reconnecting into a room whose admin is gone.
     * @return the token newly offered admin, otherwise null.
     */
    public String reofferVacantSeat(String sessionId) {
        Session s = find(sessionId);
        if (s == null) return null;
        synchronized (s) {
            if (s.isEnded() || s.getAdminOfferToken() != null || s.getActiveOrder().isEmpty()
                    || s.getActiveOrder().contains(s.getCurrentAdminToken())) return null;
            s.getAdminOfferDeclined().clear();
            return offerAdminSeat(sessionId, s, null);
        }
    }

    /** Offer the seat to the first non-declined connected member after {@code after}
     *  (wrapping; from the longest-present when null/absent). Caller holds the monitor. */
    private String offerAdminSeat(String sessionId, Session s, String after) {
        List<String> order = new ArrayList<>(s.getActiveOrder());
        int start = order.indexOf(after) + 1;
        String next = null;
        for (int i = 0; i < order.size(); i++) {
            String candidate = order.get((start + i) % order.size());
            if (!s.getAdminOfferDeclined().contains(candidate)) { next = candidate; break; }
        }
        s.setAdminOffer(next, next == null ? 0 : System.currentTimeMillis() + adminOfferTimeoutMs);
        if (next != null) log.info("Session {}: admin seat offered to the next member", sessionId);
        return next;
    }

    private static void clearAdminOffer(Session s) {
        s.setAdminOffer(null, 0);
        s.getAdminOfferDeclined().clear();
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
        reaped.forEach(repo::delete);
        reaped.addAll(repo.reapIdle(nowMs - idleTtlMs, sessions.keySet()));
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

    /** Bound and default a display name. Public so the handshake interceptor can
     *  sanitize before the raw value is ever seeded into WS attributes / broadcast. */
    public static String safeName(String name) {
        if (name == null || name.isBlank()) return "Anonymous";
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    /** Defense-in-depth: only accept a hex color, never store/relay arbitrary text. */
    public static String safeColor(String color) {
        return (color != null && COLOR_FORMAT.matcher(color).matches()) ? color : DEFAULT_COLOR;
    }
}
