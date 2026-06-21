package com.vertex.gateway.web;

import com.vertex.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * The reverse proxy. A request to {@code /api/<service>/<rest>} is forwarded verbatim (method,
 * headers, query, body) to the downstream base URL configured for {@code <service>}, and the
 * downstream response is streamed back unchanged. Hop-by-hop headers are stripped on both legs so
 * the two HTTP connections stay independent.
 *
 * <p>This is intentionally hand-rolled rather than Spring Cloud Gateway: the platform runs on plain
 * Spring Boot, and owning the proxy keeps the routing, edge auth, and rate limiting in one place
 * with no release-train coupling.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Headers that describe a single connection and must not be forwarded across the proxy. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length",
            "accept-encoding", "x-forwarded-for");
    /** Response headers the servlet container recomputes from the body we return. */
    private static final Set<String> SKIP_RESPONSE = Set.of(
            "transfer-encoding", "content-length", "connection");

    private final GatewayProperties routes;
    private final RestClient client;

    public ProxyController(GatewayProperties routes, RestClient proxyRestClient) {
        this.routes = routes;
        this.client = proxyRestClient;
    }

    @RequestMapping({"/api/{service}", "/api/{service}/**"})
    public ResponseEntity<byte[]> proxy(@PathVariable String service, HttpServletRequest request) throws IOException {
        String baseUrl = routes.baseUrlFor(service);
        if (baseUrl == null) {
            return json(HttpStatus.NOT_FOUND, "{\"error\":\"no route for '" + service + "'\"}");
        }

        URI target = targetUri(baseUrl, service, request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());

        RestClient.RequestBodySpec spec = client.method(method).uri(target);
        copyRequestHeaders(request, spec);
        if (body.length > 0) {
            spec = spec.body(body);
        }

        try {
            return spec.exchange((downstreamRequest, downstreamResponse) -> ResponseEntity
                    .status(downstreamResponse.getStatusCode())
                    .headers(out -> copyResponseHeaders(downstreamResponse.getHeaders(), out))
                    .body(StreamUtils.copyToByteArray(downstreamResponse.getBody())));
        } catch (ResourceAccessException unreachable) {
            // Connect refused / read timeout: the downstream is down or slow. Report it as a gateway
            // error rather than a 500 — the gateway is fine, the upstream isn't.
            log.warn("downstream {} unreachable: {}", target, unreachable.getMessage());
            return json(HttpStatus.BAD_GATEWAY, "{\"error\":\"upstream unavailable\"}");
        }
    }

    private URI targetUri(String baseUrl, String service, HttpServletRequest request) {
        String requestUri = request.getRequestURI();        // already URL-encoded
        String prefix = "/api/" + service;
        String rest = requestUri.length() > prefix.length() ? requestUri.substring(prefix.length()) : "/";
        if (rest.isEmpty()) {
            rest = "/";
        }
        String query = request.getQueryString();
        return URI.create(baseUrl + rest + (query != null ? "?" + query : ""));
    }

    private void copyRequestHeaders(HttpServletRequest request, RestClient.RequestBodySpec spec) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                spec.header(name, values.nextElement());
            }
        }
        // Record the immediate client so downstreams (and the gateway's own rate limiter) can see
        // the real caller. We set it from the connection, not a client-supplied header, so it can't
        // be spoofed at the edge.
        spec.header("X-Forwarded-For", request.getRemoteAddr());
    }

    private void copyResponseHeaders(HttpHeaders from, HttpHeaders to) {
        from.forEach((name, values) -> {
            if (!SKIP_RESPONSE.contains(name.toLowerCase(Locale.ROOT))) {
                to.addAll(name, values);
            }
        });
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
