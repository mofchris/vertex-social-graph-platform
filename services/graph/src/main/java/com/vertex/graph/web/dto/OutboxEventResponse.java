package com.vertex.graph.web.dto;

import com.vertex.graph.events.OutboxEvent;
import com.vertex.graph.events.OutboxKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row as the system trace shows it. Nulls are serialized on purpose: a null
 * {@code publishedAt} means "still waiting in the outbox", and a POISON row has no
 * {@code eventId}/{@code type}/{@code recipientId} because its payload isn't a valid event.
 *
 * @param outboxId the outbox row's own id (a replay gets a new one; its {@code eventId} is the original's)
 * @param type     the wire event type (FOLLOW, FRIEND_REQUEST, FRIEND_ACCEPT), null for POISON
 */
public record OutboxEventResponse(
        UUID outboxId,
        UUID eventId,
        String type,
        OutboxKind kind,
        UUID actorId,
        UUID recipientId,
        String topic,
        Instant createdAt,
        Instant publishedAt
) {
    public static OutboxEventResponse from(OutboxEvent e) {
        return new OutboxEventResponse(e.getId(), e.getEventId(), e.getEventType(), e.getKind(),
                e.getActorId(), e.getRecipientId(), e.getTopic(), e.getCreatedAt(), e.getPublishedAt());
    }
}
