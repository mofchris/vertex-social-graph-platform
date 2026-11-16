package com.vertex.notify.events;

import com.vertex.notify.domain.DeadLetter;
import com.vertex.notify.domain.Notification;
import com.vertex.notify.domain.ProcessedEvent;
import com.vertex.notify.repository.DeadLetterRepository;
import com.vertex.notify.repository.NotificationRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the Kafka consumer against an in-process broker (no Docker). The headline case is the
 * idempotent consumer: Kafka is at-least-once, so the same event can arrive twice — it must produce
 * exactly one notification (see EDGE_CASES.md) — and the ledger must record where each event came
 * from and how many duplicates it absorbed. Poison records must end up captured off the DLT.
 */
@SpringBootTest
@ActiveProfiles("kafka")
@EmbeddedKafka(partitions = 1, topics = {"social.events", "social.events.DLT"})
class SocialEventConsumerIntegrationTest {

    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private DeadLetterRepository deadLetters;

    private KafkaTemplate<String, String> producer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        notifications.deleteAll();
        processedEvents.deleteAll();
        deadLetters.deleteAll();
    }

    @Test
    void turnsASocialEventIntoANotificationAndRecordsWhereItCameFrom() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        RecordMetadata sent = send(recipient, event(eventId, "FOLLOW", actor, recipient));

        await().atMost(Duration.ofSeconds(20)).until(() -> !forRecipient(recipient).isEmpty());

        List<Notification> rows = forRecipient(recipient);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType().name()).isEqualTo("FOLLOW");
        assertThat(rows.get(0).getActorCount()).isEqualTo(1);
        assertThat(rows.get(0).getLatestActorId()).isEqualTo(actor);

        // The ledger knows exactly which record it consumed.
        ProcessedEvent ledger = processedEvents.findById(eventId).orElseThrow();
        assertThat(ledger.getKafkaPartition()).isEqualTo(sent.partition());
        assertThat(ledger.getKafkaOffset()).isEqualTo(sent.offset());
        assertThat(ledger.getEventType()).isEqualTo("FOLLOW");
        assertThat(ledger.getActorId()).isEqualTo(actor);
        assertThat(ledger.getRecipientId()).isEqualTo(recipient);
        assertThat(ledger.getProcessedAt()).isNotNull();
        assertThat(ledger.getDuplicatesIgnored()).isZero();
        assertThat(ledger.getLastDuplicateAt()).isNull();
    }

    @Test
    void redeliveredEventProducesExactlyOneNotificationAndIsCountedAsADuplicate() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String duplicate = event(eventId, "FOLLOW", actor, recipient);

        // The same event id, delivered twice (Kafka redelivery / consumer replay).
        RecordMetadata first = send(recipient, duplicate);
        send(recipient, duplicate);

        // A later sentinel on the single partition is processed strictly after both copies above,
        // so once it lands we know the duplicate has been fully handled — no sleeping, no flakiness.
        UUID sentinelRecipient = UUID.randomUUID();
        send(sentinelRecipient, event(UUID.randomUUID(), "FOLLOW", UUID.randomUUID(), sentinelRecipient));
        await().atMost(Duration.ofSeconds(20)).until(() -> !forRecipient(sentinelRecipient).isEmpty());

        List<Notification> rows = forRecipient(recipient);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorCount()).isEqualTo(1); // not double-counted

        ProcessedEvent ledger = processedEvents.findById(eventId).orElseThrow();
        assertThat(ledger.getDuplicatesIgnored()).isEqualTo(1);
        assertThat(ledger.getLastDuplicateAt()).isNotNull();
        assertThat(ledger.getKafkaOffset()).isEqualTo(first.offset()); // the first copy did the work
    }

    @Test
    void unparseableEventIsDeadLetteredAndCaptured() throws Exception {
        // A poison pill shaped like Graph's: a JSON object cut off mid-string. The consumer's parse
        // throws, and the dead-letter error handler republishes the record to social.events.DLT
        // instead of crash-looping the partition; the capture listener then records it.
        UUID key = UUID.randomUUID();
        String poison = "{\"vertexPoison\":true,\"actorId\":\"" + key + "\",\"note\":\"deliberately truncat";
        RecordMetadata sent = send(key, poison);

        await().atMost(Duration.ofSeconds(20)).until(() -> !capturedFor(key).isEmpty());

        DeadLetter dead = capturedFor(key).get(0);
        assertThat(dead.getPayload()).isEqualTo(poison);
        // The root cause, without Spring's "Listener method '...' threw exception" wrapper.
        assertThat(dead.getErrorMessage()).contains("end-of-input").doesNotStartWith("Listener method");
        assertThat(dead.getOriginalTopic()).isEqualTo("social.events");
        assertThat(dead.getOriginalPartition()).isEqualTo(sent.partition());
        assertThat(dead.getOriginalOffset()).isEqualTo(sent.offset());
        assertThat(dead.getReceivedAt()).isNotNull();

        // It never became a notification or a ledger entry.
        assertThat(notifications.findAll()).isEmpty();
        assertThat(processedEvents.findAll()).isEmpty();
    }

    @Test
    void parseableButIncompleteEventIsDeadLetteredToo() throws Exception {
        UUID key = UUID.randomUUID();
        send(key, "{\"type\":\"FOLLOW\"}"); // valid JSON, but no eventId / recipient / actor

        await().atMost(Duration.ofSeconds(20)).until(() -> !capturedFor(key).isEmpty());
        assertThat(capturedFor(key).get(0).getErrorMessage()).isNotBlank();
        assertThat(processedEvents.findAll()).isEmpty();
    }

    @Test
    void theSameDeadLetterDeliveredTwiceIsCapturedOnce() throws Exception {
        // Simulate the DLT copy being redelivered to the capture listener: identical original
        // coordinates. The unique constraint makes the second one a quiet no-op, not a loop.
        UUID key = UUID.randomUUID();
        long fakeOffset = 4_242L;
        sendToDlt(key, "first copy", fakeOffset);
        sendToDlt(key, "second copy", fakeOffset);
        UUID sentinel = UUID.randomUUID();
        sendToDlt(sentinel, "sentinel", fakeOffset + 1);

        await().atMost(Duration.ofSeconds(20)).until(() -> !capturedFor(sentinel).isEmpty());
        List<DeadLetter> captured = capturedFor(key);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getPayload()).isEqualTo("first copy");
        assertThat(captured.get(0).getErrorMessage()).isEqualTo("simulated failure");
        assertThat(captured.get(0).getOriginalOffset()).isEqualTo(fakeOffset);
    }

    private List<DeadLetter> capturedFor(UUID key) {
        return deadLetters.findRecentByKey(key.toString(), Limit.of(10));
    }

    private List<Notification> forRecipient(UUID recipient) {
        return notifications.findAll().stream().filter(n -> n.getRecipientId().equals(recipient)).toList();
    }

    private RecordMetadata send(UUID key, String json) throws Exception {
        return producer.send("social.events", key.toString(), json).get().getRecordMetadata();
    }

    /** A record on the DLT carrying the headers Spring's DeadLetterPublishingRecoverer would add. */
    private void sendToDlt(UUID key, String payload, long originalOffset) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>("social.events.DLT", key.toString(), payload);
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "simulated failure".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "social.events".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(0).array());
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(originalOffset).array());
        producer.send(record).get();
    }

    /** Minimal JSON matching {@link IncomingSocialEvent}; targetId is null for these event kinds. */
    private static String event(UUID eventId, String type, UUID actor, UUID recipient) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"type\":\"" + type + "\","
                + "\"actorId\":\"" + actor + "\","
                + "\"recipientId\":\"" + recipient + "\","
                + "\"targetId\":null,"
                + "\"occurredAt\":" + System.currentTimeMillis()
                + "}";
    }
}
