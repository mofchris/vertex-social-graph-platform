package com.vertex.gateway.ratelimit;

/**
 * A token-bucket rate limiter. {@link #tryAcquire} takes one token for a key (a user id or client
 * IP) and reports whether the call is allowed, how many tokens remain, and — when denied — how long
 * to wait before retrying.
 */
public interface RateLimiter {

    Decision tryAcquire(String key);

    /**
     * @param allowed           whether the request may proceed
     * @param remaining         tokens left in the bucket after this call
     * @param retryAfterSeconds seconds until a token is available again (0 when allowed)
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }
}
