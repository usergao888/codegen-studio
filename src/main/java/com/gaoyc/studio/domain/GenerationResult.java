package com.gaoyc.studio.domain;

import java.util.List;
import java.util.Map;

/** 一次完整生成的结果，直接作为 API 响应体。 */
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
        List<String> stages
) {
    public boolean passed() {
        return remainingIssues.stream().noneMatch(QaIssue::isError);
    }

    /** 缓存命中时返回同一份结果，只把 cached 标记翻过来。 */
    public GenerationResult asCached() {
        return new GenerationResult(runId, slug, previewUrl, files, initialIssues,
                remainingIssues, repairRounds, model, tier, costCny, true, durationMs, stages);
    }
}
