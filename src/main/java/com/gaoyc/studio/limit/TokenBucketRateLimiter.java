package com.gaoyc.studio.limit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 进程内令牌桶：按 key 独立计量，允许突发到 burst，之后按 permitsPerSecond 匀速恢复。
 *
 * <p>单机默认用它。注意它**不跨进程** —— 多实例部署时每个实例各算各的，
 * 实际放行量会变成 N 倍，所以那种场景要换 {@link RedisRateLimiter}。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final double defaultPermitsPerSecond;
    private final int defaultBurst;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public record Decision(boolean allowed, long retryAfterMillis, double remainingTokens) {
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    public TokenBucketRateLimiter(int permitsPerSecond, int burst) {
        this(permitsPerSecond, burst, System::nanoTime);
    }

    public TokenBucketRateLimiter(int permitsPerSecond, int burst, LongSupplier nanoClock) {
        this.defaultPermitsPerSecond = Math.max(0.1, permitsPerSecond);
        this.defaultBurst = Math.max(1, burst);
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String key) {
        return tryAcquireDetailed(key, 1, defaultPermitsPerSecond, defaultBurst);
    }

    public Decision tryAcquire(String key, int permits) {
        return tryAcquireDetailed(key, permits, defaultPermitsPerSecond, defaultBurst);
    }

    /** 可指定速率与桶容量，供多级限流复用同一个实例。 */
    public Decision tryAcquireDetailed(String key, int permits, double permitsPerSecond, int burst) {
        int capacity = Math.max(1, burst);
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * permitsPerSecond);
            bucket.lastRefillNanos = now;

            if (bucket.tokens >= permits) {
                bucket.tokens -= permits;
                return new Decision(true, 0, bucket.tokens);
            }
            double missing = permits - bucket.tokens;
            long retryAfter = (long) Math.ceil(missing / permitsPerSecond * 1000.0);
            return new Decision(false, Math.max(1, retryAfter), bucket.tokens);
        }
    }

    @Override
    public RateLimiter.Decision tryAcquire(String key, int permits, double permitsPerSecond, int burst) {
        Decision detailed = tryAcquireDetailed(key, permits, permitsPerSecond, burst);
        return detailed.allowed()
                ? RateLimiter.Decision.allow()
                : RateLimiter.Decision.deny(detailed.retryAfterMillis());
    }

    @Override
    public String backend() {
        return "memory";
    }

    /** 清掉长时间不活跃的桶，避免 key 无限增长。 */
    public int sweep(long idleSeconds) {
        long now = nanoClock.getAsLong();
        long threshold = idleSeconds * 1_000_000_000L;
        int removed = 0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                if (now - bucket.lastRefillNanos <= threshold) {
                    continue;
                }
                // 先按流逝时间回补再判断是否已满：不回补的话 tokens 还是"刚被借走"的旧值，
                // 空闲很久的桶永远不满足删除条件（这个 bug 是测试抓出来的）
                double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
                double refilled = Math.min(defaultBurst, bucket.tokens + elapsedSeconds * defaultPermitsPerSecond);
                if (refilled >= defaultBurst - 0.001) {
                    buckets.remove(entry.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    public int trackedKeys() {
        return buckets.size();
    }
}
