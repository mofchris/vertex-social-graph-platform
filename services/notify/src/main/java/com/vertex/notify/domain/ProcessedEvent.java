package com.vertex.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * The dedupe ledger for the at-least-once Kafka stream. Kafka can redeliver an event (consumer
 * crashes after the DB commit but before the offset commit, a rebalance replays the partition,
 * etc.), so the consumer records every {@code eventId} it has handled and ignores repeats. Written
 * in the same transaction as the notification it produced, so the two commit or roll back together
 * — that atomicity is what makes the consumer idempotent (see EDGE_CASES.md).
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
    }

    @PrePersist
    void onCreate() {
        this.processedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    /** Always an insert — a processed event is never updated — so skip JPA's pre-insert SELECT. */
    @Override
    public boolean isNew() {
        return true;
    }
}
