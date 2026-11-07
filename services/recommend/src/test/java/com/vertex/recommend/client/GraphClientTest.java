package com.vertex.recommend.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the HTTP contracts the fallback depends on against a throwaway local HTTP server
 * (ephemeral port): the {@code /v1/popular} response shape, token propagation, and the
 * relationship fields that make a popular user ineligible.
 */
class GraphClientTest {

    private HttpServer server;
    private GraphClient client;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        client = new GraphClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void popularParsesTheContractAndForwardsTheToken() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        body = "{\"items\":[{\"userId\":\"" + a + "\",\"followers\":42},"
                + "{\"userId\":\"" + b + "\",\"followers\":7}]}";

        assertThat(client.popular("Bearer t", 20)).containsExactly(a, b);
        assertThat(lastAuth.get()).isEqualTo("Bearer t");
        assertThat(lastQuery.get()).isEqualTo("limit=20");
    }

    @Test
    void popularThrowsOnServerErrorSoTheCallerCanDegrade() {
        status = 500;
        assertThatThrownBy(() -> client.popular("Bearer t", 20)).isInstanceOf(RestClientException.class);
    }

    @Test
    void unconnectedRequiresNoFollowNoFriendshipAndNoBlock() {
        UUID c = UUID.randomUUID();
        body = relationship(false, "NONE", false, false);
        assertThat(client.isUnconnected(c, "Bearer t")).isTrue();

        body = relationship(true, "NONE", false, false);
        assertThat(client.isUnconnected(c, "Bearer t")).isFalse();

        body = relationship(false, "PENDING_OUTGOING", false, false);
        assertThat(client.isUnconnected(c, "Bearer t")).isFalse();

        body = relationship(false, "FRIENDS", false, false);
        assertThat(client.isUnconnected(c, "Bearer t")).isFalse();

        body = relationship(false, "NONE", false, true);
        assertThat(client.isUnconnected(c, "Bearer t")).isFalse();

        status = 503;
        assertThat(client.isUnconnected(c, "Bearer t")).isFalse(); // fails closed
    }

    private static String relationship(boolean following, String friendStatus, boolean blocking, boolean blockedBy) {
        return "{\"following\":" + following + ",\"followedBy\":false,\"friendStatus\":\"" + friendStatus
                + "\",\"blocking\":" + blocking + ",\"blockedBy\":" + blockedBy + "}";
    }
}
