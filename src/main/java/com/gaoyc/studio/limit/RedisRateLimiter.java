package com.gaoyc.studio.limit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis 令牌桶：用一段 Lua 脚本保证"读桶 → 回补 → 扣减 → 写回"是原子的。
 *
 * <p>为什么必须用 Lua：如果拆成 GET/HGET/HMSET 多次往返，两个并发请求会读到同一份 tokens
 * 再各自扣减，超卖就回来了 —— 限流器本身被并发击穿，还不如不装。
 *
 * <p>脚本只用 HMGET / HMSET / PEXPIRE，Redis 2.6+ 都支持；时钟由调用方传入，
 * 因为 Redis 3.2 之前禁止在脚本中调用 TIME（本机 3.0.504 实测会直接报错）。
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String LUA = """
            local key  = KEYS[1]
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local permits = tonumber(ARGV[3])
            local ttl  = tonumber(ARGV[4])

            -- 时钟由调用方传入。Redis 3.2 之前禁止在脚本里调用 TIME
            -- （会报 "Write commands not allowed after non deterministic commands"），
            -- 3.0.504 就是这一种；代价是多实例间时钟可能有个位数毫秒偏差。
            local now = tonumber(ARGV[5])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then tokens = burst end
            if ts == nil then ts = now end

            local delta = math.max(0, now - ts) / 1000
            tokens = math.min(burst, tokens + delta * rate)

            local allowed = 0
            local retry = 0
            if tokens >= permits then
              tokens = tokens - permits
              allowed = 1
            else
              retry = math.ceil((permits - tokens) / rate * 1000)
            end

            redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', key, ttl)
            return {allowed, retry}
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final long keyTtlMillis;

    public RedisRateLimiter(String host, int port, int database) {
        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(Duration.ofSeconds(2))
                .build();
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        // 桶闲置超过这个时长就回收，避免 key 无限增长；必须大于"回满一个桶"所需时间
        this.keyTtlMillis = 120_000L;
        log.info("限流后端已切到 Redis：{}:{}/db{}", host, port, database);
    }

    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(commands.ping());
        } catch (Exception e) {
            log.warn("Redis 限流器探活失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimiter.Decision tryAcquire(String key, int permits, double permitsPerSecond, int burst) {
        try {
            List<Long> result = (List<Long>) commands.eval(
                    LUA,
                    ScriptOutputType.MULTI,
                    new String[]{key},
                    String.valueOf(permitsPerSecond),
                    String.valueOf(Math.max(1, burst)),
                    String.valueOf(permits),
                    String.valueOf(keyTtlMillis),
                    String.valueOf(System.currentTimeMillis()));
            long allowed = result.get(0);
            long retry = result.size() > 1 ? result.get(1) : 0L;
            return allowed == 1
                    ? RateLimiter.Decision.allow()
                    : RateLimiter.Decision.deny(retry);
        } catch (Exception e) {
            // 限流器故障时选择放行并告警：宁可多放几个请求，也不要因为限流挂掉整个接口
            log.warn("Redis 限流执行失败，本次放行：{}", e.getMessage());
            return RateLimiter.Decision.allow();
        }
    }

    @Override
    public String backend() {
        return "redis";
    }

    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
        client.shutdown();
    }
}
