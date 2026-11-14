package com.gaoyc.studio.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的 /chat/completions 客户端，只用 JDK 自带的 HttpClient。
 *
 * <p>为什么不用 LangChain4j：这里只需要"发一次请求、拿一段文本"，
 * 引一整套框架（及其传递依赖）换不来什么；JDK 客户端反而更可控，也不会有版本地狱。
 * 真正的复杂点不在调用，而在**返回内容的校验与回退**，那部分在 {@link LlmSiteWriter}。
 */
public class OpenAiCompatClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatClient(String baseUrl, String apiKey, String model,
                              Duration timeout, ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    /** 发一轮对话。异常与 HTTP 非 2xx 都抛 IOException，由上层决定是否回退。 */
    public String chat(String systemPrompt, String userPrompt) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException("模型返回 HTTP " + response.statusCode()
                    + "：" + truncate(response.body(), 300));
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IOException("模型返回里没有 content：" + truncate(response.body(), 300));
        }
        return content.asText();
    }

    public String model() {
        return model;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
