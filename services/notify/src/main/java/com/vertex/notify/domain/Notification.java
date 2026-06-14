package com.vertex.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A coalesced notification: one row stands for every event of the same {@code type} about
 * the same {@code targetId} for a {@code recipientId} while it stays unread. Each new event
 * bumps {@code actorCount} and {@code latestActorId} instead of creating a new row.
 */
@Entity
@Table(name = "notifications")
public class Notification implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "coalesce_key", nullable = false, length = 80, updatable = false)
    private String coalesceKey;

    @Column(name = "actor_count", nullable = false)
    private long actorCount;

    @Column(name = "latest_actor_id", nullable = false)
    private UUID latestActorId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected Notification() {
    }

    public Notification(UUID recipientId, NotificationType type, UUID targetId, UUID actorId) {
        this.id = UUID.randomUUID();
        this.recipientId = recipientId;
        this.type = type;
        this.targetId = targetId;
        this.coalesceKey = coalesceKey(type, targetId);
        this.actorCount = 1;
        this.latestActorId = actorId;
        this.read = false;
    }

    /** Grouping key: events with the same key (and recipient) coalesce while unread. */
    public static String coalesceKey(NotificationType type, UUID targetId) {
        return type.name() + ":" + (targetId != null ? targetId : "self");
    }

    /** Fold another event into this notification. */
    public void addActor(UUID actorId) {
        this.actorCount++;
        this.latestActorId = actorId;
    }

    public void markRead() {
        this.read = true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public NotificationType getType() {
        return type;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getCoalesceKey() {
        return coalesceKey;
    }

    public long getActorCount() {
        return actorCount;
    }

    public UUID getLatestActorId() {
        return latestActorId;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
