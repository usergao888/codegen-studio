package com.gaoyc.studio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.config.StudioProperties;
import com.gaoyc.studio.domain.GenerationResult;
import com.gaoyc.studio.domain.RunRecord;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.publish.SitePublisher;
import com.gaoyc.studio.repository.JsonFileRunRepository;

public class ApprovalServiceTest {

    private static StudioProperties properties(Path workspace) {
        return new StudioProperties(workspace.toString(), 1800, 16, 2,
                StudioProperties.RateLimit.standard(5, 10),
                new StudioProperties.Models("small", "strong", 120),
                new StudioProperties.Redis(false, "127.0.0.1", 6379, 0, "t:"),
                true);
    }

    private static GenerationResult pending(String runId, String slug) {
        return new GenerationResult(runId, slug, "", Map.of("index.html", "<html></html>"),
                List.of(), List.of(), 0, "small", "small", 0.001, false, 42,
                List.of("parse", "generate"), GenerationResult.STATUS_PENDING);
    }

    @Test
    void stageKeepsArtifactsOffDisk() throws Exception {
        Path dir = Files.createTempDirectory("studio-approval-");
        StudioProperties props = properties(dir.resolve("sites"));
        JsonFileRunRepository runs = new JsonFileRunRepository(
                dir.resolve("runs.jsonl").toString(), 10, new ObjectMapper());
        ApprovalService service = new ApprovalService(new SitePublisher(props), runs);

        service.stage(pending("r1", "todo-r1"), "做一个待办", SiteDraft.of(Map.of("index.html", "<html></html>")));

        assertEquals(1, service.size());
        assertFalse(Files.exists(dir.resolve("sites").resolve("todo-r1").resolve("index.html")),
                "未确认前不应落盘");
        assertEquals("pending", runs.find("r1").orElseThrow().effectiveStatus());
    }

    @Test
    void approvePublishesAndMarksPublished() throws Exception {
        Path dir = Files.createTempDirectory("studio-approval-");
        StudioProperties props = properties(dir.resolve("sites"));
        JsonFileRunRepository runs = new JsonFileRunRepository(
                dir.resolve("runs.jsonl").toString(), 10, new ObjectMapper());
        ApprovalService service = new ApprovalService(new SitePublisher(props), runs);

        service.stage(pending("r2", "todo-r2"), "做一个待办", SiteDraft.of(Map.of("index.html", "<html></html>")));
        Optional<GenerationResult> published = service.approve("r2");

        assertTrue(published.isPresent());
        assertEquals(GenerationResult.STATUS_PUBLISHED, published.orElseThrow().status());
        assertEquals("/preview/todo-r2/index.html", published.orElseThrow().previewUrl());
        assertTrue(Files.exists(dir.resolve("sites").resolve("todo-r2").resolve("index.html")),
                "确认后才落盘");
        assertEquals("published", runs.find("r2").orElseThrow().effectiveStatus());
        assertEquals(0, service.size());
    }

    @Test
    void rejectDiscardsWithoutWritingFiles() throws Exception {
        Path dir = Files.createTempDirectory("studio-approval-");
        StudioProperties props = properties(dir.resolve("sites"));
        JsonFileRunRepository runs = new JsonFileRunRepository(
                dir.resolve("runs.jsonl").toString(), 10, new ObjectMapper());
        ApprovalService service = new ApprovalService(new SitePublisher(props), runs);

        service.stage(pending("r3", "todo-r3"), "做一个待办", SiteDraft.of(Map.of("index.html", "<html></html>")));
        assertTrue(service.reject("r3"));

        assertFalse(Files.exists(dir.resolve("sites").resolve("todo-r3")));
        assertEquals("rejected", runs.find("r3").orElseThrow().effectiveStatus());
        assertFalse(service.approve("r3").isPresent(), "拒绝后不能再被确认");
    }

    @Test
    void unknownRunIdIsRejected() throws Exception {
        Path dir = Files.createTempDirectory("studio-approval-");
        StudioProperties props = properties(dir.resolve("sites"));
        JsonFileRunRepository runs = new JsonFileRunRepository(
                dir.resolve("runs.jsonl").toString(), 10, new ObjectMapper());
        ApprovalService service = new ApprovalService(new SitePublisher(props), runs);

        assertFalse(service.approve("nope").isPresent());
        assertFalse(service.reject("nope"));
    }

    @Test
    void historyReplaceKeepsOnlyLatestStatus() throws Exception {
        Path dir = Files.createTempDirectory("studio-approval-");
        JsonFileRunRepository runs = new JsonFileRunRepository(
                dir.resolve("runs.jsonl").toString(), 10, new ObjectMapper());
        RunRecord first = RunRecord.of(pending("r9", "todo-r9"), "需求", "pending");
        runs.save(first);
        runs.save(RunRecord.of(pending("r9", "todo-r9"), "需求", "published"));

        assertEquals(1, runs.count(), "同 runId 只应记一条");
        assertEquals("published", runs.find("r9").orElseThrow().effectiveStatus());
    }
}
