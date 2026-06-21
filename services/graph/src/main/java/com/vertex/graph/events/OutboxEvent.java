package com.vertex.graph.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending Kafka message, staged in the same database transaction as the relationship change that
 * produced it — the transactional outbox pattern (see EDGE_CASES.md: "partial-failure multi-service
 * write"). Writing the edge and this row atomically is what closes the dual-write gap the old
 * after-commit forwarder left open: either both commit or neither does, so a committed edge can
 * never silently fail to announce itself. {@link OutboxRelay} later publishes the row to Kafka and
 * stamps {@code publishedAt}; an unpublished row is simply retried on the next tick.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String topic;

    /** Kafka partition key (recipient id) so each recipient's events stay ordered on one partition. */
    @Column(name = "msg_key", updatable = false)
    private String msgKey;

    @Column(nullable = false, updatable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the relay confirms the broker accepted the message. */
    @Column(name = "published_at")
    private Instant publishedAt;

    // Lets Spring Data skip the pre-insert SELECT for an assigned id, while still allowing the relay
    // to UPDATE a loaded row (mark it published): true only for a freshly constructed instance.
    @Transient
    private boolean isNew = true;

    protected OutboxEvent() {
    }

    public OutboxEvent(String topic, String msgKey, String payload) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.msgKey = msgKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getMsgKey() {
        return msgKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
