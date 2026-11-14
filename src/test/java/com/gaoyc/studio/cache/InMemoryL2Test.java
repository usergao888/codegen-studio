package com.gaoyc.studio.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

public class InMemoryL2Test {

    @Test
    void storesAndReadsBack() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryL2<String> l2 = new InMemoryL2<>(10_000, clock::get);
        l2.put("k", "v", 10_000);
        assertEquals("v", l2.get("k").orElseThrow());
        assertEquals("memory", l2.backend());
        assertEquals(1, l2.size());
    }

    @Test
    void honoursTtl() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryL2<String> l2 = new InMemoryL2<>(1_000, clock::get);
        l2.put("k", "v", 1_000);
        clock.set(1_500);
        assertFalse(l2.get("k").isPresent());
    }

    @Test
    void removesExplicitly() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryL2<String> l2 = new InMemoryL2<>(10_000, clock::get);
        l2.put("k", "v", 10_000);
        l2.remove("k");
        assertFalse(l2.get("k").isPresent());
    }

    @Test
    void twoLevelCacheUsesInjectedL2() {
        AtomicLong clock = new AtomicLong(0);
        // 容量 1，put 两个之后最早的那个会被挤出 L1，只剩 L2 能兜住
        L2Store<String> l2 = new InMemoryL2<>(60_000, clock::get);
        TwoLevelCache<String, String> cache = new TwoLevelCache<>(1, 60_000, clock::get, l2);

        cache.put("a", "1");
        cache.put("b", "2");
        cache.evictL1("a");            // 显式模拟 L1 淘汰，让断言不依赖 Caffeine 的淘汰时机

        assertEquals("1", cache.get("a").orElseThrow());
        assertEquals(1L, cache.stats().get("l2Hits"));
        assertEquals("memory", cache.l2Backend());
    }

    @Test
    void missWhenNeitherLevelHasKey() {
        AtomicLong clock = new AtomicLong(0);
        TwoLevelCache<String, String> cache =
                new TwoLevelCache<>(10, 60_000, clock::get, new InMemoryL2<>(60_000, clock::get));
        assertTrue(cache.get("absent").isEmpty());
        assertEquals(1L, cache.stats().get("misses"));
    }
}
