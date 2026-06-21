package com.vertex.gateway.ratelimit;

import com.vertex.gateway.config.RateLimitProperties;

import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process token bucket. It is the limiter for a single-instance / no-Redis run, and the
 * fallback the {@link RedisRateLimiter} drops to when Redis is unreachable — so a limiter-store
 * outage degrades to per-instance limiting rather than taking the gateway down (EDGE_CASES.md:
 * "limiter store down"). Buckets are kept in a map; acceptable for a fallback, though unlike the
 * Redis limiter the count is not shared across gateway instances.
 */
public class LocalRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalRateLimiter(RateLimitProperties props) {
        this.capacity = props.capacity();
        this.refillPerSecond = props.refillPerSecond();
    }

    @Override
    public Decision tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, System.currentTimeMillis()));
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            double elapsedSeconds = Math.max(0, now - bucket.timestamp) / 1000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.timestamp = now;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, (long) bucket.tokens, 0);
            }
            long retryAfter = refillPerSecond > 0
                    ? (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond)
                    : 1;
            return new Decision(false, 0, Math.max(1, retryAfter));
        }
    }

    private static final class Bucket {
        double tokens;
        long timestamp;

        Bucket(double tokens, long timestamp) {
            this.tokens = tokens;
            this.timestamp = timestamp;
        }
    }
}
