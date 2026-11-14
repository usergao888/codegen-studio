package com.gaoyc.studio.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.cache.InMemoryL2;
import com.gaoyc.studio.cache.L2Store;
import com.gaoyc.studio.cache.RedisL2;
import com.gaoyc.studio.cache.TwoLevelCache;
import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.limit.MultiLevelRateLimiter;
import com.gaoyc.studio.limit.RateLimiter;
import com.gaoyc.studio.limit.RedisRateLimiter;
import com.gaoyc.studio.limit.TokenBucketRateLimiter;

@Configuration
public class StudioConfig {

    private static final Logger log = LoggerFactory.getLogger(StudioConfig.class);

    /**
     * 二级缓存后端：优先 Redis，连不上就回退进程内实现。
     *
     * <p>回退是刻意的 —— 缓存是加速手段，不是可用性前提。如果 Redis 没起就把整个服务带挂，
     * 那是把"可选依赖"错当成了"必需依赖"。
     */
    @Bean
    public L2Store<GenerationResult> generationL2Store(StudioProperties properties, ObjectMapper mapper) {
        StudioProperties.Redis redis = properties.redis();
        long ttlMillis = properties.cacheTtlSeconds() * 1000L;

        if (!redis.enabled()) {
            log.info("二级缓存使用进程内实现（studio.redis.enabled=false）");
            return new InMemoryL2<>(ttlMillis, System::currentTimeMillis);
        }
        RedisL2<GenerationResult> store = null;
        try {
            store = new RedisL2<>(redis.host(), redis.port(), redis.database(),
                    redis.keyPrefix(), mapper, GenerationResult.class);
            if (store.ping()) {
                log.info("二级缓存已接入 Redis {}:{}", redis.host(), redis.port());
                return store;
            }
        } catch (Exception e) {
            log.warn("Redis 连接失败（{}:{}）：{}", redis.host(), redis.port(), e.getMessage());
        }
        if (store != null) {
            store.close();
        }
        log.warn("已回退为进程内二级缓存；后续请求不受影响");
        return new InMemoryL2<>(ttlMillis, System::currentTimeMillis);
    }

    /** 需求文本 → 生成结果。用规范化后的需求做 key。 */
    @Bean
    public TwoLevelCache<String, GenerationResult> generationCache(
            StudioProperties properties, L2Store<GenerationResult> l2Store) {
        return new TwoLevelCache<>(
                properties.cacheMaxEntries(),
                properties.cacheTtlSeconds() * 1000L,
                System::currentTimeMillis,
                l2Store);
    }

    /** 限流后端：同样优先 Redis（多实例共享），不可用时退回进程内令牌桶。 */
    @Bean
    public RateLimiter rateLimiterBackend(StudioProperties properties) {
        StudioProperties.Redis redis = properties.redis();
        StudioProperties.RateLimit rateLimit = properties.rateLimit();
        if (redis.enabled()) {
            RedisRateLimiter redisLimiter = null;
            try {
                redisLimiter = new RedisRateLimiter(redis.host(), redis.port(), redis.database());
                if (redisLimiter.ping()) {
                    log.info("限流后端已接入 Redis（多实例共享额度）");
                    return redisLimiter;
                }
            } catch (Exception e) {
                log.warn("Redis 限流器不可用（{}:{}）：{}", redis.host(), redis.port(), e.getMessage());
            }
            if (redisLimiter != null) {
                redisLimiter.close();
            }
            log.warn("限流已回退为进程内令牌桶；多实例部署时额度不共享");
        }
        return new TokenBucketRateLimiter(rateLimit.permitsPerSecond(), rateLimit.burst());
    }

    @Bean
    public MultiLevelRateLimiter multiLevelRateLimiter(
            StudioProperties properties, RateLimiter rateLimiterBackend) {
        Map<String, StudioProperties.RateLimit.Level> configured = properties.rateLimit().levels();
        List<MultiLevelRateLimiter.Level> levels = configured.entrySet().stream()
                .map(entry -> new MultiLevelRateLimiter.Level(
                        entry.getKey(),
                        entry.getValue().permitsPerSecond(),
                        entry.getValue().burst()))
                .toList();
        return new MultiLevelRateLimiter(rateLimiterBackend, levels);
    }
}
