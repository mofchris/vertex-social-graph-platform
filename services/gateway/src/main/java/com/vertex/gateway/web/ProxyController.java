package com.vertex.gateway.web;

import com.vertex.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * The reverse proxy. A request to {@code /api/<service>/<rest>} is forwarded verbatim (method,
 * headers, query, body) to the downstream base URL configured for {@code <service>}, and the
 * downstream response is streamed back as it arrives. Hop-by-hop headers are stripped on both legs
 * so the two HTTP connections stay independent.
 *
 * <p>Streaming matters for Server-Sent Events: Notify's notification stream never "finishes", so a
 * proxy that buffered the whole body would deliver nothing. Requests that ask for
 * {@code text/event-stream} use a client with no read timeout, and every chunk of an event-stream
 * response is flushed to the caller immediately. Everything else keeps a bounded read timeout so a
 * slow or dead downstream can't pin a gateway thread.
 *
 * <p>Paths matching {@code app.gateway.blocked-paths} (each service's actuator, internal-only
 * endpoints) are refused with 404 before anything is forwarded.
 *
 * <p>This is intentionally hand-rolled rather than Spring Cloud Gateway: the platform runs on plain
 * Spring Boot, and owning the proxy keeps the routing, edge auth, and rate limiting in one place
 * with no release-train coupling.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Request attribute holding {@link System#nanoTime()} at the moment the gateway first saw it. */
    static final String ARRIVED_AT = "vertex.arrivedAt";

    /** Headers that describe a single connection and must not be forwarded across the proxy. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length",
            "accept-encoding", "x-forwarded-for");
    /** Response headers the servlet container recomputes from the body we write. */
    private static final Set<String> SKIP_RESPONSE = Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive");

    private final GatewayProperties props;
    private final RestClient client;
    private final RestClient streamingClient;
    private final ClientIpResolver clientIp;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public ProxyController(GatewayProperties props,
                           @Qualifier("proxyRestClient") RestClient client,
                           @Qualifier("streamingProxyRestClient") RestClient streamingClient,
                           ClientIpResolver clientIp) {
        this.props = props;
        this.client = client;
        this.streamingClient = streamingClient;
        this.clientIp = clientIp;
    }

    @RequestMapping({"/api/{service}", "/api/{service}/**"})
    public void proxy(@PathVariable String service, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String baseUrl = props.baseUrlFor(service);
        if (baseUrl == null) {
            writeJson(response, HttpStatus.NOT_FOUND, "{\"error\":\"no route for '" + service + "'\"}");
            return;
        }
        if (isBlocked(request.getRequestURI())) {
            writeJson(response, HttpStatus.NOT_FOUND, "{\"error\":\"not found\"}");
            return;
        }

        URI target = targetUri(baseUrl, service, request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        boolean wantsStream = acceptsEventStream(request);

        RestClient.RequestBodySpec spec = (wantsStream ? streamingClient : client).method(method).uri(target);
        copyRequestHeaders(request, spec);
        if (body.length > 0) {
            spec = spec.body(body);
        }

        long sentAt = System.nanoTime();
        try {
            spec.exchange((downstreamRequest, downstreamResponse) -> {
                response.setStatus(downstreamResponse.getStatusCode().value());
                copyResponseHeaders(downstreamResponse.getHeaders(), response);
                response.addHeader("Server-Timing", serverTiming(request, service, sentAt));
                boolean eventStream = isEventStream(downstreamResponse.getHeaders());
                if (eventStream) {
                    response.flushBuffer(); // send status + headers now; the body may take minutes
                }
                try (InputStream in = downstreamResponse.getBody()) {
                    OutputStream out = response.getOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (eventStream) {
                            out.flush();
                        }
                    }
                    out.flush();
                }
                return null;
            });
        } catch (ResourceAccessException unreachable) {
            if (response.isCommitted()) {
                // Mid-stream failure: the caller disconnected or the downstream dropped. Nothing
                // useful can be written any more; just release the connection.
                log.debug("stream to {} ended: {}", target, unreachable.getMessage());
                return;
            }
            // Connect refused / read timeout: the downstream is down or slow. Report it as a gateway
            // error rather than a 500 — the gateway is fine, the upstream isn't.
            log.warn("downstream {} unreachable: {}", target, unreachable.getMessage());
            writeJson(response, HttpStatus.BAD_GATEWAY, "{\"error\":\"upstream unavailable\",\"service\":\"" + service + "\"}");
        }
    }

    /**
     * W3C Server-Timing: how long the request has spent in the gateway so far, and how long the
     * downstream service took to start answering. Measured when the downstream headers arrive, so
     * it covers the work, not the time spent streaming a body back.
     */
    private static String serverTiming(HttpServletRequest request, String service, long sentAt) {
        long now = System.nanoTime();
        Object arrived = request.getAttribute(ARRIVED_AT);
        long start = arrived instanceof Long t ? t : sentAt;
        return String.format(Locale.ROOT, "gateway;dur=%.1f, upstream;desc=\"%s\";dur=%.1f",
                (now - start) / 1e6, service, (now - sentAt) / 1e6);
    }

    private boolean isBlocked(String path) {
        for (String pattern : props.blockedPaths()) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private static boolean isEventStream(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        return type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
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
        // Record the real caller so downstreams can see it. Resolved by the gateway (the TCP peer,
        // or the trusted edge header when configured), never copied from a client-supplied header.
        spec.header("X-Forwarded-For", clientIp.resolve(request));
    }

    private static void copyResponseHeaders(HttpHeaders from, HttpServletResponse to) {
        from.forEach((name, values) -> {
            if (!SKIP_RESPONSE.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    to.addHeader(name, value);
                }
            }
        });
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
