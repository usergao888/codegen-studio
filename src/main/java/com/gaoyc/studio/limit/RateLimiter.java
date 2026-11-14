package com.gaoyc.studio.limit;

/**
 * 限流后端。
 *
 * <p>参数按调用传入（速率、桶容量），而不是构造时写死 —— 因为多级限流里
 * "用户 5/s、IP 20/s、接口 50/s" 是同一个后端的不同策略，不该开三个实例。
 */
public interface RateLimiter {

    record Decision(boolean allowed, long retryAfterMillis) {
        public static Decision allow() {
            return new Decision(true, 0);
        }

        public static Decision deny(long retryAfterMillis) {
            return new Decision(false, Math.max(1, retryAfterMillis));
        }
    }

    Decision tryAcquire(String key, int permits, double permitsPerSecond, int burst);

    /** 后端名，写进 /api/metrics，便于确认到底用的是内存还是 Redis。 */
    String backend();
}
