package com.gaoyc.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 真实模型接入配置（OpenAI 兼容端点：DeepSeek / DashScope / vLLM / Ollama 都行）。
 *
 * <p>单独一个配置类而不是塞进 StudioProperties：模型接入是"可选增强"，
 * 关掉时整个应用仍然完整可用，配置也该是独立的。
 */
@ConfigurationProperties(prefix = "studio.llm")
public record LlmProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int maxOutputChars
) {

    public LlmProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com/v1" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null || model.isBlank() ? "deepseek-chat" : model;
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        maxOutputChars = maxOutputChars <= 0 ? 60_000 : maxOutputChars;
    }

    /** enabled=true 但没配 key 时视为未启用 —— 免得每个请求都去撞一次 401。 */
    public boolean usable() {
        return enabled && !apiKey.isBlank();
    }
}
