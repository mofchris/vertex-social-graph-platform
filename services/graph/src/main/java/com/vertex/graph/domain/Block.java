package com.vertex.graph.domain;

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

/** A directed block: {@code blockerId} has blocked {@code blockedId}. */
@Entity
@Table(name = "blocks")
public class Block implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "blocker_id", nullable = false, updatable = false)
    private UUID blockerId;

    @Column(name = "blocked_id", nullable = false, updatable = false)
    private UUID blockedId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Block() {
    }

    public Block(UUID blockerId, UUID blockedId) {
        this.id = UUID.randomUUID();
        this.blockerId = blockerId;
        this.blockedId = blockedId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
