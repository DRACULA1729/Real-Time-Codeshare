package com.collabcode.realtime_codeshare.service;

import com.collabcode.realtime_codeshare.model.Session;
import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.security.Role;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session createSession(String sessionId, User creator) {
        Session session = new Session(sessionId, creator);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<Session> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public boolean isIpWhitelisted(String sessionId, String ip) {
        return getSession(sessionId)
                .map(session -> session.getIpWhitelist().contains(ip))
                .orElse(false);
    }

    public boolean submitJoinRequest(String sessionId, User requester) {
        Session session = sessions.get(sessionId);
        if (session == null) return false;
        if (!session.getIpWhitelist().contains(requester.getIpAddress())) return false;

        if (requester.getRejectedAttempts() >= 3) return false;

        session.getPendingJoinRequests().add(requester);
        return true;
    }

    public void rejectUser(String sessionId, User user) {
        Session session = sessions.get(sessionId);
        user.setRejectedAttempts(user.getRejectedAttempts() + 1);
        if (user.getRejectedAttempts() >= 3) {
            session.getPendingJoinRequests().remove(user);
        }
    }

    public void approveUser(String sessionId, User user) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.getActiveUsers().add(user);
            session.getIpWhitelist().add(user.getIpAddress());
        }
    }

    public void shutdown(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.setAlive(false);
            session.getActiveUsers().clear();
            session.getPendingJoinRequests().clear();
        }
    }

    public List<User> getPendingRequests(String sessionId) {
        if((sessions.get(sessionId))==null) return Collections.emptyList();
        return new ArrayList<>(sessions.get(sessionId).getPendingJoinRequests());
    }
}
