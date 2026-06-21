package com.vertex.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code app.gateway.*}. {@code routes} maps a public service name to the downstream base URL
 * it fronts, e.g. {@code graph -> http://localhost:8082}. A request to {@code /api/<name>/<rest>} is
 * forwarded to {@code <base-url>/<rest>}.
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(Map<String, String> routes) {

    public GatewayProperties {
        routes = (routes == null) ? Map.of() : Map.copyOf(routes);
    }

    /** The downstream base URL for a service name, or {@code null} if there is no such route. */
    public String baseUrlFor(String service) {
        return routes.get(service);
    }
}
