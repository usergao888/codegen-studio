package com.gaoyc.studio.auth;

/** 当前请求的租户。由 {@link ApiKeyAuthInterceptor} 设置，请求结束清理。 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenant) {
        TENANT.set(tenant == null || tenant.isBlank() ? "default" : tenant.trim());
    }

    public static String tenant() {
        String value = TENANT.get();
        return value == null || value.isBlank() ? "default" : value;
    }

    public static void clear() {
        TENANT.remove();
    }
}
