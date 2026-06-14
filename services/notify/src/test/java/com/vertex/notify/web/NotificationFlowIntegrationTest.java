package com.vertex.notify.web;

import com.vertex.notify.config.JwtProperties;
import com.vertex.notify.repository.NotificationRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drives the Notify API against embedded H2, focusing on notification coalescing. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;
    @Autowired private NotificationRepository repository;

    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder().issuer(jwtProps.issuer()).subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key).compact();
        return "Bearer " + token;
    }

    private void event(UUID actor, String type, UUID target) throws Exception {
        String body = (target == null)
                ? "{\"recipientId\":\"" + recipient + "\",\"type\":\"" + type + "\"}"
                : "{\"recipientId\":\"" + recipient + "\",\"type\":\"" + type + "\",\"targetId\":\"" + target + "\"}";
        mvc.perform(post("/v1/events").header("Authorization", bearer(actor))
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void coalescesAStormIntoOneNotification() throws Exception {
        // 50 different users follow the recipient.
        for (int i = 0; i < 50; i++) {
            event(UUID.randomUUID(), "FOLLOW", null);
        }

        // One coalesced notification with a count of 50, not 50 rows.
        mvc.perform(get("/v1/notifications").header("Authorization", bearer(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("FOLLOW"))
                .andExpect(jsonPath("$.items[0].actorCount").value(50));

        mvc.perform(get("/v1/notifications/unread-count").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void distinctTypesAndTargetsStaySeparate() throws Exception {
        UUID postP = UUID.randomUUID();
        UUID postQ = UUID.randomUUID();
        event(UUID.randomUUID(), "FOLLOW", null);
        event(UUID.randomUUID(), "FRIEND_REQUEST", null);
        event(UUID.randomUUID(), "POST_LIKE", postP);
        event(UUID.randomUUID(), "POST_LIKE", postP); // coalesces with the previous
        event(UUID.randomUUID(), "POST_LIKE", postQ); // different target -> separate

        // FOLLOW, FRIEND_REQUEST, LIKE(P), LIKE(Q) = 4 notifications.
        mvc.perform(get("/v1/notifications").header("Authorization", bearer(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4));
    }

    @Test
    void readingThenANewEventStartsAFreshNotification() throws Exception {
        event(UUID.randomUUID(), "FOLLOW", null);
        mvc.perform(post("/v1/notifications/read").header("Authorization", bearer(recipient)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/notifications/unread-count").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.unread").value(0));

        // A new follow after reading is a new unread notification, not a coalesce into the read one.
        event(UUID.randomUUID(), "FOLLOW", null);
        mvc.perform(get("/v1/notifications/unread-count").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/v1/notifications").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.items.length()").value(2)); // one read, one unread
    }

    @Test
    void streamOpensAsServerSentEvents() throws Exception {
        mvc.perform(get("/v1/notifications/stream").header("Authorization", bearer(recipient)))
                .andExpect(request().asyncStarted());
    }

    @Test
    void rejectsInvalidEvent() throws Exception {
        mvc.perform(post("/v1/events").header("Authorization", bearer(recipient))
                        .contentType(APPLICATION_JSON).content("{\"type\":\"FOLLOW\"}")) // missing recipientId
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mvc.perform(post("/v1/events").contentType(APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + recipient + "\",\"type\":\"FOLLOW\"}"))
                .andExpect(status().is4xxClientError());
    }
}
