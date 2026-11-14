package com.gaoyc.studio.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

public class TokenBucketRateLimiterTest {

    @Test
    void allowsBurstThenBlocks() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 3, () -> 0L);
        assertTrue(limiter.tryAcquire("u").allowed());
        assertTrue(limiter.tryAcquire("u").allowed());
        assertTrue(limiter.tryAcquire("u").allowed());
        assertFalse(limiter.tryAcquire("u").allowed(), "第 4 次应被限流");
    }

    @Test
    void refillsOverTime() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 2, clock::get);
        assertTrue(limiter.tryAcquire("u").allowed());
        assertTrue(limiter.tryAcquire("u").allowed());
        assertFalse(limiter.tryAcquire("u").allowed());

        clock.addAndGet(1_000_000_000L);   // 过 1 秒，补 2 个令牌
        assertTrue(limiter.tryAcquire("u").allowed());
    }

    @Test
    void usersAreIsolated() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, () -> 0L);
        assertTrue(limiter.tryAcquire("alice").allowed());
        assertFalse(limiter.tryAcquire("alice").allowed());
        assertTrue(limiter.tryAcquire("bob").allowed(), "不同用户不应互相影响");
    }

    @Test
    void reportsRetryAfter() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, () -> 0L);
        limiter.tryAcquire("u");
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("u");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMillis() >= 1);
    }

    @Test
    void sweepsIdleBuckets() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, clock::get);
        limiter.tryAcquire("idle");
        assertEquals(1, limiter.trackedKeys());
        clock.addAndGet(120_000_000_000L);   // 2 分钟
        limiter.sweep(60);
        assertEquals(0, limiter.trackedKeys());
    }
}
