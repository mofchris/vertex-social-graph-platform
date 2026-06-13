package com.vertex.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.jwt.*}. This service only *verifies* tokens issued by the Identity
 * service, so it needs the shared signing secret and the expected issuer.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        String issuer
) {
}
