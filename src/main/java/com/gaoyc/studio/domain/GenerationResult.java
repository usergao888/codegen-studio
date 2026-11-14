package com.gaoyc.studio.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次完整生成的结果，直接作为 API 响应体。
 *
 * <p>{@code status} 有三种取值：
 * <ul>
 *   <li>{@code published} —— 已落盘，previewUrl 可直接打开</li>
 *   <li>{@code pending} —— 开启人工确认时停在发布前，尚未写盘</li>
 *   <li>{@code rejected} —— 人工拒绝，产物丢弃</li>
 * </ul>
 */
public record GenerationResult(
        String runId,
        String slug,
        String previewUrl,
        Map<String, String> files,
        List<QaIssue> initialIssues,
        List<QaIssue> remainingIssues,
        int repairRounds,
        String model,
        String tier,
        double costCny,
        boolean cached,
        long durationMs,
        List<String> stages,
        String status
) {
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REJECTED = "rejected";

    public boolean passed() {
        return remainingIssues.stream().noneMatch(QaIssue::isError);
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    /** 缓存命中时返回同一份结果，只把 cached 标记翻过来。 */
    public GenerationResult asCached() {
        return new GenerationResult(runId, slug, previewUrl, files, initialIssues,
                remainingIssues, repairRounds, model, tier, costCny, true, durationMs, stages, status);
    }

    public GenerationResult withStatus(String newStatus) {
        return new GenerationResult(runId, slug, previewUrl, files, initialIssues,
                remainingIssues, repairRounds, model, tier, costCny, cached, durationMs, stages, newStatus);
    }

    public GenerationResult withPreviewUrl(String newUrl) {
        return new GenerationResult(runId, slug, newUrl, files, initialIssues,
                remainingIssues, repairRounds, model, tier, costCny, cached, durationMs, stages, status);
    }
}
