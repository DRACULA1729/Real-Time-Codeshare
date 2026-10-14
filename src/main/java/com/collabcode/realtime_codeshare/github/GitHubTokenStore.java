package com.collabcode.realtime_codeshare.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds each browser's GitHub access token server-side, keyed by HTTP session id.
 * The token never leaves the server and is never logged.
 *
 * <p>Entries carry a TTL and are swept periodically so tokens for sessions that
 * never explicitly disconnect can't accumulate (and stale credentials don't linger
 * indefinitely). Touching an entry on read keeps an active session's token alive.
 */
@Component
public class GitHubTokenStore {

    private final long ttlMs;
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    private record Entry(String token, long expiresAtMs) {}

    public GitHubTokenStore(@Value("${github.token-ttl-ms:3600000}") long ttlMs) {
        this.ttlMs = ttlMs; // default 1h
    }

    public void put(String httpSessionId, String token) {
        tokens.put(httpSessionId, new Entry(token, now() + ttlMs));
    }

    public String get(String httpSessionId) {
        Entry e = tokens.get(httpSessionId);
        if (e == null) return null;
        if (e.expiresAtMs() < now()) {
            tokens.remove(httpSessionId, e);
            return null;
        }
        // Sliding expiry: an actively-used token stays valid.
        tokens.put(httpSessionId, new Entry(e.token(), now() + ttlMs));
        return e.token();
    }

    public boolean has(String httpSessionId) {
        return get(httpSessionId) != null;
    }

    public void remove(String httpSessionId) {
        tokens.remove(httpSessionId);
    }

    @Scheduled(fixedDelay = 600_000)
    void sweepExpired() {
        long now = now();
        tokens.values().removeIf(e -> e.expiresAtMs() < now);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
