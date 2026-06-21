package com.vertex.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.jwt.*}. The gateway verifies tokens issued by the Identity service. */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer
) {
}
