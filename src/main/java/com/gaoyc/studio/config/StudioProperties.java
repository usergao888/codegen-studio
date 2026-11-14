package com.gaoyc.studio.config;

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
        Redis redis
) {

    public StudioProperties {
        workspace = workspace == null ? "./var/sites" : workspace;
        cacheTtlSeconds = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cacheMaxEntries = cacheMaxEntries <= 0 ? 256 : cacheMaxEntries;
        maxRepairRounds = maxRepairRounds < 0 ? 2 : maxRepairRounds;
        rateLimit = rateLimit == null ? new RateLimit(5, 10) : rateLimit;
        models = models == null ? new Models("deepseek-chat", "deepseek-reasoner", 120) : models;
        redis = redis == null ? new Redis(false, "127.0.0.1", 6379, 0, "studio:gen:") : redis;
    }

    public record RateLimit(int permitsPerSecond, int burst) {
    }

    public record Models(String small, String strong, int complexPromptChars) {
    }

    /** 二级缓存后端。enabled=false 时用进程内实现，打开则连 Redis（连不上自动回退）。 */
    public record Redis(boolean enabled, String host, int port, int database, String keyPrefix) {
    }
}
