package com.gaoyc.studio.auth;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.gaoyc.studio.config.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 轻量 API Key 鉴权：{@code X-API-Key} -> tenant。
 *
 * <p>没有引入 Spring Security：当前只需要"拦住 /api/** 并绑定租户"，一个
 * HandlerInterceptor 足够，也避免离线环境缺安全依赖。生产化可以换成
 * OAuth2/OIDC + 角色校验，契约不变。
 *
 * <p>{@code /api/health} 与 {@code /preview/**} 公开；其余 /api 在启用后必须带 key。
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-API-Key";
    public static final String TENANT_ATTRIBUTE = "studio.tenant";

    private final boolean enabled;
    private final Map<String, String> keys;

    public ApiKeyAuthInterceptor(AuthProperties properties) {
        this.enabled = properties.enabled();
        this.keys = parseKeys(properties.keys());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/health".equals(request.getRequestURI())) {
            TenantContext.set("default");
            return true;
        }
        String provided = request.getHeader(HEADER);
        String tenant = provided == null ? null : keys.get(provided);
        if (tenant == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("WWW-Authenticate", "ApiKey");
            response.getWriter().write(
                    "{\"error\":\"未授权：请提供有效的 X-API-Key\",\"code\":\"AUTH_REQUIRED\"}");
            return false;
        }
        TenantContext.set(tenant);
        request.setAttribute(TENANT_ATTRIBUTE, tenant);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }

    /** 格式：{@code key1:tenant-a,key2:tenant-b}；空条目忽略。 */
    static Map<String, String> parseKeys(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        for (String entry : raw.split(",")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] parts = item.split(":", 2);
            String key = parts[0].trim();
            if (key.isEmpty()) {
                continue;
            }
            String tenant = parts.length > 1 && !parts[1].isBlank()
                    ? parts[1].trim() : "default";
            parsed.put(key, tenant);
        }
        return Map.copyOf(parsed);
    }
}
