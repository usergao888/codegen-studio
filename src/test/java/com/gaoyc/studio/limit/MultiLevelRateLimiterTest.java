package com.gaoyc.studio.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class MultiLevelRateLimiterTest {

    /** 记录每个 key 被调用几次，超过额度即拒绝 —— 用来验证"多级"的组合逻辑，而不是桶算法本身。 */
    private static final class CountingBackend implements RateLimiter {
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<String, Integer> quotas;

        private CountingBackend(Map<String, Integer> quotas) {
            this.quotas = quotas;
        }

        @Override
        public RateLimiter.Decision tryAcquire(String key, int permits, double permitsPerSecond, int burst) {
            int used = calls.merge(key, 1, Integer::sum);
            for (Map.Entry<String, Integer> entry : quotas.entrySet()) {
                if (key.contains(entry.getKey()) && used > entry.getValue()) {
                    return RateLimiter.Decision.deny(250);
                }
            }
            return RateLimiter.Decision.allow();
        }

        @Override
        public String backend() {
            return "fake";
        }
    }

    private MultiLevelRateLimiter limiter(int userQuota) {
        RateLimiter backend = new CountingBackend(Map.of("user", userQuota));
        return new MultiLevelRateLimiter(backend, List.of(
                new MultiLevelRateLimiter.Level("user", 5, 10),
                new MultiLevelRateLimiter.Level("ip", 20, 40),
                new MultiLevelRateLimiter.Level("endpoint", 50, 100)));
    }

    @Test
    void allowsUntilUserQuotaIsHit() {
        MultiLevelRateLimiter limiter = limiter(2);
        Map<String, String> subject = Map.of("user", "u1", "ip", "127.0.0.1", "endpoint", "generate");

        assertTrue(limiter.tryAcquire(subject).allowed());
        assertTrue(limiter.tryAcquire(subject).allowed());
        MultiLevelRateLimiter.Decision third = limiter.tryAcquire(subject);
        assertFalse(third.allowed());
        assertEquals("user", third.rejectedScope());
        assertTrue(third.retryAfterMillis() >= 1);
    }

    @Test
    void differentUsersDoNotShareQuota() {
        MultiLevelRateLimiter limiter = limiter(1);
        assertTrue(limiter.tryAcquire(Map.of("user", "alice", "ip", "1.1.1.1")).allowed());
        assertFalse(limiter.tryAcquire(Map.of("user", "alice", "ip", "1.1.1.1")).allowed());
        assertTrue(limiter.tryAcquire(Map.of("user", "bob", "ip", "1.1.1.1")).allowed());
    }

    @Test
    void missingScopeIsSkipped() {
        MultiLevelRateLimiter limiter = limiter(0);
        // 只给 ip，user 一级不参与，所以不会被 user 额度挡住
        assertTrue(limiter.tryAcquire(Map.of("ip", "10.0.0.1")).allowed());
    }

    @Test
    void reportsBackendAndLevels() {
        MultiLevelRateLimiter limiter = limiter(5);
        assertEquals("fake", limiter.backend());
        assertEquals(3, limiter.levels().size());
    }
}
