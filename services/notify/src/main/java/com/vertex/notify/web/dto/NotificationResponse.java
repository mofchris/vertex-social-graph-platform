package com.vertex.notify.web.dto;

import com.vertex.notify.domain.Notification;
import com.vertex.notify.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        UUID targetId,
        long actorCount,
        UUID latestActorId,
        boolean read,
        Instant updatedAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getType(), n.getTargetId(), n.getActorCount(),
                n.getLatestActorId(), n.isRead(), n.getUpdatedAt());
    }
}
