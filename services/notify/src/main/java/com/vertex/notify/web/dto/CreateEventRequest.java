package com.vertex.notify.web.dto;

import com.vertex.notify.domain.NotificationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Reports a social event so it can be turned into (or coalesced into) a notification for
 * {@code recipientId}. The acting user is the authenticated caller. {@code targetId} is the
 * thing the event is about (e.g. a post id for a like); null means "about you".
 */
public record CreateEventRequest(
        @NotNull UUID recipientId,
        @NotNull NotificationType type,
        UUID targetId
) {
}
