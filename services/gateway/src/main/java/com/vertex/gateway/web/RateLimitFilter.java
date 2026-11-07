package com.vertex.gateway.web;

import com.vertex.gateway.config.RateLimitProperties;
import com.vertex.gateway.ratelimit.RateLimiter;
import com.vertex.gateway.ratelimit.RateLimiter.Decision;
import com.vertex.gateway.ratelimit.RateLimiter.Limit;
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
import java.util.Set;
import java.util.UUID;

/**
 * Rate limiting at the very front of the chain — before authentication — so it also shields the
 * public login/signup endpoints from brute force. Buckets checked, in order (EDGE_CASES.md: combine
 * per-account and per-IP):
 *
 * <ol>
 *   <li><b>auth, per IP</b> — only for {@code /api/identity/v1/auth/**}; deliberately tight, since
 *       this is where password guessing happens;</li>
 *   <li><b>general, per IP</b> — always; the only bucket that bounds anonymous traffic;</li>
 *   <li><b>general, per user</b> — when the request carries a valid token, so one heavy account
 *       can't drain a shared NAT/CGNAT's IP budget for everyone behind it;</li>
 *   <li><b>writes, per user</b> — for state-changing methods, so one account can't flood the
 *       graph, the feed or the event pipeline.</li>
 * </ol>
 *
 * Any empty bucket yields {@code 429} with {@code Retry-After}. Allowed responses carry
 * {@code X-RateLimit-Remaining} (the tightest bucket that applied) so clients can see the budget.
 *
 * <p>It parses the token itself rather than reading the security context, so it is independent of
 * the security filter order and can run first. Health/metrics probes are exempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_PREFIX = "/api/identity/v1/auth/";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RateLimiter limiter;
    private final JwtService jwtService;
    private final RateLimitProperties props;
    private final ClientIpResolver clientIp;

    public RateLimitFilter(RateLimiter limiter, JwtService jwtService, RateLimitProperties props,
                           ClientIpResolver clientIp) {
        this.limiter = limiter;
        this.jwtService = jwtService;
        this.props = props;
        this.clientIp = clientIp;
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

        String ip = clientIp.resolve(request);
        long remaining = Long.MAX_VALUE;

        if (request.getRequestURI().startsWith(AUTH_PREFIX)) {
            Decision auth = take("rl:auth:ip:" + ip, props.auth());
            if (!auth.allowed()) {
                reject(response, auth);
                return;
            }
            remaining = Math.min(remaining, auth.remaining());
        }

        Decision byIp = take("rl:ip:" + ip, props.general());
        if (!byIp.allowed()) {
            reject(response, byIp);
            return;
        }
        remaining = Math.min(remaining, byIp.remaining());

        Optional<UUID> userId = authenticatedUser(request);
        if (userId.isPresent()) {
            Decision byUser = take("rl:user:" + userId.get(), props.general());
            if (!byUser.allowed()) {
                reject(response, byUser);
                return;
            }
            remaining = Math.min(remaining, byUser.remaining());

            if (WRITE_METHODS.contains(request.getMethod())) {
                Decision writes = take("rl:write:user:" + userId.get(), props.writes());
                if (!writes.allowed()) {
                    reject(response, writes);
                    return;
                }
                remaining = Math.min(remaining, writes.remaining());
            }
        }

        response.setHeader(REMAINING_HEADER, String.valueOf(remaining));
        filterChain.doFilter(request, response);
    }

    private Decision take(String key, Limit limit) {
        return limiter.tryAcquire(key, limit);
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

    private void reject(HttpServletResponse response, Decision decision) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setHeader(REMAINING_HEADER, "0");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
    }
}
