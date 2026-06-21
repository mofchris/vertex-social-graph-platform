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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the reverse proxy against a real (in-JVM, dependency-free) downstream HTTP server, so the
 * forwarding of method, path, query, body, status, and headers is exercised over an actual socket.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProxyRoutingIntegrationTest {

    private static HttpServer stub;

    @Autowired private MockMvc mvc;

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
            byte[] out = (exchange.getRequestMethod() + " " + path
                    + " body=" + new String(requestBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Downstream", "stub");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        int port = stub.getAddress().getPort();
        registry.add("app.gateway.routes.echo", () -> "http://localhost:" + port);
        registry.add("app.gateway.routes.dead", () -> "http://localhost:1"); // nothing listens here
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void forwardsMethodPathQueryAndDownstreamResponse() throws Exception {
        mvc.perform(get("/api/echo/v1/ping?x=1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Downstream", "stub"))   // downstream header passed back
                .andExpect(content().string(containsString("GET /v1/ping?x=1")));
    }

    @Test
    void forwardsRequestBody() throws Exception {
        mvc.perform(post("/api/echo/v1/things").content("hello"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("POST /v1/things body=hello")));
    }

    @Test
    void passesThroughDownstreamErrorStatus() throws Exception {
        mvc.perform(get("/api/echo/boom")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownServiceIsNotFound() throws Exception {
        mvc.perform(get("/api/nope/v1/x")).andExpect(status().isNotFound());
    }

    @Test
    void unreachableDownstreamIsBadGateway() throws Exception {
        mvc.perform(get("/api/dead/v1/x")).andExpect(status().isBadGateway());
    }
}
