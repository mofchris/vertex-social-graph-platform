package com.vertex.graph.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 *
 * <p>Each row also records who caused it ({@code actorId}), why it exists ({@link OutboxKind}) and,
 * for well-formed events, the event id / type / recipient lifted out of the payload. None of that
 * affects relaying — the relay only reads topic, key and payload — it is what lets the system trace
 * show a visitor what really happened to their action without parsing JSON on every read.
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

    /** The user whose action produced this row; null for rows staged before the trace columns existed. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private OutboxKind kind;

    /** The payload's idempotency key; null for POISON rows (the payload isn't a valid event). */
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "event_type", updatable = false, length = 30)
    private String eventType;

    @Column(name = "recipient_id", updatable = false)
    private UUID recipientId;

    // Lets Spring Data skip the pre-insert SELECT for an assigned id, while still allowing the relay
    // to UPDATE a loaded row (mark it published): true only for a freshly constructed instance.
    @Transient
    private boolean isNew = true;

    protected OutboxEvent() {
    }

    private OutboxEvent(String topic, String msgKey, String payload, OutboxKind kind, UUID actorId,
                        UUID eventId, String eventType, UUID recipientId) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.msgKey = msgKey;
        this.payload = payload;
        this.kind = kind;
        this.actorId = actorId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.recipientId = recipientId;
        this.createdAt = Instant.now();
    }

    /** Stage a real event, keyed by recipient so each recipient's events stay ordered on one partition. */
    public static OutboxEvent of(String topic, SocialEvent event, String payload) {
        return new OutboxEvent(topic, event.recipientId().toString(), payload, OutboxKind.EVENT,
                event.actorId(), event.eventId(), event.type().name(), event.recipientId());
    }

    /**
     * Re-enqueue {@code original}'s exact payload and key as a new row. Same eventId, so a correct
     * consumer must recognise it as a duplicate; same key, so it lands on the same partition as the
     * original and therefore arrives after it.
     */
    public static OutboxEvent replayOf(OutboxEvent original) {
        return new OutboxEvent(original.topic, original.msgKey, original.payload, OutboxKind.REPLAY,
                original.actorId, original.eventId, original.eventType, original.recipientId);
    }

    /**
     * A deliberately malformed message, keyed by its actor (not a recipient) so the dead letter it
     * becomes downstream can be traced back to the visitor who asked for it.
     */
    public static OutboxEvent poison(String topic, UUID actorId, String payload) {
        return new OutboxEvent(topic, actorId.toString(), payload, OutboxKind.POISON,
                actorId, null, null, null);
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getActorId() {
        return actorId;
    }

    public OutboxKind getKind() {
        return kind;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getRecipientId() {
        return recipientId;
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
