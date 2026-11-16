package com.vertex.gateway.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/status} is public and reports each routed service's own health: one healthy stub,
 * one stub that answers 503 (a Spring Boot app still starting), one route with nothing listening.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatusIntegrationTest {

    private static HttpServer healthy;
    private static HttpServer starting;

    @Autowired private MockMvc mvc;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        healthy = stub(200, "{\"status\":\"UP\"}");
        starting = stub(503, "{\"status\":\"OUT_OF_SERVICE\"}");
        registry.add("app.gateway.routes.alpha", () -> "http://localhost:" + healthy.getAddress().getPort());
        registry.add("app.gateway.routes.beta", () -> "http://localhost:" + starting.getAddress().getPort());
        registry.add("app.gateway.routes.gamma", () -> "http://localhost:1");
    }

    private static HttpServer stub(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    @AfterAll
    static void stop() {
        healthy.stop(0);
        starting.stop(0);
    }

    @Test
    void reportsEachServiceWithoutAToken() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                // alpha is up and beta is not, so whatever the default routes do, it's partial.
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.services[?(@.name == 'alpha')].status", contains("UP")))
                .andExpect(jsonPath("$.services[?(@.name == 'beta')].status", contains("DOWN")))
                .andExpect(jsonPath("$.services[?(@.name == 'gamma')].status", contains("DOWN")))
                .andExpect(jsonPath("$.services[?(@.name == 'alpha')].latencyMs", hasSize(1)));
    }
}
