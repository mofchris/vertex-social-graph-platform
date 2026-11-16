package com.vertex.gateway.web;

import com.vertex.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Works out which client a request came from, for per-IP rate limiting and the
 * {@code X-Forwarded-For} sent downstream.
 *
 * <p>Behind a tunnel or load balancer every request arrives from the same peer address, so keying
 * buckets on {@link HttpServletRequest#getRemoteAddr()} would make all visitors share one bucket.
 * When {@code app.gateway.client-ip-header} is configured, the first address in that header is used
 * instead — but only if it parses as an IP literal, so a garbage value can't mint fresh buckets.
 */
@Component
public class ClientIpResolver {

    /** IPv4 dotted quad or an IPv6 literal (hex groups, colons, optional embedded IPv4). */
    private static final Pattern IP_LITERAL = Pattern.compile(
            "^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9A-Fa-f:]{2,39}(?:\\.\\d{1,3}(?:\\.\\d{1,3}){3})?)$");

    private final String header;

    public ClientIpResolver(GatewayProperties props) {
        this.header = props.clientIpHeader();
    }

    public String resolve(HttpServletRequest request) {
        if (header != null) {
            String value = request.getHeader(header);
            if (value != null) {
                int comma = value.indexOf(',');
                String first = (comma >= 0 ? value.substring(0, comma) : value).trim();
                if (IP_LITERAL.matcher(first).matches()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
