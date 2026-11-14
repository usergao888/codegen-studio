package com.gaoyc.studio.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.domain.RunRecord;

/**
 * 追加写 JSONL + 启动时回放到内存索引。
 *
 * <p>为什么不用数据库：本地仓库里没有 H2/HSQLDB/SQLite 的 jar，而为了几十条历史记录
 * 引一整套 ORM 也不划算。JSONL 的好处是按行追加天然容忍半行损坏 —— 进程被杀时
 * 最多丢最后一行，不会整份文件读不出来。
 *
 * <p>内存里只保留最近 {@code maxInMemory} 条，避免服务跑久了堆内存被历史撑爆；
 * 更早的记录仍留在文件里，需要时手工翻。
 */
@Component
public class JsonFileRunRepository implements RunRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileRunRepository.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final int maxInMemory;
    private final Deque<RunRecord> recent = new ArrayDeque<>();
    private final Map<String, RunRecord> byId = new LinkedHashMap<>();
    private long totalSeen;

    public JsonFileRunRepository(
            @Value("${studio.runs-file:./var/runs/runs.jsonl}") String path,
            @Value("${studio.runs-in-memory:200}") int maxInMemory,
            ObjectMapper mapper) {
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.maxInMemory = Math.max(1, maxInMemory);
        replay();
    }

    private void replay() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    RunRecord record = mapper.readValue(line, RunRecord.class);
                    remember(record);
                    totalSeen++;
                } catch (Exception e) {
                    // 单行坏掉不该影响整体回放
                    log.warn("跳过一条无法解析的历史记录：{}", e.getMessage());
                }
            }
            log.info("已加载 {} 条生成历史（{}）", totalSeen, file);
        } catch (IOException e) {
            log.warn("读取历史文件失败：{}", e.getMessage());
        }
    }

    private void remember(RunRecord record) {
        byId.put(record.runId(), record);
        recent.addFirst(record);
        while (recent.size() > maxInMemory) {
            RunRecord evicted = recent.removeLast();
            byId.remove(evicted.runId());
        }
    }

    @Override
    public synchronized void save(RunRecord record) {
        remember(record);
        totalSeen++;
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 历史写不进去不能影响生成主链路
            log.warn("写入历史文件失败：{}", e.getMessage());
        }
    }

    @Override
    public synchronized Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(byId.get(runId));
    }

    @Override
    public synchronized List<RunRecord> recent(int limit) {
        int size = Math.max(0, Math.min(limit, recent.size()));
        List<RunRecord> out = new ArrayList<>(size);
        int index = 0;
        for (RunRecord record : recent) {
            if (index++ >= size) {
                break;
            }
            out.add(record);
        }
        return out;
    }

    @Override
    public synchronized long count() {
        return totalSeen;
    }

    public Path file() {
        return file;
    }
}
