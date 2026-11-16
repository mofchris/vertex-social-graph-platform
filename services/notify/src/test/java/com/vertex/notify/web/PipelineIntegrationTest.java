package com.vertex.notify.web;

import com.vertex.notify.config.JwtProperties;
import com.vertex.notify.domain.DeadLetter;
import com.vertex.notify.domain.NotificationType;
import com.vertex.notify.repository.DeadLetterRepository;
import com.vertex.notify.repository.NotificationRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import com.vertex.notify.service.NotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pipeline trace API against embedded H2: who may see what, and that the numbers are the real
 * ledger state. Ledger rows are written through the same {@link NotificationService#ingestEvent} the
 * Kafka consumer calls (the Kafka hop itself is covered by {@code SocialEventConsumerIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PipelineIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;
    @Autowired private NotificationService service;
    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private DeadLetterRepository deadLetters;

    private final UUID actor = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void reset() {
        notifications.deleteAll();
        processedEvents.deleteAll();
        deadLetters.deleteAll();
    }

    @Test
    void eventTraceIsVisibleOnlyToItsActorAndRecipient() throws Exception {
        UUID eventId = UUID.randomUUID();
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 0, 42L)).isTrue();

        for (UUID allowed : new UUID[] {actor, recipient}) {
            mvc.perform(get("/v1/pipeline/events/" + eventId).header("Authorization", bearer(allowed)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                    .andExpect(jsonPath("$.type").value("FOLLOW"))
                    .andExpect(jsonPath("$.partition").value(0))
                    .andExpect(jsonPath("$.offset").value(42))
                    .andExpect(jsonPath("$.processedAt").value(not(nullValue())))
                    .andExpect(jsonPath("$.duplicatesIgnored").value(0))
                    .andExpect(jsonPath("$.lastDuplicateAt").value(nullValue()));
        }

        // A third party gets the same 404 as "not processed yet" — ids can't be probed.
        mvc.perform(get("/v1/pipeline/events/" + eventId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/v1/pipeline/events/" + UUID.randomUUID()).header("Authorization", bearer(actor)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/pipeline/events/" + eventId)).andExpect(status().is4xxClientError());
    }

    @Test
    void aDuplicateIsCountedButNeverNotifiesTwice() throws Exception {
        UUID eventId = UUID.randomUUID();
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 0, 7L)).isTrue();
        // Redelivered twice more (e.g. a replay from Graph, then a consumer restart).
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 0, 8L)).isFalse();
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 0, 9L)).isFalse();

        mvc.perform(get("/v1/pipeline/events/" + eventId).header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(7)) // where it was first consumed
                .andExpect(jsonPath("$.duplicatesIgnored").value(2))
                .andExpect(jsonPath("$.lastDuplicateAt").value(not(nullValue())));

        // Exactly one notification, with one actor — the duplicates changed nothing user-visible.
        mvc.perform(get("/v1/notifications").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].actorCount").value(1));
    }

    @Test
    void deadLettersAreScopedToTheCallersKeyNewestFirst() throws Exception {
        deadLetters.save(new DeadLetter(actor.toString(), "{\"vertexPoison\":true", "Unexpected end-of-input",
                "social.events", 0, 10L));
        Thread.sleep(5); // distinct receivedAt for a deterministic order
        deadLetters.save(new DeadLetter(actor.toString(), "x".repeat(2_000), "second", "social.events", 0, 11L));
        deadLetters.save(new DeadLetter(stranger.toString(), "not yours", "other", "social.events", 0, 12L));

        mvc.perform(get("/v1/pipeline/dead-letters/mine").header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].error").value("second"))
                .andExpect(jsonPath("$.items[0].payloadPreview").value(hasLength(DeadLetter.MAX_PAYLOAD)))
                .andExpect(jsonPath("$.items[0].originalOffset").value(11))
                .andExpect(jsonPath("$.items[1].error").value("Unexpected end-of-input"))
                .andExpect(jsonPath("$.items[1].payloadPreview").value("{\"vertexPoison\":true"))
                .andExpect(jsonPath("$.items[1].originalTopic").value("social.events"))
                .andExpect(jsonPath("$.items[1].originalPartition").value(0))
                .andExpect(jsonPath("$.items[1].originalOffset").value(10))
                .andExpect(jsonPath("$.items[1].id").value(not(nullValue())))
                .andExpect(jsonPath("$.items[1].receivedAt").value(not(nullValue())));

        // limit is clamped to 1..50.
        mvc.perform(get("/v1/pipeline/dead-letters/mine?limit=0").header("Authorization", bearer(actor)))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/v1/pipeline/dead-letters/mine").header("Authorization", bearer(recipient)))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void statsAreRealSystemWideCounts() throws Exception {
        UUID first = UUID.randomUUID();
        service.ingestEvent(first, recipient, NotificationType.FOLLOW, null, actor, 0, 1L);
        service.ingestEvent(UUID.randomUUID(), actor, NotificationType.FRIEND_REQUEST, null, recipient, 0, 2L);
        service.ingestEvent(first, recipient, NotificationType.FOLLOW, null, actor, 0, 3L); // duplicate
        deadLetters.save(new DeadLetter(actor.toString(), "bad", "boom", "social.events", 0, 4L));

        // Not scoped to the caller: a stranger sees the same totals.
        mvc.perform(get("/v1/pipeline/stats").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedEvents").value(2))
                .andExpect(jsonPath("$.duplicatesIgnored").value(1))
                .andExpect(jsonPath("$.deadLetters").value(1));
        mvc.perform(get("/v1/pipeline/stats")).andExpect(status().is4xxClientError());
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
