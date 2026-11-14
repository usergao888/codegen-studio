package com.gaoyc.studio.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次生成的历史记录（轻量版）。
 *
 * <p>刻意不算整份产物：文件内容已经落在 var/sites/{slug}/ 下，历史里再存一份
 * 既浪费又容易不一致。这里只记"能用来复盘和检索"的字段。
 */
public record RunRecord(
        String runId,
        String prompt,
        String slug,
        String previewUrl,
        boolean passed,
        int repairRounds,
        String model,
        String tier,
        double costCny,
        long durationMs,
        boolean cached,
        List<String> issueCodes,
        int fileCount,
        String createdAt
) {
    public static RunRecord of(GenerationResult result, String prompt) {
        return new RunRecord(
                result.runId(),
                prompt,
                result.slug(),
                result.previewUrl(),
                result.passed(),
                result.repairRounds(),
                result.model(),
                result.tier(),
                result.costCny(),
                result.durationMs(),
                result.cached(),
                result.initialIssues().stream().map(QaIssue::code).distinct().toList(),
                result.files().size(),
                Instant.now().toString());
    }
}
