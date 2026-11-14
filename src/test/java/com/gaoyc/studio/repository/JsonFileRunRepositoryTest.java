package com.gaoyc.studio.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.domain.RunRecord;

public class JsonFileRunRepositoryTest {

    private static RunRecord record(String id, String prompt) {
        return new RunRecord(id, prompt, "todo-" + id, "/preview/todo-" + id + "/index.html",
                true, 1, "deepseek-chat", "small", 0.0012, 120, false,
                List.of("HTML_MISSING_VIEWPORT"), 4, "2026-11-14T00:00:00Z");
    }

    private static Path tempFile() throws Exception {
        Path dir = Files.createTempDirectory("studio-runs-");
        return dir.resolve("runs.jsonl");
    }

    @Test
    void savesAndFindsById() throws Exception {
        JsonFileRunRepository repo = new JsonFileRunRepository(tempFile().toString(), 10, new ObjectMapper());
        repo.save(record("aaa", "做一个待办"));
        assertTrue(repo.find("aaa").isPresent());
        assertEquals("做一个待办", repo.find("aaa").orElseThrow().prompt());
        assertEquals(1, repo.count());
    }

    @Test
    void recentIsNewestFirst() throws Exception {
        JsonFileRunRepository repo = new JsonFileRunRepository(tempFile().toString(), 10, new ObjectMapper());
        repo.save(record("aaa", "第一"));
        repo.save(record("bbb", "第二"));
        List<RunRecord> items = repo.recent(5);
        assertEquals(2, items.size());
        assertEquals("bbb", items.get(0).runId());
    }

    @Test
    void survivesRestartBecauseItReplaysTheFile() throws Exception {
        Path file = tempFile();
        JsonFileRunRepository first = new JsonFileRunRepository(file.toString(), 10, new ObjectMapper());
        first.save(record("aaa", "先跑一次"));

        // 新实例模拟进程重启：只能靠文件回放
        JsonFileRunRepository second = new JsonFileRunRepository(file.toString(), 10, new ObjectMapper());
        assertEquals(1, second.count());
        assertTrue(second.find("aaa").isPresent());
    }

    @Test
    void inMemoryIndexIsBoundedButFileKeepsEverything() throws Exception {
        Path file = tempFile();
        JsonFileRunRepository repo = new JsonFileRunRepository(file.toString(), 2, new ObjectMapper());
        for (int i = 0; i < 5; i++) {
            repo.save(record("id" + i, "第 " + i + " 条"));
        }
        assertEquals(5, repo.count());
        assertEquals(2, repo.recent(10).size(), "内存索引只保留最近 2 条");
        assertEquals(5, Files.readAllLines(file, StandardCharsets.UTF_8).size(), "文件里仍是 5 行");
    }

    @Test
    void corruptLineIsSkippedInsteadOfFailingTheWholeReplay() throws Exception {
        Path file = tempFile();
        JsonFileRunRepository first = new JsonFileRunRepository(file.toString(), 10, new ObjectMapper());
        first.save(record("aaa", "好的一行"));
        Files.writeString(file, "{ 这行坏了" + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        first.save(record("bbb", "又一行好的"));

        JsonFileRunRepository second = new JsonFileRunRepository(file.toString(), 10, new ObjectMapper());
        assertEquals(2, second.count(), "坏行被跳过，两条好记录都还在");
    }
}
