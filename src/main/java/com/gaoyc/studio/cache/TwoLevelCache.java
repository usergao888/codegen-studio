package com.gaoyc.studio.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 两级缓存：Caffeine（L1，进程内热数据）+ 带 TTL 的 Map（L2，冷一点但比重新生成便宜）。
 *
 * <p>为什么两级而不是直接一个：L1 有大小上限和淘汰策略，命中极快但装不下太多；
 * L2 不设上限但只在 TTL 内有效，用来接住"同一个需求被反复提交"的情况。
 * 真实部署里 L2 换成 Redis，接口不变。
 */
public class TwoLevelCache<K, V> {

    private final Cache<K, V> l1;
    private final Map<K, Entry<V>> l2 = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    private record Entry<V>(V value, long expiresAt) {
    }

    public TwoLevelCache(int maxEntries, long ttlMillis) {
        this(maxEntries, ttlMillis, System::currentTimeMillis);
    }

    public TwoLevelCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxEntries))
                // L1 也必须带 TTL：否则 L2 过期了、L1 还在，读到的就是过期数据
                .expireAfterWrite(Duration.ofMillis(Math.max(1, ttlMillis)))
                // 关键：L1 的时钟也换成注入的 clock，否则假时钟只影响 L2，单测永远等不到 L1 过期
                .ticker(() -> clock.getAsLong() * 1_000_000L)
                .build();
    }

    public Optional<V> get(K key) {
        V hot = l1.getIfPresent(key);
        if (hot != null) {
            l1Hits.increment();
            return Optional.of(hot);
        }
        Entry<V> entry = l2.get(key);
        if (entry == null) {
            misses.increment();
            return Optional.empty();
        }
        if (entry.expiresAt() < clock.getAsLong()) {
            l2.remove(key);
            evictions.increment();
            misses.increment();
            return Optional.empty();
        }
        l2Hits.increment();
        l1.put(key, entry.value());
        return Optional.of(entry.value());
    }

    public void put(K key, V value) {
        l1.put(key, value);
        l2.put(key, new Entry<>(value, clock.getAsLong() + ttlMillis));
    }

    public void invalidate(K key) {
        l1.invalidate(key);
        l2.remove(key);
    }

    /** 只清 L1，保留 L2 —— 用于模拟进程内缓存被淘汰，以及测试两级回退。 */
    public void evictL1(K key) {
        l1.invalidate(key);
    }

    public Map<String, Long> stats() {
        return Map.of(
                "l1Hits", l1Hits.sum(),
                "l2Hits", l2Hits.sum(),
                "misses", misses.sum(),
                "evictions", evictions.sum(),
                "l2Size", (long) l2.size()
        );
    }

    public long totalLookups() {
        return l1Hits.sum() + l2Hits.sum() + misses.sum();
    }
}
