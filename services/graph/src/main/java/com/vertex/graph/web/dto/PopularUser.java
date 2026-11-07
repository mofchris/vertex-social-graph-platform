package com.vertex.graph.web.dto;

import java.util.UUID;

/** A user and their real follower count (a live {@code COUNT} over the follow edges). */
public record PopularUser(
        UUID userId,
        long followers
) {
}
