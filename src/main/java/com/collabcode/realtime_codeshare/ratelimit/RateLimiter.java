package com.collabcode.realtime_codeshare.ratelimit;

/**
 * A keyed rate limiter (e.g. by client IP). The in-memory implementation is the
 * default; a Redis-backed implementation can replace it for cluster-wide limits
 * without touching callers.
 */
public interface RateLimiter {

    /** @return true if a unit of work is allowed for {@code key} right now, false if it should be throttled. */
    boolean tryAcquire(String key);
}
