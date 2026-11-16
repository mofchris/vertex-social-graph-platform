package com.vertex.gateway.ratelimit;

import com.vertex.gateway.ratelimit.RateLimiter.Decision;
import com.vertex.gateway.ratelimit.RateLimiter.Limit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Lua token bucket against a real Redis: the burst/deny/retry-after contract, atomicity
 * under concurrent callers (the reason the decision lives in a script), and the local fallback when
 * Redis goes away.
 */
@Testcontainers
class RedisRateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, new LocalRateLimiter());

    @Test
    void burstsToCapacityThenDeniesWithRetryAfter() {
        String key = "rl:test:" + UUID.randomUUID();
        Limit limit = new Limit(3, 0.5);

        assertThat(limiter.tryAcquire(key, limit).remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire(key, limit).remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire(key, limit).remaining()).isZero();

        Decision denied = limiter.tryAcquire(key, limit);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(2); // one token takes 2 s at 0.5/s
    }

    @Test
    void idleBucketsExpireFromRedis() {
        String key = "rl:test:" + UUID.randomUUID();
        limiter.tryAcquire(key, new Limit(2, 1));
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isBetween(1L, 3L); // ceil(capacity / refill) + 1
    }

    @Test
    void concurrentCallersNeverOverspendTheBucket() throws Exception {
        String key = "rl:test:" + UUID.randomUUID();
        Limit limit = new Limit(20, 0.001); // effectively no refill during the test

        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            calls.add(() -> limiter.tryAcquire(key, limit).allowed());
        }
        long allowed;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            allowed = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                if (f.get()) {
                    allowed++;
                }
            }
        }
        assertThat(allowed).isEqualTo(20);
    }

    @Test
    void fallsBackToTheLocalLimiterWhenRedisIsUnreachable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        dead.start();
        try {
            RedisRateLimiter offline = new RedisRateLimiter(new StringRedisTemplate(dead), new LocalRateLimiter());
            Limit limit = new Limit(1, 0.001);
            assertThat(offline.tryAcquire("k", limit).allowed()).isTrue();  // served, not failed
            assertThat(offline.tryAcquire("k", limit).allowed()).isFalse(); // and still limited
        } finally {
            dead.destroy();
        }
    }
}
