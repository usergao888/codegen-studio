package com.gaoyc.studio.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.domain.RunRecord;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.publish.SitePublisher;
import com.gaoyc.studio.repository.RunRepository;

/**
 * 发布前的人工确认（human-in-the-loop）。
 *
 * <p>开启后工作流跑到"质检通过"就停住，产物留在内存里不落盘；等人点了 approve 才写盘并
 * 给出预览地址。为什么值得单独一层：生成代码会带内联脚本，直接发布等于把一个可能带 XSS
 * 的页面挂到公司域名下 —— 机器质检能拦住已知模式，但"这个页面到底能不能给人看"是人的判断。
 *
 * <p>代价是待确认的产物存在内存里，进程重启就丢（历史记录还在，会显示 pending）。
 * 真要上生产，这一层该换成"产物先写临时目录 + 状态落库"。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    public record Pending(
            String runId,
            String slug,
            String prompt,
            int fileCount,
            String createdAt,
            GenerationResult result,
            SiteDraft draft
    ) {
        public Map<String, Object> summary() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId);
            map.put("slug", slug);
            map.put("prompt", prompt);
            map.put("fileCount", fileCount);
            map.put("createdAt", createdAt);
            map.put("issueCodes", result.initialIssues().stream()
                    .map(issue -> issue.code()).distinct().toList());
            return map;
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SitePublisher publisher;
    private final RunRepository runs;

    public ApprovalService(SitePublisher publisher, RunRepository runs) {
        this.publisher = publisher;
        this.runs = runs;
    }

    public void stage(GenerationResult result, String prompt, SiteDraft draft) {
        Pending entry = new Pending(result.runId(), result.slug(), prompt,
                draft == null ? 0 : draft.files().size(),
                Instant.now().toString(), result, draft);
        pending.put(result.runId(), entry);
        runs.save(RunRecord.of(result, prompt, GenerationResult.STATUS_PENDING));
        log.info("已暂存待确认产物：runId={} slug={}", result.runId(), result.slug());
    }

    /** 人工确认后落盘并返回带预览地址的结果。 */
    public Optional<GenerationResult> approve(String runId) {
        Pending entry = pending.remove(runId);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            String previewUrl = publisher.publish(entry.slug(), entry.draft());
            GenerationResult published = entry.result()
                    .withPreviewUrl(previewUrl)
                    .withStatus(GenerationResult.STATUS_PUBLISHED);
            runs.save(RunRecord.of(published, entry.prompt(), GenerationResult.STATUS_PUBLISHED));
            log.info("人工确认通过并已发布：runId={} url={}", runId, previewUrl);
            return Optional.of(published);
        } catch (IOException e) {
            log.warn("发布失败：{}", e.getMessage());
            // 放回去，允许重试
            pending.put(runId, entry);
            return Optional.empty();
        }
    }

    /** 人工拒绝：产物直接丢弃，不写盘。 */
    public boolean reject(String runId) {
        Pending entry = pending.remove(runId);
        if (entry == null) {
            return false;
        }
        runs.save(RunRecord.of(entry.result().withStatus(GenerationResult.STATUS_REJECTED),
                entry.prompt(), GenerationResult.STATUS_REJECTED));
        log.info("人工拒绝：runId={} slug={}", runId, entry.slug());
        return true;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        pending.values().forEach(entry -> out.add(entry.summary()));
        return out;
    }

    public int size() {
        return pending.size();
    }
}
