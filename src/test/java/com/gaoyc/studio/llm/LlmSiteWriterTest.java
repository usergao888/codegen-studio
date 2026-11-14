package com.gaoyc.studio.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaoyc.studio.config.LlmProperties;
import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.gen.SiteGenerator;
import com.gaoyc.studio.gen.TemplateSiteWriter;
import com.gaoyc.studio.spec.SpecParser;
import com.sun.net.httpserver.HttpServer;

/**
 * 用本地桩服务器验证「真实模型」这条链路：HTTP 调用、JSON 解析、非法路径过滤、失败回退。
 *
 * <p>这样即使没有 API Key 也能证明代码是通的 —— 桩返回的就是 OpenAI 兼容格式的响应。
 */
public class LlmSiteWriterTest {

    private final SpecParser parser = new SpecParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private TemplateSiteWriter template() {
        return new TemplateSiteWriter(new SiteGenerator());
    }

    private LlmProperties props(String baseUrl, boolean enabled) {
        return new LlmProperties(enabled, baseUrl, "test-key", "stub-model", 5, 60_000);
    }

    private HttpServer stub(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server;
    }

    private String openAiEnvelope(String content) {
        return """
                {"choices":[{"message":{"role":"assistant","content":%s}}]}
                """.formatted(mapper.valueToTree(content).toString());
    }

    @Test
    void parsesFencedJsonIntoFiles() {
        LlmSiteWriter writer = new LlmSiteWriter(props("http://127.0.0.1:1", false), template(), mapper);
        String raw = """
                ```json
                {"files":[
                  {"path":"index.html","content":"<html>MODEL</html>"},
                  {"path":"styles.css","content":"body{}"}
                ]}
                ```
                """;
        SiteDraft draft = writer.parse(raw);
        assertNotNull(draft);
        assertEquals("<html>MODEL</html>", draft.file("index.html"));
        assertEquals("body{}", draft.file("styles.css"));
    }

    @Test
    void stripsWrappingFenceInsideFileContent() {
        LlmSiteWriter writer = new LlmSiteWriter(props("http://127.0.0.1:1", false), template(), mapper);
        String raw = "{\"files\":[{\"path\":\"index.html\",\"content\":\"```html\\n<b>x</b>\\n```\"}]}";
        SiteDraft draft = writer.parse(raw);
        assertNotNull(draft);
        assertFalse(draft.file("index.html").contains("```"));
        assertTrue(draft.file("index.html").contains("<b>x</b>"));
    }

    @Test
    void rejectsPathTraversalAndMissingEntry() {
        LlmSiteWriter writer = new LlmSiteWriter(props("http://127.0.0.1:1", false), template(), mapper);
        assertNull(writer.parse("{\"files\":[{\"path\":\"../../evil.js\",\"content\":\"x\"}]}"),
                "没有 index.html 且路径非法，应判为不可用");
    }

    @Test
    void usesModelOutputWhenServerRespondsProperly() throws Exception {
        String modelHtml = "<html><body>MODEL_HTML_MARKER</body></html>";
        String content = "{\"files\":[{\"path\":\"index.html\",\"content\":"
                + mapper.valueToTree(modelHtml).toString() + "}]}";
        HttpServer server = stub(200, openAiEnvelope(content));
        try {
            LlmSiteWriter writer = new LlmSiteWriter(
                    props("http://127.0.0.1:" + server.getAddress().getPort(), true), template(), mapper);
            SiteDraft draft = writer.write(parser.parse("做一个待办清单"), "做一个待办清单");
            assertTrue(draft.file("index.html").contains("MODEL_HTML_MARKER"), "应使用模型产物");
            assertEquals("llm:stub-model", writer.backend());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToTemplateWhenModelReturnsError() throws Exception {
        HttpServer server = stub(500, "{\"error\":\"boom\"}");
        try {
            LlmSiteWriter writer = new LlmSiteWriter(
                    props("http://127.0.0.1:" + server.getAddress().getPort(), true), template(), mapper);
            AppSpec spec = parser.parse("做一个待办清单");
            SiteDraft draft = writer.write(spec, "做一个待办清单");
            assertTrue(draft.has("styles.css"), "回退后应是模板产物（含 styles.css）");
            assertTrue(draft.file("index.html").contains(spec.title()), "模板产物会带解析出的标题");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToTemplateWhenModelOutputIsGarbage() throws Exception {
        HttpServer server = stub(200, openAiEnvelope("抱歉，我无法完成。"));
        try {
            LlmSiteWriter writer = new LlmSiteWriter(
                    props("http://127.0.0.1:" + server.getAddress().getPort(), true), template(), mapper);
            SiteDraft draft = writer.write(parser.parse("做一个待办清单"), "做一个待办清单");
            assertTrue(draft.has("app.js"), "无法解析时应回退模板");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledConfigUsesTemplateWithoutCallingModel() {
        LlmSiteWriter writer = new LlmSiteWriter(props("http://127.0.0.1:1", false), template(), mapper);
        SiteDraft draft = writer.write(parser.parse("做一个数据看板"), "做一个数据看板");
        assertEquals("template", writer.backend());
        assertTrue(draft.has("styles.css"));
    }
}
