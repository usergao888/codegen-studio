package com.gaoyc.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API Key 鉴权配置。
 *
 * <p>{@code keys} 格式：{@code key1:tenant-a,key2:tenant-b}。只在启用时校验；
 * 本地 demo 默认关闭，生产用环境变量 {@code STUDIO_AUTH_ENABLED=true}、
 * {@code STUDIO_AUTH_KEYS=...} 开启，不要把真实 key 写进 application.yml。
 */
@ConfigurationProperties(prefix = "studio.auth")
public record AuthProperties(boolean enabled, String keys) {

    public AuthProperties {
        keys = keys == null ? "" : keys;
    }
}
