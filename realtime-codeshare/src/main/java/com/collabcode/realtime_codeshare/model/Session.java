package com.collabcode.realtime_codeshare.model;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access-control state for one room. Mutating the pending/active collections is
 * not internally synchronized — {@code SessionService} owns a monitor per
 * session and serializes the compound join/approve/reject operations.
 *
 * Identity is the server-minted token throughout. {@code approvedTokens} (the
 * repurposed old IP whitelist) is the set of tokens allowed to be admitted;
 * {@code currentAdminToken} shifts when the host leaves and admin is transferred.
 */
public class Session {

    private final String sessionId;
    private final User creator;
    private final String creatorToken;
    private volatile String currentAdminToken;

    /** Tokens permitted into the room (survives reconnects). */
    private final Set<String> approvedTokens = ConcurrentHashMap.newKeySet();
    /** Admitted members by token. */
    private final Map<String, User> members = new ConcurrentHashMap<>();
    /** Pending join requests keyed by an opaque join key (admit UI never sees tokens/IPs). */
    private final Map<String, User> pendingByJoinKey = new ConcurrentHashMap<>();
    /** How many times each token has been rejected (rejection cap). */
    private final Map<String, Integer> rejectedByToken = new ConcurrentHashMap<>();
    /** Tokens currently connected, in admission order — drives admin transfer. */
    private final Set<String> activeOrder = new LinkedHashSet<>();

    private volatile long lastActivityMs;

    public Session(String sessionId, User creator) {
        this.sessionId = sessionId;
        this.creator = creator;
        this.creatorToken = creator.getToken();
        this.currentAdminToken = creator.getToken();
        this.approvedTokens.add(creator.getToken());
        this.members.put(creator.getToken(), creator);
    }

    public String getSessionId() { return sessionId; }
    public User getCreator() { return creator; }
    public String getCreatorToken() { return creatorToken; }

    public String getCurrentAdminToken() { return currentAdminToken; }
    public void setCurrentAdminToken(String token) { this.currentAdminToken = token; }

    public Set<String> getApprovedTokens() { return approvedTokens; }
    public Map<String, User> getMembers() { return members; }
    public Map<String, User> getPendingByJoinKey() { return pendingByJoinKey; }
    public Map<String, Integer> getRejectedByToken() { return rejectedByToken; }
    public Set<String> getActiveOrder() { return activeOrder; }

    public long getLastActivityMs() { return lastActivityMs; }
    public void touch(long nowMs) { this.lastActivityMs = nowMs; }

    public boolean isApproved(String token) {
        return token != null && approvedTokens.contains(token);
    }

    public boolean isAdmin(String token) {
        return token != null && token.equals(currentAdminToken);
    }
}
