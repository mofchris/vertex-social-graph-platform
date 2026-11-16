package com.vertex.gateway.web;

import com.sun.net.httpserver.HttpServer;
import com.vertex.gateway.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-Sent Events must pass through the gateway as they happen. The stub downstream writes one
 * event and then holds the connection open (as Notify's stream does); the client must receive that
 * event long before the stream ends — and well past the normal 10 s read timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingProxyIntegrationTest {

    private static HttpServer stub;
    private static final CountDownLatch RELEASE = new CountDownLatch(1);

    @LocalServerPort private int port;
    @Autowired private JwtProperties jwtProps;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        stub.createContext("/v1/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            OutputStream out = exchange.getResponseBody();
            out.write("event:connected\ndata:ok\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                RELEASE.await(30, TimeUnit.SECONDS); // keep the stream open until the test is done
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stub.start();
        registry.add("app.gateway.routes.notify", () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        RELEASE.countDown();
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void deliversEventsBeforeTheStreamEnds() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notify/v1/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", bearer(UUID.randomUUID()))
                .GET().build();

        long start = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).isEqualTo("event:connected");
            assertThat(reader.readLine()).isEqualTo("data:ok");
        }
        // Arrived while the downstream was still holding the stream open — not buffered to the end.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        RELEASE.countDown();
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
