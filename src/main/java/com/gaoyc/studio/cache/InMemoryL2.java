package com.gaoyc.studio.cache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** 进程内二级缓存：带 TTL 的 Map，单机默认用它。 */
public class InMemoryL2<V> implements L2Store<V> {

    private record Entry<V>(V value, long expiresAt) {
    }

    private final Map<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LongAdder evictions = new LongAdder();

    public InMemoryL2(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = Math.max(1, ttlMillis);
        this.clock = clock;
    }

    @Override
    public Optional<V> get(String key) {
        Entry<V> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() < clock.getAsLong()) {
            store.remove(key);
            evictions.increment();
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, V value, long ttlMillis) {
        long effective = ttlMillis > 0 ? ttlMillis : this.ttlMillis;
        store.put(key, new Entry<>(value, clock.getAsLong() + effective));
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public long size() {
        return store.size();
    }

    public long evictions() {
        return evictions.sum();
    }
}
