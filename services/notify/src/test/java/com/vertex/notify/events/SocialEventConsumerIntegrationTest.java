package com.vertex.notify.events;

import com.vertex.notify.domain.Notification;
import com.vertex.notify.repository.NotificationRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

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
 * exactly one notification (see EDGE_CASES.md).
 */
@SpringBootTest
@ActiveProfiles("kafka")
@EmbeddedKafka(partitions = 1, topics = {"social.events", "social.events.DLT"})
class SocialEventConsumerIntegrationTest {

    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;

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
    }

    @Test
    void turnsASocialEventIntoANotification() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        send(recipient, event(eventId, "FOLLOW", actor, recipient));

        await().atMost(Duration.ofSeconds(20)).until(() -> !forRecipient(recipient).isEmpty());

        List<Notification> rows = forRecipient(recipient);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType().name()).isEqualTo("FOLLOW");
        assertThat(rows.get(0).getActorCount()).isEqualTo(1);
        assertThat(rows.get(0).getLatestActorId()).isEqualTo(actor);
    }

    @Test
    void redeliveredEventProducesExactlyOneNotification() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String duplicate = event(eventId, "FOLLOW", actor, recipient);

        // The same event id, delivered twice (Kafka redelivery / consumer replay).
        send(recipient, duplicate);
        send(recipient, duplicate);

        // A later sentinel on the single partition is processed strictly after both copies above,
        // so once it lands we know the duplicate has been fully handled — no sleeping, no flakiness.
        UUID sentinelRecipient = UUID.randomUUID();
        send(sentinelRecipient, event(UUID.randomUUID(), "FOLLOW", UUID.randomUUID(), sentinelRecipient));
        await().atMost(Duration.ofSeconds(20)).until(() -> !forRecipient(sentinelRecipient).isEmpty());

        List<Notification> rows = forRecipient(recipient);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorCount()).isEqualTo(1); // not double-counted
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void unparseableEventIsRoutedToTheDeadLetterTopic() {
        Consumer<String, String> dlt = dltConsumer();

        // A poison-pill: not JSON at all. The consumer's parse throws, and the dead-letter error
        // handler republishes the record to social.events.DLT instead of crash-looping the partition.
        send(UUID.randomUUID(), "this is not json");

        ConsumerRecord<String, String> dead =
                KafkaTestUtils.getSingleRecord(dlt, "social.events.DLT", Duration.ofSeconds(20));
        assertThat(dead.value()).isEqualTo("this is not json");

        // It never became a notification, and the partition kept moving (it didn't block).
        assertThat(notifications.findAll()).isEmpty();
        dlt.close();
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-it", "true", broker);
        Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, "social.events.DLT");
        return consumer;
    }

    private List<Notification> forRecipient(UUID recipient) {
        return notifications.findAll().stream().filter(n -> n.getRecipientId().equals(recipient)).toList();
    }

    private void send(UUID key, String json) {
        producer.send("social.events", key.toString(), json);
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
