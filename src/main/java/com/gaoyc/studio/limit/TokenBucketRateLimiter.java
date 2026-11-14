package com.gaoyc.studio.limit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 令牌桶限流：按 userId 维度独立计量，允许突发到 burst，之后按 permitsPerSecond 匀速恢复。
 *
 * <p>选令牌桶而不是固定窗口计数，是因为生成一次要几秒钟，用户往往会连点几次 ——
 * 令牌桶能把"突发"和"长期速率"分开控制。真实部署换 Redisson 的 {@code RRateLimiter}，
 * tryAcquire 的语义保持一致。
 */
public class TokenBucketRateLimiter {

    private final double permitsPerSecond;
    private final int burst;
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
        this.permitsPerSecond = Math.max(0.1, permitsPerSecond);
        this.burst = Math.max(1, burst);
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public Decision tryAcquire(String key, int permits) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(burst, now));
        synchronized (bucket) {
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * permitsPerSecond);
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
                // 空闲很久的桶永远不满足删除条件（这个 bug 是测试抓出来的）。
                double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
                double refilled = Math.min(burst, bucket.tokens + elapsedSeconds * permitsPerSecond);
                if (refilled >= burst - 0.001) {
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
