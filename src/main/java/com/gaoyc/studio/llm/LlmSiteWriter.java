package com.gaoyc.studio.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.config.LlmProperties;
import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.gen.SiteWriter;
import com.gaoyc.studio.gen.TemplateSiteWriter;

/**
 * 用真实大模型生成整套前端文件。
 *
 * <p>三条原则：
 * <ol>
 *   <li><b>输出必须是可校验的结构</b>：让模型只返回 {@code {"files":[{"path","content"}]}}，
 *       而不是自由文本 —— 否则解析就是赌博；</li>
 *   <li><b>路径要消毒</b>：模型可能给出 {@code ../../etc/passwd} 这种路径，写盘前必须挡掉；</li>
 *   <li><b>任何异常都回退模板</b>：模型超时、限流、返回不合法 JSON，用户仍要拿到能用的页面。</li>
 * </ol>
 */
@Component
@Primary
public class LlmSiteWriter implements SiteWriter {

    private static final Logger log = LoggerFactory.getLogger(LlmSiteWriter.class);

    private static final Pattern FENCED = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9._/-]{1,120}$");
    private static final Pattern FENCE = Pattern.compile("^```.*$", Pattern.MULTILINE);

    private static final String SYSTEM_PROMPT = """
            你是资深前端工程师。根据需求生成一个**可直接双击打开**的静态页面。
            只输出 JSON，不要任何解释，格式：
            {"files":[{"path":"index.html","content":"..."},{"path":"styles.css","content":"..."},{"path":"app.js","content":"..."}]}
            硬性要求：
            1. 必须包含 index.html；样式与脚本可拆成 styles.css / app.js；
            2. 禁止引用任何外部 CDN 或 http(s) 资源 —— 断网也必须能打开；
            3. 结构语义化，表单控件配 label，交互元素给 aria 说明；
            4. 脚本用原生 JS（ES2017 即可），不要引入框架；
            5. 中文界面，文案具体，不要占位符。
            """;

    private final LlmProperties properties;
    private final TemplateSiteWriter fallback;
    private final ObjectMapper mapper;
    private final OpenAiCompatClient client;

    public LlmSiteWriter(LlmProperties properties, TemplateSiteWriter fallback, ObjectMapper mapper) {
        this.properties = properties;
        this.fallback = fallback;
        this.mapper = mapper;
        this.client = new OpenAiCompatClient(
                properties.baseUrl(), properties.apiKey(), properties.model(),
                Duration.ofSeconds(properties.timeoutSeconds()), mapper);
    }

    @Override
    public SiteDraft write(AppSpec spec, String prompt) {
        if (!properties.usable()) {
            return fallback.write(spec, prompt);
        }
        try {
            String raw = client.chat(SYSTEM_PROMPT, userPrompt(spec, prompt));
            SiteDraft draft = parse(raw);
            if (draft == null) {
                log.warn("模型输出无法解析成文件树，回退模板生成");
                return fallback.write(spec, prompt);
            }
            return draft;
        } catch (IOException e) {
            log.warn("调用模型失败（{}），回退模板生成", e.getMessage());
            return fallback.write(spec, prompt);
        }
    }

    @Override
    public String backend() {
        return properties.usable() ? "llm:" + properties.model() : "template";
    }

    private String userPrompt(AppSpec spec, String prompt) {
        return """
                需求：%s
                解析结果：类型=%s，主色=%s，移动优先=%s，特性=%s
                请据此生成完整页面。""".formatted(
                prompt, spec.archetype(), spec.accent(), spec.mobileFirst(), spec.features());
    }

    /** 包一层便于单测直接验证解析逻辑，不必每次都起 HTTP。 */
    SiteDraft parse(String raw) {
        String text = extractJson(raw);
        if (text == null) {
            return null;
        }
        JsonNode files;
        try {
            files = mapper.readTree(text).path("files");
        } catch (Exception e) {
            return null;
        }
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        int total = 0;
        List<String> rejected = new ArrayList<>();
        for (JsonNode node : files) {
            String path = node.path("path").asText("");
            String content = node.path("content").asText("");
            if (!isSafePath(path)) {
                rejected.add(path);
                continue;
            }
            total += content.length();
            if (total > properties.maxOutputChars()) {
                log.warn("模型输出超过上限 {} 字符，丢弃后续文件", properties.maxOutputChars());
                break;
            }
            out.put(path, stripWrappingFence(content));
        }
        if (!rejected.isEmpty()) {
            log.warn("模型给出了非法路径，已丢弃：{}", rejected);
        }
        if (!out.containsKey("index.html")) {
            return null;
        }
        return SiteDraft.of(out);
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        // 先用整段解析：文件内容里本身可能带 ``` 代码块，
        // 一上来就按围栏抓会抓到内容而不是外层 JSON（这个顺序被单测纠正过）
        String trimmed = raw.strip();
        if (looksLikeFileTree(trimmed)) {
            return trimmed;
        }
        Matcher fenced = FENCED.matcher(raw);
        if (fenced.find() && looksLikeFileTree(fenced.group(1).strip())) {
            return fenced.group(1).strip();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String candidate = raw.substring(start, end + 1).trim();
        return looksLikeFileTree(candidate) ? candidate : null;
    }

    private boolean looksLikeFileTree(String candidate) {
        if (!candidate.startsWith("{")) {
            return false;
        }
        try {
            return mapper.readTree(candidate).path("files").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSafePath(String path) {
        return SAFE_PATH.matcher(path).matches()
                && !path.contains("..")
                && !path.startsWith("/")
                && !path.startsWith("\\");
    }

    /** 模型有时会把文件内容再包一层 ```html，去掉以免渲染出字面量。 */
    private String stripWrappingFence(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            String body = FENCE.matcher(trimmed).replaceFirst("").stripTrailing();
            if (body.endsWith("```")) {
                body = body.substring(0, body.length() - 3).stripTrailing();
            }
            return body;
        }
        return content;
    }
}
