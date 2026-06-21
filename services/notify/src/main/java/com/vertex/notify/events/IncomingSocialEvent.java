package com.vertex.notify.events;

import java.util.UUID;

/**
 * The on-the-wire shape of a social event from Graph. Decoupled by design: Notify keeps its own
 * copy of the contract rather than sharing a module, and {@code type} stays a free String so an
 * event kind Notify doesn't recognise is dropped, not a deserialization failure. Mirrors
 * {@code com.vertex.graph.events.SocialEvent}.
 */
public record IncomingSocialEvent(
        UUID eventId,
        String type,
        UUID actorId,
        UUID recipientId,
        UUID targetId,
        long occurredAt) {
}
