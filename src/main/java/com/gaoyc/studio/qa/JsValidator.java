package com.gaoyc.studio.qa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;

/**
 * 用 Node 真正解析一次生成的脚本：{@code node --check file.js}。
 *
 * <p>这是整个质检里唯一"外部权威"的判定 —— 正则永远无法确认 JS 语法对不对，
 * 而 V8 可以。机器上没有 node 时降级为 WARN，不阻塞发布。
 */
@Component
public class JsValidator {

    private static final long TIMEOUT_SECONDS = 15;

    public List<QaIssue> validate(String js, String fileName) {
        List<QaIssue> issues = new ArrayList<>();
        Path temp = null;
        try {
            temp = Files.createTempFile("studio-check-", ".js");
            Files.writeString(temp, js, StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder(nodeExecutable(), "--check", temp.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                issues.add(QaIssue.warn("JS_CHECK_TIMEOUT", fileName, "node --check 超时，跳过语法校验"));
                return issues;
            }
            if (process.exitValue() != 0) {
                issues.add(QaIssue.error("JS_SYNTAX", fileName, firstLine(output)));
            }
        } catch (IOException e) {
            issues.add(QaIssue.warn("JS_CHECK_SKIPPED", fileName,
                    "未找到 node，跳过 JS 语法校验：" + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            issues.add(QaIssue.warn("JS_CHECK_SKIPPED", fileName, "JS 校验被中断"));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
        return issues;
    }

    private String nodeExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "node.exe" : "node";
    }

    private String firstLine(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SyntaxError") || trimmed.contains("SyntaxError")) {
                return trimmed;
            }
        }
        String[] lines = output.strip().split("\\R");
        return lines.length == 0 ? "JS 语法错误" : lines[0];
    }
}
