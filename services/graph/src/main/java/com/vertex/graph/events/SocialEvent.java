package com.vertex.graph.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A relationship change worth telling someone about. Published in-process by {@link
 * com.vertex.graph.service.GraphService}; under the {@code kafka} profile it is staged to the
 * transactional outbox before commit and relayed to Kafka after, so we never announce an edge that
 * rolled back and never lose one that committed.
 *
 * <p>{@code eventId} is the idempotency key: Kafka is at-least-once, so a consumer may see the same
 * event more than once and must dedupe on this id (see EDGE_CASES.md). {@code occurredAt} is epoch
 * millis to keep the wire format trivial and Jackson-version-agnostic.
 *
 * @param recipientId who should be notified
 * @param actorId     who caused the event
 * @param targetId    what the event is about (nullable; null means "about the recipient")
 */
public record SocialEvent(
        UUID eventId,
        SocialEventType type,
        UUID actorId,
        UUID recipientId,
        UUID targetId,
        long occurredAt) {

    /** Mint a fresh event with a new idempotency key, stamped now. */
    public static SocialEvent of(SocialEventType type, UUID actorId, UUID recipientId, UUID targetId) {
        return new SocialEvent(UUID.randomUUID(), type, actorId, recipientId, targetId, Instant.now().toEpochMilli());
    }
}
