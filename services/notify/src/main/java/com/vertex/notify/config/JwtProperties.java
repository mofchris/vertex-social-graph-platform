package com.vertex.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.jwt.*}. Verifies tokens issued by the Identity service. */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer
) {
}
