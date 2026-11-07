package com.vertex.gateway.ratelimit;

/**
 * A token-bucket rate limiter. {@link #tryAcquire} takes one token for a key (a user id or client
 * IP) from a bucket shaped by {@link Limit}, and reports whether the call is allowed, how many
 * tokens remain, and — when denied — how long to wait before retrying.
 */
public interface RateLimiter {

    Decision tryAcquire(String key, Limit limit);

    /**
     * The shape of one bucket: a client may burst up to {@code capacity} requests, then is paced to
     * {@code refillPerSecond}.
     */
    record Limit(int capacity, double refillPerSecond) {
        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be >= 1");
            }
            if (refillPerSecond < 0) {
                throw new IllegalArgumentException("refillPerSecond must be >= 0");
            }
        }
    }

    /**
     * @param allowed           whether the request may proceed
     * @param remaining         tokens left in the bucket after this call
     * @param retryAfterSeconds seconds until a token is available again (0 when allowed)
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }
}
