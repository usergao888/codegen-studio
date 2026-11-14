package com.gaoyc.studio.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 两级缓存：Caffeine（L1，进程内热数据）+ 可插拔 L2（内存或 Redis）。
 *
 * <p>为什么两层而不是一层：
 * <ul>
 *   <li>L1 有大小上限和淘汰策略，命中在纳秒级，但装不下太多、也**不跨进程**；</li>
 *   <li>L2 用来接住"同一需求被反复提交"，单机时放内存，多实例时换 Redis 才能共享。</li>
 * </ul>
 * 两层的 TTL 必须一致 —— 只给 L2 设 TTL 的话，L1 命中时会读到过期数据（这点被单测抓过）。
 */
public class TwoLevelCache<K, V> {

    private final Cache<K, V> l1;
    private final L2Store<V> l2;
    private final long ttlMillis;

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public TwoLevelCache(int maxEntries, long ttlMillis) {
        this(maxEntries, ttlMillis, System::currentTimeMillis, null);
    }

    public TwoLevelCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        this(maxEntries, ttlMillis, clock, null);
    }

    public TwoLevelCache(int maxEntries, long ttlMillis, LongSupplier clock, L2Store<V> l2) {
        this.ttlMillis = Math.max(1, ttlMillis);
        this.l2 = l2 != null ? l2 : new InMemoryL2<>(this.ttlMillis, clock);
        this.l1 = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxEntries))
                .expireAfterWrite(Duration.ofMillis(this.ttlMillis))
                // L1 的时钟也要替换，否则注入假时钟只能测到 L2（这个坑也是单测抓出来的）
                .ticker(() -> clock.getAsLong() * 1_000_000L)
                .build();
    }

    public Optional<V> get(K key) {
        String cacheKey = String.valueOf(key);
        V hot = l1.getIfPresent(key);
        if (hot != null) {
            l1Hits.increment();
            return Optional.of(hot);
        }
        Optional<V> cold = l2.get(cacheKey);
        if (cold.isEmpty()) {
            misses.increment();
            return Optional.empty();
        }
        l2Hits.increment();
        l1.put(key, cold.get());
        return cold;
    }

    public void put(K key, V value) {
        l1.put(key, value);
        l2.put(String.valueOf(key), value, ttlMillis);
    }

    public void invalidate(K key) {
        l1.invalidate(key);
        l2.remove(String.valueOf(key));
    }

    /** 只清 L1，保留 L2 —— 用于模拟进程内缓存被淘汰，以及测试两级回退。 */
    public void evictL1(K key) {
        l1.invalidate(key);
    }

    public String l2Backend() {
        return l2.backend();
    }

    public Map<String, Long> stats() {
        return Map.of(
                "l1Hits", l1Hits.sum(),
                "l2Hits", l2Hits.sum(),
                "misses", misses.sum(),
                "l2Size", l2.size()
        );
    }

    public long totalLookups() {
        return l1Hits.sum() + l2Hits.sum() + misses.sum();
    }
}
