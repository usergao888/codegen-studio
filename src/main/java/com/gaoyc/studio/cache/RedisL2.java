package com.gaoyc.studio.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis 二级缓存（String 存储 + JSON 序列化 + EX 过期）。
 *
 * <p>刻意不用 Spring 的 {@code RedisTemplate}：这里只需要 get/set/del 三个命令，
 * 直接拿 Lettuce 的连接更轻，也避免为了一个缓存引入整套自动配置。
 * 连接是懒建立的 —— 启动时不 ping，第一次真正用到才连，连不上就抛给上层回退。
 */
public class RedisL2<V> implements L2Store<V> {

    private static final Logger log = LoggerFactory.getLogger(RedisL2.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper;
    private final Class<V> valueType;
    private final String keyPrefix;

    public RedisL2(String host, int port, int database, String keyPrefix,
                   ObjectMapper mapper, Class<V> valueType) {
        this.keyPrefix = keyPrefix == null ? "studio:gen:" : keyPrefix;
        this.mapper = mapper;
        this.valueType = valueType;
        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(Duration.ofSeconds(2))
                .build();
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        log.info("二级缓存已切到 Redis：{}:{}/db{}", host, port, database);
    }

    /** 探活：启动时调用一次，失败就回退到内存实现。 */
    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(commands.ping());
        } catch (Exception e) {
            log.warn("Redis 探活失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<V> get(String key) {
        try {
            String raw = commands.get(keyPrefix + key);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapper.readValue(raw, valueType));
        } catch (Exception e) {
            // 缓存故障不能影响主链路：降级为未命中
            log.warn("Redis 读取失败，按未命中处理：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, V value, long ttlMillis) {
        try {
            commands.setex(keyPrefix + key, Math.max(1, ttlMillis / 1000),
                    mapper.writeValueAsString(value));
        } catch (Exception e) {
            log.warn("Redis 写入失败，跳过缓存：{}", e.getMessage());
        }
    }

    @Override
    public void remove(String key) {
        try {
            commands.del(keyPrefix + key);
        } catch (Exception e) {
            log.warn("Redis 删除失败：{}", e.getMessage());
        }
    }

    @Override
    public String backend() {
        return "redis";
    }

    @Override
    public long size() {
        try {
            Long dbSize = commands.dbsize();
            return dbSize == null ? -1 : dbSize;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
        client.shutdown();
    }
}
