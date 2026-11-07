package com.vertex.gateway.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process token bucket. It is the limiter for a single-instance / no-Redis run, and the
 * fallback the {@link RedisRateLimiter} drops to when Redis is unreachable — so a limiter-store
 * outage degrades to per-instance limiting rather than taking the gateway down (EDGE_CASES.md:
 * "limiter store down"). Unlike the Redis limiter the count is not shared across gateway instances.
 *
 * <p>Idle buckets are swept once the map grows past {@link #SWEEP_THRESHOLD}: a bucket that has been
 * untouched long enough to be full again carries no state worth keeping, so a stream of one-off
 * client IPs can't grow the map without bound.
 */
public class LocalRateLimiter implements RateLimiter {

    static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalRateLimiter() {
        this(Clock.systemUTC());
    }

    LocalRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, Limit limit) {
        long now = clock.millis();
        if (buckets.size() > SWEEP_THRESHOLD) {
            sweepIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(limit, now));
        synchronized (bucket) {
            double elapsedSeconds = Math.max(0, now - bucket.timestamp) / 1000.0;
            bucket.tokens = Math.min(limit.capacity(), bucket.tokens + elapsedSeconds * limit.refillPerSecond());
            bucket.timestamp = now;
            bucket.fullAfterMillis = limit.refillPerSecond() > 0
                    ? (long) Math.ceil(limit.capacity() / limit.refillPerSecond() * 1000)
                    : Long.MAX_VALUE;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, (long) bucket.tokens, 0);
            }
            long retryAfter = limit.refillPerSecond() > 0
                    ? (long) Math.ceil((1.0 - bucket.tokens) / limit.refillPerSecond())
                    : 1;
            return new Decision(false, 0, Math.max(1, retryAfter));
        }
    }

    int size() {
        return buckets.size();
    }

    private void sweepIdle(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return now - b.timestamp >= b.fullAfterMillis;
            }
        });
    }

    private static final class Bucket {
        double tokens;
        long timestamp;
        long fullAfterMillis;

        Bucket(Limit limit, long timestamp) {
            this.tokens = limit.capacity();
            this.timestamp = timestamp;
        }
    }
}
