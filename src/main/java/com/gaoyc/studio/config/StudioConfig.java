package com.gaoyc.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gaoyc.studio.cache.TwoLevelCache;
import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.limit.TokenBucketRateLimiter;

@Configuration
public class StudioConfig {

    /** 需求文本 → 生成结果。用规范化后的需求做 key。 */
    @Bean
    public TwoLevelCache<String, GenerationResult> generationCache(StudioProperties properties) {
        return new TwoLevelCache<>(
                properties.cacheMaxEntries(),
                properties.cacheTtlSeconds() * 1000L);
    }

    @Bean
    public TokenBucketRateLimiter rateLimiter(StudioProperties properties) {
        return new TokenBucketRateLimiter(
                properties.rateLimit().permitsPerSecond(),
                properties.rateLimit().burst());
    }
}
