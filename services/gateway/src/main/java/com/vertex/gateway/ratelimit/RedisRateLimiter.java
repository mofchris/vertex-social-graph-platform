package com.vertex.gateway.ratelimit;

import com.vertex.gateway.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Distributed token bucket backed by Redis. The decision runs inside a single Lua script so the
 * read-modify-write is atomic and shared across every gateway instance. If Redis is unreachable we
 * don't fail the request — we drop to a per-instance {@link LocalRateLimiter} so a limiter-store
 * outage degrades gracefully instead of becoming a gateway outage (EDGE_CASES.md).
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final RateLimiter fallback;
    private final String capacity;
    private final String refillPerSecond;

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties props, RateLimiter fallback) {
        this.redis = redis;
        this.fallback = fallback;
        this.capacity = String.valueOf(props.capacity());
        this.refillPerSecond = String.valueOf(props.refillPerSecond());
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision tryAcquire(String key) {
        try {
            List<Long> result = redis.execute(script, List.of(key),
                    capacity, refillPerSecond, String.valueOf(System.currentTimeMillis()), "1");
            return new Decision(result.get(0) == 1L, result.get(1), result.get(2));
        } catch (RuntimeException redisUnavailable) {
            log.warn("rate-limit store unavailable, falling back to local limiter: {}",
                    redisUnavailable.getMessage());
            return fallback.tryAcquire(key);
        }
    }
}
