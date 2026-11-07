package com.vertex.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Binds {@code app.gateway.*}.
 *
 * <ul>
 *   <li>{@code routes} maps a public service name to the downstream base URL it fronts, e.g.
 *       {@code graph -> http://localhost:8082}. A request to {@code /api/<name>/<rest>} is forwarded
 *       to {@code <base-url>/<rest>}.</li>
 *   <li>{@code blocked-paths} are Ant patterns (matched against the public path) the gateway refuses
 *       with 404 instead of forwarding — e.g. each service's actuator, or an internal-only endpoint.
 *       The services stay reachable on the private network; strangers just can't reach them.</li>
 *   <li>{@code client-ip-header} names a header set by a trusted edge (Cloudflare, a load balancer)
 *       that carries the real client address. Blank means "use the TCP peer address". Only set it
 *       when the gateway is reachable exclusively through that edge, or clients could spoof it.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(Map<String, String> routes, List<String> blockedPaths, String clientIpHeader) {

    public GatewayProperties {
        routes = (routes == null) ? Map.of() : Map.copyOf(routes);
        blockedPaths = (blockedPaths == null) ? List.of() : List.copyOf(blockedPaths);
        clientIpHeader = (clientIpHeader == null || clientIpHeader.isBlank()) ? null : clientIpHeader.trim();
    }

    /** The downstream base URL for a service name, or {@code null} if there is no such route. */
    public String baseUrlFor(String service) {
        return routes.get(service);
    }
}
