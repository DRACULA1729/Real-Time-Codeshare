package com.collabcode.realtime_codeshare.ratelimit;

/**
 * Simple per-connection token-bucket rate limiter. One instance guards one
 * WebSocket session; capacity equals the per-second op allowance and refills
 * continuously at that rate, so a client may briefly burst up to {@code capacity}
 * then settle to {@code refillPerSec} sustained ops/sec.
 *
 * Not thread-safe on its own — callers synchronize on the instance, which is
 * cheap since each bucket is touched by a single connection's message thread.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int opsPerSec) {
        this(opsPerSec, System.nanoTime());
    }

    /** Test seam: inject the starting clock reading. */
    TokenBucket(int opsPerSec, long startNanos) {
        this.capacity = Math.max(1, opsPerSec);
        this.refillPerSec = Math.max(1, opsPerSec);
        this.tokens = this.capacity;
        this.lastRefillNanos = startNanos;
    }

    /** @return true if a token was available and consumed. */
    public synchronized boolean tryAcquire() {
        return tryAcquire(System.nanoTime());
    }

    synchronized boolean tryAcquire(long nowNanos) {
        double elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
            lastRefillNanos = nowNanos;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
