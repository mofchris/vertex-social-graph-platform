package com.vertex.graph.web.dto;

import java.util.UUID;

/** The authenticated viewer's relationship to {@code userId}. */
public record RelationshipResponse(
        UUID userId,
        boolean following,
        boolean followedBy,
        FriendStatus friendStatus,
        boolean blocking,
        boolean blockedBy
) {
}
