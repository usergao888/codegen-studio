package com.gaoyc.studio.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 里 studio.* 的绑定。 */
@ConfigurationProperties(prefix = "studio")
public record StudioProperties(
        String workspace,
        int cacheTtlSeconds,
        int cacheMaxEntries,
        int maxRepairRounds,
        RateLimit rateLimit,
        Models models,
        Redis redis,
        boolean requireApproval
) {

    public StudioProperties {
        workspace = workspace == null ? "./var/sites" : workspace;
        cacheTtlSeconds = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cacheMaxEntries = cacheMaxEntries <= 0 ? 256 : cacheMaxEntries;
        maxRepairRounds = maxRepairRounds < 0 ? 2 : maxRepairRounds;
        rateLimit = rateLimit == null ? RateLimit.standard(5, 10) : rateLimit.standardized();
        models = models == null ? new Models("deepseek-chat", "deepseek-reasoner", 120) : models;
        redis = redis == null ? new Redis(false, "127.0.0.1", 6379, 0, "studio:gen:") : redis;
    }

    /**
     * 限流配置。levels 为空时按基础速率派生出用户 / IP / 接口三级，
     * 数量级依次放大 —— 因为同一个用户刷接口的概率远高于同一出口 IP 被刷。
     */
    public record RateLimit(int permitsPerSecond, int burst, Map<String, Level> levels) {

        public record Level(int permitsPerSecond, int burst) {
        }

        public static RateLimit standard(int permitsPerSecond, int burst) {
            return new RateLimit(permitsPerSecond, burst, Map.of());
        }

        RateLimit standardized() {
            if (levels != null && !levels.isEmpty()) {
                return this;
            }
            int base = Math.max(1, permitsPerSecond <= 0 ? 5 : permitsPerSecond);
            int capacity = Math.max(1, burst <= 0 ? base * 2 : burst);
            Map<String, Level> derived = new LinkedHashMap<>();
            derived.put("user", new Level(base, capacity));
            derived.put("ip", new Level(base * 4, capacity * 4));
            derived.put("endpoint", new Level(base * 10, capacity * 10));
            return new RateLimit(base, capacity, Map.copyOf(derived));
        }
    }

    public record Models(String small, String strong, int complexPromptChars) {
    }

    /** 二级缓存后端。enabled=false 时用进程内实现，打开则连 Redis（连不上自动回退）。 */
    public record Redis(boolean enabled, String host, int port, int database, String keyPrefix) {
    }
}
