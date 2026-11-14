package com.gaoyc.studio.limit;

import java.util.ArrayList;
import java.util.List;

/**
 * 多级限流：同一请求要同时通过用户、IP、接口三道闸。
 *
 * <p>为什么需要多级：只按用户限流，换个账号就能刷；只按 IP 限流，同一出口的同事会互相拖累；
 * 只按接口限流，一个用户就能把整个接口的额度吃满。三者的额度数量级也不同，
 * 所以每级独立算桶，任意一级拒绝就整体拒绝。
 */
public class MultiLevelRateLimiter {

    /** 一级策略：scope 名 + 从请求里取哪个值 + 该级的速率。 */
    public record Level(String scope, int permitsPerSecond, int burst) {
    }

    public record Decision(boolean allowed, long retryAfterMillis, String rejectedScope, String backend) {
    }

    private final RateLimiter backend;
    private final List<Level> levels;

    public MultiLevelRateLimiter(RateLimiter backend, List<Level> levels) {
        this.backend = backend;
        this.levels = List.copyOf(levels);
    }

    /**
     * @param subject 每个 scope 对应的取值，例如 {user=u1, ip=127.0.0.1, endpoint=generate}
     */
    public Decision tryAcquire(java.util.Map<String, String> subject) {
        List<String> applied = new ArrayList<>();
        for (Level level : levels) {
            String value = subject.get(level.scope());
            if (value == null || value.isBlank()) {
                continue;
            }
            String key = "studio:rl:" + level.scope() + ":" + value;
            RateLimiter.Decision decision =
                    backend.tryAcquire(key, 1, level.permitsPerSecond(), level.burst());
            if (!decision.allowed()) {
                return new Decision(false, decision.retryAfterMillis(), level.scope(), backend.backend());
            }
            applied.add(level.scope());
        }
        return new Decision(true, 0, "none", backend.backend());
    }

    public List<Level> levels() {
        return levels;
    }

    public String backend() {
        return backend.backend();
    }
}
