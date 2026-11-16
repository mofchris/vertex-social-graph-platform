package com.vertex.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * The dedupe ledger for the at-least-once Kafka stream. Kafka can redeliver an event (consumer
 * crashes after the DB commit but before the offset commit, a rebalance replays the partition,
 * etc.), so the consumer records every {@code eventId} it has handled and ignores repeats. Written
 * in the same transaction as the notification it produced, so the two commit or roll back together
 * — that atomicity is what makes the consumer idempotent (see EDGE_CASES.md).
 *
 * <p>Each entry also keeps where the event was read from (partition/offset), who it concerns, and how
 * many redeliveries were ignored. That turns the ledger into evidence the system trace can show: "Notify
 * consumed it from partition P offset O, and ignored N duplicates".
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "recipient_id", updatable = false)
    private UUID recipientId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "event_type", updatable = false, length = 30)
    private String eventType;

    @Column(name = "kafka_partition", updatable = false)
    private Integer kafkaPartition;

    @Column(name = "kafka_offset", updatable = false)
    private Long kafkaOffset;

    /** Left to the column default on insert; only ever changed by the repository's atomic SQL increment. */
    @Column(name = "duplicates_ignored", nullable = false, insertable = false)
    private int duplicatesIgnored;

    @Column(name = "last_duplicate_at", insertable = false)
    private Instant lastDuplicateAt;

    // Lets Spring Data skip the pre-insert SELECT for an assigned id, while still treating a loaded
    // row as existing (so repository deletes work): true only for a freshly constructed instance.
    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType, UUID actorId, UUID recipientId,
                          Integer kafkaPartition, Long kafkaOffset) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.recipientId = recipientId;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
    }

    @PrePersist
    void onCreate() {
        this.processedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getEventType() {
        return eventType;
    }

    public Integer getKafkaPartition() {
        return kafkaPartition;
    }

    public Long getKafkaOffset() {
        return kafkaOffset;
    }

    public int getDuplicatesIgnored() {
        return duplicatesIgnored;
    }

    public Instant getLastDuplicateAt() {
        return lastDuplicateAt;
    }

    /** True when {@code userId} is one of the two people this event is about. */
    public boolean concerns(UUID userId) {
        return userId.equals(actorId) || userId.equals(recipientId);
    }
}
