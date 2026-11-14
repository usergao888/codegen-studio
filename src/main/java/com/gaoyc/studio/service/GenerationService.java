package com.gaoyc.studio.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bsc.langgraph4j.NodeOutput;
import org.springframework.stereotype.Service;

import com.gaoyc.studio.cache.TwoLevelCache;
import com.gaoyc.studio.config.StudioProperties;
import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.domain.StudioState;
import com.gaoyc.studio.graph.GenerationGraph;
import com.gaoyc.studio.limit.TokenBucketRateLimiter;
import com.gaoyc.studio.llm.ModelRouter;
import com.gaoyc.studio.metrics.StudioMetrics;

/**
 * 把限流、缓存、工作流、指标串成一个用例。
 *
 * <p>顺序是有讲究的：**先查缓存再限流**。缓存命中只要几微秒，不该消耗用户的令牌；
 * 反过来先限流会让"反复点同一个需求"这种最常见的操作被误伤。
 */
@Service
public class GenerationService {

    /** 供 SSE 推送的节点事件。 */
    public record NodeEvent(String node, boolean last, long elapsedMs) {
    }

    public record Outcome(GenerationResult result, List<String> visited) {
    }

    private final GenerationGraph graph;
    private final TwoLevelCache<String, GenerationResult> cache;
    private final TokenBucketRateLimiter rateLimiter;
    private final ModelRouter router;
    private final StudioMetrics metrics;
    private final StudioProperties properties;

    public GenerationService(GenerationGraph graph,
                             TwoLevelCache<String, GenerationResult> cache,
                             TokenBucketRateLimiter rateLimiter,
                             ModelRouter router,
                             StudioMetrics metrics,
                             StudioProperties properties) {
        this.graph = graph;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.router = router;
        this.metrics = metrics;
        this.properties = properties;
    }

    public GenerationResult generate(String prompt, String userId) {
        return generate(prompt, userId, event -> {
        });
    }

    public GenerationResult generate(String prompt, String userId, Consumer<NodeEvent> listener) {
        String normalized = normalize(prompt);
        if (normalized.isEmpty()) {
            throw new InvalidPromptException("需求不能为空");
        }
        if (normalized.length() > 500) {
            throw new InvalidPromptException("需求过长，请控制在 500 字以内");
        }

        Optional<GenerationResult> cached = cache.get(normalized);
        if (cached.isPresent()) {
            metrics.recordCacheHit();
            listener.accept(new NodeEvent("cache", true, 0));
            return cached.get().asCached();
        }

        TokenBucketRateLimiter.Decision decision = rateLimiter.tryAcquire(userId == null ? "anonymous" : userId);
        if (!decision.allowed()) {
            metrics.recordRateLimited();
            throw new RateLimitExceededException(decision.retryAfterMillis());
        }

        long started = System.currentTimeMillis();
        StudioState finalState = null;
        List<String> visited = new ArrayList<>();
        for (NodeOutput<StudioState> output : graph.stream(normalized)) {
            finalState = output.state();
            visited.add(output.node());
            listener.accept(new NodeEvent(output.node(), false, System.currentTimeMillis() - started));
        }
        if (finalState == null) {
            metrics.recordFailure();
            throw new IllegalStateException("工作流没有产出最终状态");
        }

        long elapsed = System.currentTimeMillis() - started;
        SiteDraft draft = finalState.draft();
        double cost = router.route(normalized).estimateCost(normalized.length());

        GenerationResult result = new GenerationResult(
                UUID.randomUUID().toString().substring(0, 8),
                finalState.slug(),
                finalState.previewUrl(),
                draft == null ? java.util.Map.of() : draft.files(),
                finalState.initialIssues(),
                finalState.issues(),
                finalState.rounds(),
                finalState.model(),
                finalState.tier(),
                cost,
                false,
                elapsed,
                finalState.stages());

        cache.put(normalized, result);
        metrics.recordGeneration(elapsed, cost);
        metrics.recordRepairs(finalState.rounds());
        // 记录首轮被质检拦下的缺陷码 —— 修好了不代表没发生过，这才是调参该看的信号
        metrics.recordIssueCodes(result.initialIssues().stream().map(QaIssue::code).toList());
        if (!result.passed()) {
            metrics.recordQaBlocked();
        }
        return result;
    }

    public TwoLevelCache<String, GenerationResult> cache() {
        return cache;
    }

    public StudioProperties properties() {
        return properties;
    }

    /** 缓存 key：去空白、全角转半角太复杂，这里只做折叠空白 + 小写 + 去尾部标点。 */
    static String normalize(String prompt) {
        if (prompt == null) {
            return "";
        }
        String text = prompt.strip().replaceAll("\\s+", " ").toLowerCase();
        while (!text.isEmpty() && "。.,，!！?？ ".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    public static class InvalidPromptException extends RuntimeException {
        public InvalidPromptException(String message) {
            super(message);
        }
    }

    public static class RateLimitExceededException extends RuntimeException {
        private final long retryAfterMillis;

        public RateLimitExceededException(long retryAfterMillis) {
            super("请求过于频繁，请稍后重试");
            this.retryAfterMillis = retryAfterMillis;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }
}
