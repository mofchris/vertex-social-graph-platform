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

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the gateway against a real (in-JVM, dependency-free) downstream HTTP server, covering both
 * the reverse proxy (method/path/query/body/status/headers) and edge authentication: a protected
 * route needs a valid token, a public auth route doesn't, and the token is propagated downstream.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProxyRoutingIntegrationTest {

    private static HttpServer stub;

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            if (path.startsWith("/boom")) {
                exchange.sendResponseHeaders(503, -1); // downstream error, to be passed through
                exchange.close();
                return;
            }
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            boolean tokenPropagated = exchange.getRequestHeaders().containsKey("Authorization");
            byte[] out = (exchange.getRequestMethod() + " " + path
                    + " body=" + new String(requestBody, StandardCharsets.UTF_8)
                    + " auth=" + (tokenPropagated ? "yes" : "no")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Downstream", "stub");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        int port = stub.getAddress().getPort();
        registry.add("app.gateway.routes.echo", () -> "http://localhost:" + port);
        registry.add("app.gateway.routes.identity", () -> "http://localhost:" + port);
        registry.add("app.gateway.routes.dead", () -> "http://localhost:1"); // nothing listens here
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void forwardsMethodPathQueryAndPropagatesToken() throws Exception {
        mvc.perform(get("/api/echo/v1/ping?x=1").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Downstream", "stub"))    // downstream header passed back
                .andExpect(content().string(containsString("GET /v1/ping?x=1")))
                .andExpect(content().string(containsString("auth=yes"))); // token reached downstream
    }

    @Test
    void forwardsRequestBody() throws Exception {
        mvc.perform(post("/api/echo/v1/things").content("hello").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("POST /v1/things body=hello")));
    }

    @Test
    void passesThroughDownstreamErrorStatus() throws Exception {
        mvc.perform(get("/api/echo/boom").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownServiceIsNotFound() throws Exception {
        mvc.perform(get("/api/nope/v1/x").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void unreachableDownstreamIsBadGateway() throws Exception {
        mvc.perform(get("/api/dead/v1/x").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isBadGateway());
    }

    @Test
    void rejectsProtectedRouteWithoutToken() throws Exception {
        mvc.perform(get("/api/echo/v1/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsProtectedRouteWithInvalidToken() throws Exception {
        mvc.perform(get("/api/echo/v1/ping").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsPublicAuthRouteWithoutToken() throws Exception {
        // Login must be reachable before you have a token.
        mvc.perform(post("/api/identity/v1/auth/login").content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("POST /v1/auth/login")));
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
