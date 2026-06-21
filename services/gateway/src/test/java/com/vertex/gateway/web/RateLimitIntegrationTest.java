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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the edge rate limiter with the in-process limiter (no Redis): a capacity-1 bucket lets
 * one request through then returns 429 with a Retry-After, and distinct clients are limited
 * independently. Each test uses its own client IP so buckets don't leak across methods.
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
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        registry.add("app.gateway.routes.echo", () -> "http://localhost:" + stub.getAddress().getPort());
        // Capacity 1 so the second request from a client is immediately over the limit.
        registry.add("app.ratelimit.capacity", () -> "1");
        registry.add("app.ratelimit.refill-per-second", () -> "1");
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

        mvc.perform(get("/api/echo/v1/ping").with(clientIp("10.10.0.1")).header("Authorization", token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/echo/v1/ping").with(clientIp("10.10.0.1")).header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void differentClientsAreLimitedIndependently() throws Exception {
        mvc.perform(get("/api/echo/v1/ping").with(clientIp("10.10.0.2")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/echo/v1/ping").with(clientIp("10.10.0.3")).header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk()); // a different IP + user has its own bucket
    }

    private static RequestPostProcessor clientIp(String addr) {
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
