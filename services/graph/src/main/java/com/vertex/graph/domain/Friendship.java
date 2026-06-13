package com.vertex.graph.domain;

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
 * One row per unordered pair of users (enforced by the unique {@code pairKey}), carrying a
 * directed request: {@code requesterId} asked {@code addresseeId} to be friends.
 */
@Entity
@Table(name = "friendships")
public class Friendship implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "addressee_id", nullable = false)
    private UUID addresseeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendshipStatus status;

    @Column(name = "pair_key", nullable = false, updatable = false, length = 80)
    private String pairKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected Friendship() {
    }

    public Friendship(UUID requesterId, UUID addresseeId) {
        this.id = UUID.randomUUID();
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = FriendshipStatus.PENDING;
        this.pairKey = pairKey(requesterId, addresseeId);
    }

    /** Canonical, order-independent key for a pair of users. */
    public static String pairKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) <= 0 ? sa + ":" + sb : sb + ":" + sa;
    }

    public void accept() {
        this.status = FriendshipStatus.ACCEPTED;
    }

    public boolean involves(UUID userId) {
        return requesterId.equals(userId) || addresseeId.equals(userId);
    }

    public UUID other(UUID userId) {
        return requesterId.equals(userId) ? addresseeId : requesterId;
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

    public UUID getRequesterId() {
        return requesterId;
    }

    public UUID getAddresseeId() {
        return addresseeId;
    }

    public FriendshipStatus getStatus() {
        return status;
    }

    public String getPairKey() {
        return pairKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
