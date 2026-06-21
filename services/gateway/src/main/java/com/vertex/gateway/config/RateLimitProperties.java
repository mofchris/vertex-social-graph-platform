package com.vertex.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code app.ratelimit.*}. A token bucket of {@code capacity} that refills at
 * {@code refillPerSecond}: a client may burst up to capacity, then is paced to the refill rate.
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int capacity,
        @DefaultValue("50") double refillPerSecond
) {
}
