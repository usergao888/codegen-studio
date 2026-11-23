package com.gaoyc.studio.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gaoyc.studio.auth.TenantContext;
import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.metrics.StudioMetrics;
import com.gaoyc.studio.repository.RunRepository;
import com.gaoyc.studio.service.ApprovalService;
import com.gaoyc.studio.service.GenerationService;

/** 生成接口：一个同步 JSON，一个 SSE 流式。 */
@RestController
@RequestMapping("/api")
public class StudioController {

    private final GenerationService service;
    private final StudioMetrics metrics;
    private final RunRepository runRepository;
    private final ApprovalService approvalService;

    public StudioController(GenerationService service, StudioMetrics metrics,
                            RunRepository runRepository,
                            ApprovalService approvalService) {
        this.service = service;
        this.metrics = metrics;
        this.runRepository = runRepository;
        this.approvalService = approvalService;
    }

    public record GenerateRequest(String prompt, String userId) {
    }

    @PostMapping(path = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@RequestBody(required = false) GenerateRequest request,
                                      @RequestHeader(value = "X-User-Id", required = false) String headerUser,
                                      jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        try {
            GenerationResult result = service.generate(request.prompt(), resolveUser(request, headerUser),
                    clientIp(httpRequest), TenantContext.tenant(), event -> {
                    });
            return ResponseEntity.ok(result);
        } catch (GenerationService.InvalidPromptException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (GenerationService.RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(Math.max(1, e.retryAfterMillis() / 1000)))
                    .body(Map.of("error", e.getMessage(),
                            "retryAfterMs", e.retryAfterMillis()));
        }
    }

    @GetMapping(path = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("prompt") String prompt,
                             @RequestParam(value = "userId", required = false) String userId,
                             @RequestHeader(value = "X-User-Id", required = false) String headerUser,
                             jakarta.servlet.http.HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String user = userId != null && !userId.isBlank() ? userId : (headerUser == null ? "anonymous" : headerUser);
        String ip = clientIp(httpRequest);

        CompletableFuture.runAsync(() -> {
            try {
                GenerationResult result = service.generate(prompt, user, ip,
                        TenantContext.tenant(), event -> {
                    try {
                        emitter.send(SseEmitter.event().name("node").data(event));
                    } catch (IOException ignored) {
                        // 客户端断开就静默停止推送
                    }
                });
                emitter.send(SseEmitter.event().name("result").data(result));
                emitter.complete();
            } catch (GenerationService.RateLimitExceededException e) {
                sendError(emitter, "rate_limited", e.getMessage(), e.retryAfterMillis());
                emitter.complete();
            } catch (Exception e) {
                sendError(emitter, "failed", String.valueOf(e.getMessage()), 0);
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 最近的生成历史，按时间倒序。 */
    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return Map.of(
                "count", runRepository.count(),
                "items", runRepository.recent(safe));
    }

    /** 单个 run 的详情；产物本身在 /preview/{slug}/ 下。 */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@org.springframework.web.bind.annotation.PathVariable("runId") String runId) {
        return runRepository.find(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 待人工确认的产物列表。 */
    @GetMapping("/approvals")
    public Map<String, Object> approvals() {
        return Map.of("pending", approvalService.size(), "items", approvalService.list());
    }

    /** 人工确认通过：落盘并返回预览地址。 */
    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<?> approve(@org.springframework.web.bind.annotation.PathVariable("runId") String runId) {
        return approvalService.approve(runId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "没有待确认的 runId：" + runId)));
    }

    /** 人工拒绝：产物丢弃，不写盘。 */
    @PostMapping("/runs/{runId}/reject")
    public ResponseEntity<?> reject(@org.springframework.web.bind.annotation.PathVariable("runId") String runId) {
        boolean ok = approvalService.reject(runId);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "没有待确认的 runId：" + runId));
        }
        return ResponseEntity.ok(Map.of("runId", runId, "status", "rejected"));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "codegen-studio");
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "generation", metrics.snapshot(),
                "cache", service.cache().stats(),
                "cacheBackend", service.cache().l2Backend(),
                "rateLimiterBackend", service.rateLimiter().backend(),
                "rateLimitLevels", service.rateLimiter().levels().stream()
                        .map(l -> l.scope() + ":" + l.permitsPerSecond() + "/s").toList());
    }

    private void sendError(SseEmitter emitter, String code, String message, long retryAfter) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("code", code, "message", message, "retryAfterMs", retryAfter)));
        } catch (IOException ignored) {
            // 连接已断，无需处理
        }
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveUser(GenerateRequest request, String headerUser) {
        if (request.userId() != null && !request.userId().isBlank()) {
            return request.userId();
        }
        return headerUser == null || headerUser.isBlank() ? "anonymous" : headerUser;
    }
}
