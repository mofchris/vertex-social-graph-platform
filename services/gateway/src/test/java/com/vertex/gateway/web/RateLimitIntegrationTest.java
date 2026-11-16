package com.vertex.gateway.web;

import com.sun.net.httpserver.HttpServer;
import com.vertex.gateway.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the edge rate limiter with the in-process limiter (no Redis). Buckets are tiny so limits
 * trip immediately; each test uses its own client IPs and users so buckets don't leak across tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    private static HttpServer stub;

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        String base = "http://localhost:" + stub.getAddress().getPort();
        registry.add("app.gateway.routes.echo", () -> base);
        registry.add("app.gateway.routes.identity", () -> base);
        registry.add("app.gateway.routes.graph", () -> base);
        registry.add("app.gateway.client-ip-header", () -> "X-Edge-Client-IP");
        registry.add("app.ratelimit.capacity", () -> "3");
        registry.add("app.ratelimit.refill-per-second", () -> "0.01");
        registry.add("app.ratelimit.auth-capacity", () -> "2");
        registry.add("app.ratelimit.auth-refill-per-second", () -> "0.01");
        registry.add("app.ratelimit.write-capacity", () -> "2");
        registry.add("app.ratelimit.write-refill-per-second", () -> "0.01");
        registry.add("app.ratelimit.lab-capacity", () -> "1");
        registry.add("app.ratelimit.lab-refill-per-second", () -> "0.01");
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void allowsUpToCapacityThenReturns429WithRetryAfter() throws Exception {
        String token = bearer(UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/echo/v1/ping").with(peer("10.10.0.1")).header("Authorization", token))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/echo/v1/ping").with(peer("10.10.0.1")).header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "0"));
    }

    @Test
    void reportsTheRemainingBudget() throws Exception {
        mvc.perform(get("/api/echo/v1/ping").with(peer("10.10.0.9")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "2"));
    }

    @Test
    void differentClientsAreLimitedIndependently() throws Exception {
        mvc.perform(get("/api/echo/v1/ping").with(peer("10.10.0.2")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/echo/v1/ping").with(peer("10.10.0.3")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk()); // a different IP + user has its own bucket
    }

    @Test
    void authEndpointsHaveTheirOwnTighterBucket() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/identity/v1/auth/login").with(peer("10.20.0.1")).content("{}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/identity/v1/auth/login").with(peer("10.20.0.1")).content("{}"))
                .andExpect(status().isTooManyRequests());
        // The general per-IP budget (3) is not what tripped: a non-auth call still goes through.
        mvc.perform(get("/api/echo/v1/ping").with(peer("10.20.0.1")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void writesArePacedPerUser() throws Exception {
        String token = bearer(UUID.randomUUID());
        mvc.perform(post("/api/echo/v1/things").with(peer("10.30.0.1")).header("Authorization", token).content("a"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/echo/v1/things").with(peer("10.30.0.3")).header("Authorization", token).content("b"))
                .andExpect(status().isOk());
        // Same user from yet another IP: the per-user write bucket (2) still applies.
        mvc.perform(post("/api/echo/v1/things").with(peer("10.30.0.2")).header("Authorization", token).content("c"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void pipelineLabCallsHaveTheirOwnBudget() throws Exception {
        String token = bearer(UUID.randomUUID());
        mvc.perform(post("/api/graph/v1/events/poison").with(peer("10.50.0.1")).header("Authorization", token))
                .andExpect(status().isOk());
        // The lab bucket (1) is empty, though the user still has write budget left...
        mvc.perform(post("/api/graph/v1/events/poison").with(peer("10.50.0.2")).header("Authorization", token))
                .andExpect(status().isTooManyRequests());
        // ...which an ordinary write proves.
        mvc.perform(post("/api/graph/v1/follow/x").with(peer("10.50.0.3")).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void usesTheTrustedEdgeHeaderForTheClientIp() throws Exception {
        // Everything arrives from one peer (as through a tunnel), but the edge says who the client is.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/identity/v1/auth/login").with(peer("127.0.0.1"))
                            .header("X-Edge-Client-IP", "203.0.113.7").content("{}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/identity/v1/auth/login").with(peer("127.0.0.1"))
                        .header("X-Edge-Client-IP", "203.0.113.7").content("{}"))
                .andExpect(status().isTooManyRequests());
        // Another visitor behind the same tunnel is not affected.
        mvc.perform(post("/api/identity/v1/auth/login").with(peer("127.0.0.1"))
                        .header("X-Edge-Client-IP", "2001:db8::42").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void ignoresAMalformedEdgeHeader() throws Exception {
        // Garbage in the header must not mint a fresh bucket per request: it falls back to the peer.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/identity/v1/auth/login").with(peer("10.40.0.1"))
                            .header("X-Edge-Client-IP", "not-an-ip-" + i).content("{}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/identity/v1/auth/login").with(peer("10.40.0.1"))
                        .header("X-Edge-Client-IP", "still-not-an-ip").content("{}"))
                .andExpect(status().isTooManyRequests());
    }

    private static RequestPostProcessor peer(String addr) {
        return request -> {
            request.setRemoteAddr(addr);
            return request;
        };
    }

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder().issuer(jwtProps.issuer()).subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key).compact();
        return "Bearer " + token;
    }
}
