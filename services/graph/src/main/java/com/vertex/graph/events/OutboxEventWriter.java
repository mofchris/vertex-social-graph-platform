package com.vertex.graph.events;

import com.vertex.graph.repository.OutboxRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stages each in-process {@link SocialEvent} as an {@link OutboxEvent} row — only under the {@code
 * kafka} profile, so the default "just a JDK, no Docker" build has no broker dependency and the
 * in-process event simply lands nowhere.
 *
 * <p>It listens {@link TransactionPhase#BEFORE_COMMIT}, so the staged row is written inside the very
 * transaction that changed the edge. Edge and outbox row therefore commit or roll back together:
 * the transactional outbox pattern. A separate {@link OutboxRelay} drains the table to Kafka after
 * the commit, which is what lets us promise at-least-once delivery without a lost-event window.
 *
 * <p>The wire value is a JSON string serialized here, keeping us decoupled from any serializer
 * library's Jackson version.
 */
@Component
@Profile("kafka")
public class OutboxEventWriter {

    /** Partitioned by recipient so each recipient's events stay ordered (e.g. unfollow→refollow). */
    public static final String TOPIC = "social.events";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxRepository outbox;

    public OutboxEventWriter(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void stage(SocialEvent event) {
        String payload = JSON.writeValueAsString(event);
        outbox.save(new OutboxEvent(TOPIC, event.recipientId().toString(), payload));
    }
}
