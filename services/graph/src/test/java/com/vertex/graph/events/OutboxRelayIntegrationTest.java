package com.vertex.graph.events;

import com.vertex.graph.repository.OutboxRepository;
import com.vertex.graph.service.GraphService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the transactional outbox end-to-end against an in-process broker (no Docker): a graph write
 * stages an outbox row in its own transaction, and the relay ships it to Kafka. The headline
 * guarantee is that a committed edge always reaches the topic — no lost-event window — and the row
 * is then marked published.
 */
@SpringBootTest
@ActiveProfiles("kafka")
@EmbeddedKafka(partitions = 1, topics = OutboxEventWriter.TOPIC)
class OutboxRelayIntegrationTest {

    @Autowired private GraphService graph;
    @Autowired private OutboxRepository outbox;
    @Autowired private EmbeddedKafkaBroker broker;

    private Consumer<String, String> consumer;

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
        outbox.deleteAll();
    }

    @Test
    void aCommittedFollowReachesKafkaAndIsMarkedPublished() {
        subscribe(OutboxEventWriter.TOPIC);
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        graph.follow(actor, target); // commits the edge + stages the outbox row atomically

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, OutboxEventWriter.TOPIC, Duration.ofSeconds(20));

        assertThat(record.key()).isEqualTo(target.toString()); // partitioned by recipient
        assertThat(record.value()).contains("\"type\":\"FOLLOW\"");
        assertThat(record.value()).contains(target.toString());

        // The relay stamped the row published once the broker acked it.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                outbox.findAll().stream().allMatch(e -> e.getPublishedAt() != null));
    }

    private void subscribe(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("outbox-it", "true", broker);
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, topic);
    }
}
