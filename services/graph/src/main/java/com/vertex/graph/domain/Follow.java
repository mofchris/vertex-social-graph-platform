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

/** A directed follow edge: {@code followerId} follows {@code followeeId}. */
@Entity
@Table(name = "follows")
public class Follow implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "follower_id", nullable = false, updatable = false)
    private UUID followerId;

    @Column(name = "followee_id", nullable = false, updatable = false)
    private UUID followeeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Follow() {
    }

    public Follow(UUID followerId, UUID followeeId) {
        this.id = UUID.randomUUID();
        this.followerId = followerId;
        this.followeeId = followeeId;
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

    public UUID getFollowerId() {
        return followerId;
    }

    public UUID getFolloweeId() {
        return followeeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
