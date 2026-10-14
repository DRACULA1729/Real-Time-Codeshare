package com.collabcode.realtime_codeshare.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    @Test
    void allowsUpToCapacityThenThrottles() {
        RateLimiter rl = new InMemoryRateLimiter(5); // 5 per minute => burst of 5
        for (int i = 0; i < 5; i++) {
            assertThat(rl.tryAcquire("1.2.3.4")).as("acquire %d", i).isTrue();
        }
        assertThat(rl.tryAcquire("1.2.3.4")).as("6th is throttled").isFalse();
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new InMemoryRateLimiter(2);
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isTrue();
        assertThat(rl.tryAcquire("a")).isFalse();
        // A different IP has its own bucket.
        assertThat(rl.tryAcquire("b")).isTrue();
    }

    @Test
    void nullKeyDoesNotThrow() {
        RateLimiter rl = new InMemoryRateLimiter(1);
        assertThat(rl.tryAcquire(null)).isTrue();
        assertThat(rl.tryAcquire(null)).isFalse();
    }
}
