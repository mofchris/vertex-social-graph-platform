package com.vertex.graph.events;

import com.jayway.jsonpath.JsonPath;
import com.vertex.graph.config.JwtProperties;
import com.vertex.graph.repository.FollowRepository;
import com.vertex.graph.repository.FriendshipRepository;
import com.vertex.graph.repository.OutboxRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The system trace end-to-end against an in-process broker (no Docker): a visitor's action shows up
 * in {@code /v1/events/mine} and gets published; a replay re-sends the identical event id through the
 * outbox to Kafka; a poison row reaches the topic as a malformed message keyed by the caller.
 *
 * <p>Uses its own in-memory database so its relay can't race the relay of another cached kafka-profile
 * context (which would publish these rows to a different embedded broker).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:graph-trace;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("kafka")
@EmbeddedKafka(partitions = 1, topics = OutboxEventWriter.TOPIC)
class EventTraceIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;
    @Autowired private OutboxRepository outbox;
    @Autowired private FollowRepository follows;
    @Autowired private FriendshipRepository friendships;
    @Autowired private EmbeddedKafkaBroker broker;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("trace-it-" + UUID.randomUUID(), "true", broker);
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, OutboxEventWriter.TOPIC);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        outbox.deleteAll();
        follows.deleteAll();
        friendships.deleteAll();
    }

    @Test
    void mineListsTheCallersOwnRowsNewestFirstAndTheyGetPublished() throws Exception {
        mvc.perform(post("/v1/follow/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/v1/friends/" + carol + "/request").header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/events/mine").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("FRIEND_REQUEST"))
                .andExpect(jsonPath("$.items[0].kind").value("EVENT"))
                .andExpect(jsonPath("$.items[0].actorId").value(alice.toString()))
                .andExpect(jsonPath("$.items[0].recipientId").value(carol.toString()))
                .andExpect(jsonPath("$.items[0].topic").value("social.events"))
                .andExpect(jsonPath("$.items[0].eventId").isNotEmpty())
                .andExpect(jsonPath("$.items[0].outboxId").isNotEmpty())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[1].type").value("FOLLOW"))
                .andExpect(jsonPath("$.items[1].recipientId").value(bob.toString()));

        // The relay ships both; the trace then shows a publishedAt for each.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/v1/events/mine").header("Authorization", bearer(alice)))
                        .andExpect(jsonPath("$.items[0].publishedAt").value(not(nullValue())))
                        .andExpect(jsonPath("$.items[1].publishedAt").value(not(nullValue()))));

        // Only the actor sees them: Bob (the recipient) caused nothing.
        mvc.perform(get("/v1/events/mine").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.items.length()").value(0));
        // limit is clamped to 1..50.
        mvc.perform(get("/v1/events/mine?limit=0").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void replayReEnqueuesTheIdenticalEventAndOnlyForItsActor() throws Exception {
        mvc.perform(post("/v1/follow/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        String mine = mvc.perform(get("/v1/events/mine").header("Authorization", bearer(alice)))
                .andReturn().getResponse().getContentAsString();
        String eventId = JsonPath.read(mine, "$.items[0].eventId");
        String originalOutboxId = JsonPath.read(mine, "$.items[0].outboxId");

        // Someone else can't replay Alice's event, and an unknown id is simply not found.
        mvc.perform(post("/v1/events/" + eventId + "/replay").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(post("/v1/events/" + UUID.randomUUID() + "/replay").header("Authorization", bearer(alice)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/v1/events/" + eventId + "/replay").header("Authorization", bearer(alice)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("REPLAY"))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.type").value("FOLLOW"))
                .andExpect(jsonPath("$.actorId").value(alice.toString()))
                .andExpect(jsonPath("$.recipientId").value(bob.toString()))
                .andExpect(jsonPath("$.outboxId").value(not(originalOutboxId)));

        // Byte-identical payload and key: to the consumer it is indistinguishable from a redelivery.
        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(OutboxEvent::getPayload).containsOnly(rows.get(0).getPayload());
        assertThat(rows).extracting(OutboxEvent::getMsgKey).containsOnly(bob.toString());
        assertThat(rows).extracting(OutboxEvent::getKind)
                .containsExactlyInAnyOrder(OutboxKind.EVENT, OutboxKind.REPLAY);

        // Both copies really travel outbox -> Kafka.
        await().atMost(Duration.ofSeconds(20)).until(() ->
                records().stream().filter(r -> r.value().contains(eventId)).count() == 2);
    }

    @Test
    void poisonEnqueuesAMalformedMessageKeyedByTheCaller() throws Exception {
        mvc.perform(post("/v1/events/poison").header("Authorization", bearer(alice)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("POISON"))
                .andExpect(jsonPath("$.actorId").value(alice.toString()))
                .andExpect(jsonPath("$.topic").value("social.events"))
                .andExpect(jsonPath("$.eventId").value(nullValue()))
                .andExpect(jsonPath("$.type").value(nullValue()))
                .andExpect(jsonPath("$.recipientId").value(nullValue()));

        await().atMost(Duration.ofSeconds(20)).until(() ->
                records().stream().anyMatch(r -> alice.toString().equals(r.key())));
        ConsumerRecord<String, String> poison = records().stream()
                .filter(r -> alice.toString().equals(r.key())).findFirst().orElseThrow();
        assertThat(poison.value()).contains("\"vertexPoison\":true");
        assertThat(poison.value()).doesNotEndWith("}"); // truncated: not a parseable object

        mvc.perform(get("/v1/events/mine").header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.items[0].kind").value("POISON"));
    }

    /** Everything received so far on the topic (accumulates across polls). */
    private List<ConsumerRecord<String, String>> records() {
        KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).forEach(seen::add);
        return seen;
    }

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .issuer(jwtProps.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
        return "Bearer " + token;
    }
}
