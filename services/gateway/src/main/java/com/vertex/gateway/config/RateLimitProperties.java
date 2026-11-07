package com.vertex.gateway.config;

import com.vertex.gateway.ratelimit.RateLimiter.Limit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code app.ratelimit.*}. Each policy is a token bucket: a client may burst up to
 * {@code capacity}, then is paced to {@code refill-per-second}.
 *
 * <ul>
 *   <li>{@code capacity}/{@code refill-per-second} — every request, per client IP and per user;</li>
 *   <li>{@code auth-*} — the public login/signup/refresh endpoints, per client IP, tight enough to
 *       make password guessing slow;</li>
 *   <li>{@code write-*} — state-changing requests (POST/PUT/PATCH/DELETE), per user, so one account
 *       can't flood the graph, feed or event pipeline.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int capacity,
        @DefaultValue("50") double refillPerSecond,
        @DefaultValue("10") int authCapacity,
        @DefaultValue("0.2") double authRefillPerSecond,
        @DefaultValue("30") int writeCapacity,
        @DefaultValue("0.5") double writeRefillPerSecond,
        @DefaultValue("6") int labCapacity,
        @DefaultValue("0.05") double labRefillPerSecond
) {

    /**
     * The pipeline lab (replaying an event, sending a poison record), per user. Each call adds an
     * outbox row and a Kafka record, so it gets its own small budget: 6 at once, then 3 a minute.
     */
    public Limit lab() {
        return new Limit(labCapacity, labRefillPerSecond);
    }

    public Limit general() {
        return new Limit(capacity, refillPerSecond);
    }

    public Limit auth() {
        return new Limit(authCapacity, authRefillPerSecond);
    }

    public Limit writes() {
        return new Limit(writeCapacity, writeRefillPerSecond);
    }
}
