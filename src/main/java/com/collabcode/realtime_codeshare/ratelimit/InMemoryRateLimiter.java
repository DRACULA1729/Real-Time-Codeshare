package com.collabcode.realtime_codeshare.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key token-bucket rate limiter for session creation: caps how many sessions
 * one client IP can mint, so an unauthenticated flood of {@code POST /api/sessions}
 * can't exhaust memory (CWE-770). Capacity is the per-minute allowance, refilled
 * continuously so a burst is permitted then sustained at the configured rate.
 *
 * <p>Idle buckets (back at full capacity) are swept periodically so the key map
 * can't grow without bound.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private final double capacity;
    private final double refillPerSec;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    public InMemoryRateLimiter(@Value("${codeshare.rate.session-create-per-min:20}") int perMinute) {
        this.capacity = Math.max(1, perMinute);
        this.refillPerSec = Math.max(1, perMinute) / 60.0;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (key == null) key = "unknown";
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;
            nb.lastNanos = System.nanoTime();
            return nb;
        });
        synchronized (b) {
            long now = System.nanoTime();
            double elapsedSec = (now - b.lastNanos) / 1_000_000_000.0;
            b.lastNanos = now;
            b.tokens = Math.min(capacity, b.tokens + elapsedSec * refillPerSec);
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Drop buckets that have refilled to capacity (the key has gone idle). */
    @Scheduled(fixedDelay = 300_000)
    void sweepIdle() {
        long now = System.nanoTime();
        buckets.forEach((key, b) -> {
            synchronized (b) {
                double elapsedSec = (now - b.lastNanos) / 1_000_000_000.0;
                if (Math.min(capacity, b.tokens + elapsedSec * refillPerSec) >= capacity) {
                    buckets.remove(key, b);
                }
            }
        });
    }
}
