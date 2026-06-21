package com.vertex.gateway.web;

import com.vertex.gateway.config.RateLimitProperties;
import com.vertex.gateway.ratelimit.RateLimiter;
import com.vertex.gateway.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Rate limiting at the very front of the chain — before authentication — so it also shields the
 * public login/signup endpoints from brute force. Two buckets are checked (EDGE_CASES.md: combine
 * per-account and per-IP): a per-IP bucket always, and a per-user bucket when the request carries a
 * valid token. Either being empty yields {@code 429} with a {@code Retry-After}.
 *
 * <p>It parses the token itself rather than reading the security context, so it is independent of
 * the security filter order and can run first. Health/metrics probes are exempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final RateLimiter limiter;
    private final JwtService jwtService;
    private final RateLimitProperties props;

    public RateLimitFilter(RateLimiter limiter, JwtService jwtService, RateLimitProperties props) {
        this.limiter = limiter;
        this.jwtService = jwtService;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!props.enabled() || isExempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Per-IP first: this is the only bucket that protects anonymous traffic (e.g. login attempts).
        RateLimiter.Decision byIp = limiter.tryAcquire("rl:ip:" + request.getRemoteAddr());
        if (!byIp.allowed()) {
            reject(response, byIp);
            return;
        }

        // Per-user when authenticated: one heavy account can't exhaust a shared NAT's IP budget for
        // everyone else, and is still bounded on its own.
        Optional<UUID> userId = authenticatedUser(request);
        if (userId.isPresent()) {
            RateLimiter.Decision byUser = limiter.tryAcquire("rl:user:" + userId.get());
            if (!byUser.allowed()) {
                reject(response, byUser);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Optional<UUID> authenticatedUser(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return jwtService.parseUserId(header.substring(BEARER_PREFIX.length()));
    }

    private boolean isExempt(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    private void reject(HttpServletResponse response, RateLimiter.Decision decision) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
    }
}
