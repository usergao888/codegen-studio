package com.gaoyc.studio.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

public class TwoLevelCacheTest {

    @Test
    void returnsValueFromL1() {
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(10, 60_000);
        cache.put("k", "v");
        assertEquals(Optional.of("v"), cache.get("k"));
        assertEquals(1L, cache.stats().get("l1Hits"));
    }

    @Test
    void fallsBackToL2WhenL1Misses() {
        AtomicLong clock = new AtomicLong(0);
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(10, 60_000, clock::get);
        cache.put("k", "v");
        cache.evictL1("k");               // 模拟进程内热点被淘汰，L2 还在
        assertEquals(Optional.of("v"), cache.get("k"));
        assertEquals(1L, cache.stats().get("l2Hits"));
        assertEquals(0L, cache.stats().get("l1Hits"));
    }

    @Test
    void expiresAfterTtl() {
        AtomicLong clock = new AtomicLong(0);
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(10, 1_000, clock::get);
        cache.put("k", "v");
        clock.set(1_500);
        assertFalse(cache.get("k").isPresent());
        assertTrue(cache.stats().get("evictions") >= 1);
    }

    @Test
    void tracksMisses() {
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(10, 60_000);
        cache.get("nope");
        assertEquals(1L, cache.stats().get("misses"));
    }

    @Test
    void doesNotGrowBeyondL2SemanticsForSameKey() {
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(10, 60_000);
        cache.put("k", "v1");
        cache.put("k", "v2");
        assertEquals(Optional.of("v2"), cache.get("k"));
    }
}
