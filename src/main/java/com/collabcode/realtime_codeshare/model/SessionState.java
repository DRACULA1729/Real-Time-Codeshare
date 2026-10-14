package com.collabcode.realtime_codeshare.model;

import com.collabcode.realtime_codeshare.security.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The durable part of a {@link Session}: who may enter and who is admin. Connection
 * state (who is online, a pending admin offer) is deliberately left out — after a
 * restart everyone reconnects and that state rebuilds itself.
 */
public record SessionState(String sessionId,
                           UserState creator,
                           String currentAdminToken,
                           List<String> approvedTokens,
                           List<UserState> members,
                           Map<String, UserState> pendingByJoinKey,
                           Map<String, Integer> rejectedByToken,
                           long lastActivityMs,
                           boolean ended) {

    public record UserState(String token, String clientId, String name, String color, String role) {
        static UserState of(User u) {
            return new UserState(u.getToken(), u.getClientId(), u.getName(), u.getColor(), u.getRole().name());
        }
        User toUser() {
            return new User(token, clientId, name, color, Role.valueOf(role));
        }
    }

    /** Caller holds the session's monitor. */
    public static SessionState of(Session s) {
        List<UserState> members = new ArrayList<>();
        s.getMembers().values().forEach(u -> members.add(UserState.of(u)));
        Map<String, UserState> pending = new LinkedHashMap<>();
        s.getPendingByJoinKey().forEach((k, u) -> pending.put(k, UserState.of(u)));
        return new SessionState(s.getSessionId(), UserState.of(s.getCreator()), s.getCurrentAdminToken(),
                new ArrayList<>(s.getApprovedTokens()), members, pending,
                new LinkedHashMap<>(s.getRejectedByToken()), s.getLastActivityMs(), s.isEnded());
    }

    public Session toSession() {
        Session s = new Session(sessionId, creator.toUser());
        s.setCurrentAdminToken(currentAdminToken);
        s.getApprovedTokens().addAll(approvedTokens);
        members.forEach(m -> s.getMembers().put(m.token(), m.toUser()));
        pendingByJoinKey.forEach((k, u) -> s.getPendingByJoinKey().put(k, u.toUser()));
        s.getRejectedByToken().putAll(rejectedByToken);
        s.touch(lastActivityMs);
        if (ended) s.markEnded();
        s.clearDirty();
        return s;
    }
}
