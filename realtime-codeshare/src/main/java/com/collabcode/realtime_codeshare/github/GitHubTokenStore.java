package com.collabcode.realtime_codeshare.github;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each browser's GitHub access token server-side, keyed by HTTP session id.
 * The token never leaves the server and is never logged.
 */
@Component
public class GitHubTokenStore {

    private final Map<String, String> tokensByHttpSession = new ConcurrentHashMap<>();

    public void put(String httpSessionId, String token) {
        tokensByHttpSession.put(httpSessionId, token);
    }

    public String get(String httpSessionId) {
        return tokensByHttpSession.get(httpSessionId);
    }

    public boolean has(String httpSessionId) {
        return tokensByHttpSession.containsKey(httpSessionId);
    }

    public void remove(String httpSessionId) {
        tokensByHttpSession.remove(httpSessionId);
    }
}
