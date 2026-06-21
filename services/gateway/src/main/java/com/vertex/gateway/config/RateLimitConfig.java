package com.vertex.gateway.config;

import com.vertex.gateway.ratelimit.LocalRateLimiter;
import com.vertex.gateway.ratelimit.RateLimiter;
import com.vertex.gateway.ratelimit.RedisRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chooses the rate limiter for the active profile. Under {@code redis} the limiter is distributed
 * (shared across gateway instances) with a local fallback; otherwise it's a single in-process
 * limiter — so the default JDK-only run rate-limits correctly without a Redis dependency.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @Profile("redis")
    RateLimiter redisRateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        return new RedisRateLimiter(redis, props, new LocalRateLimiter(props));
    }

    @Bean
    @Profile("!redis")
    RateLimiter localRateLimiter(RateLimitProperties props) {
        return new LocalRateLimiter(props);
    }
}
