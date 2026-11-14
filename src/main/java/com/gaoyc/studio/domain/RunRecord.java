package com.gaoyc.studio.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次生成的历史记录（轻量版）。
 *
 * <p>刻意不算整份产物：文件内容已经落在 var/sites/{slug}/ 下，历史里再存一份
 * 既浪费又容易不一致。这里只记"能用来复盘和检索"的字段。
 *
 * <p>状态变更（发布 / 拒绝）用"追加一条同 runId 的记录"表达，回放时后者覆盖前者 ——
 * 这样文件仍是 append-only，不需要原地改行。
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
        String createdAt,
        String status
) {

    /** 老历史（加 status 之前写入的）该字段缺失，反序列化后为 null —— 在构造时补齐。 */
    public RunRecord {
        if (status == null || status.isBlank()) {
            status = GenerationResult.STATUS_PUBLISHED;
        }
    }

    public static RunRecord of(GenerationResult result, String prompt) {
        return of(result, prompt, result.status());
    }

    public static RunRecord of(GenerationResult result, String prompt, String status) {
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
                Instant.now().toString(),
                status == null ? GenerationResult.STATUS_PUBLISHED : status);
    }

    public String effectiveStatus() {
        return status;
    }
}
