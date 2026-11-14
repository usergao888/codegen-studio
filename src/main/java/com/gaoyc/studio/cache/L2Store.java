package com.gaoyc.studio.cache;

import java.util.Optional;

/**
 * 二级缓存的可插拔后端。
 *
 * <p>默认是进程内实现（{@link InMemoryL2}），把 {@code studio.redis.enabled} 打开后换成
 * Redis，多实例部署时缓存才能共享。抽成接口而不是直接写死 RedisHelper，是为了让
 * "没有 Redis 也能跑" 这件事在类型系统里成立，而不是靠 if 判断。
 */
public interface L2Store<V> extends AutoCloseable {

    Optional<V> get(String key);

    void put(String key, V value, long ttlMillis);

    void remove(String key);

    /** 当前后端名，会写进 /api/metrics，便于确认到底连上了没有。 */
    String backend();

    /** 元素个数；Redis 侧用 DBSIZE 近似，无法统计时返回 -1。 */
    long size();

    @Override
    default void close() {
        // 内存实现无需释放
    }
}
