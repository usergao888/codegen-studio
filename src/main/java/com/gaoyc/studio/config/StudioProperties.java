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
        Models models
) {

    public StudioProperties {
        workspace = workspace == null ? "./var/sites" : workspace;
        cacheTtlSeconds = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cacheMaxEntries = cacheMaxEntries <= 0 ? 256 : cacheMaxEntries;
        maxRepairRounds = maxRepairRounds < 0 ? 2 : maxRepairRounds;
        rateLimit = rateLimit == null ? new RateLimit(5, 10) : rateLimit;
        models = models == null ? new Models("deepseek-chat", "deepseek-reasoner", 120) : models;
    }

    public record RateLimit(int permitsPerSecond, int burst) {
    }

    public record Models(String small, String strong, int complexPromptChars) {
    }
}
