package com.vertex.recommend.web.dto;

import java.util.UUID;

/** A suggested user, with how many friends you have in common. */
public record Recommendation(
        UUID userId,
        int mutualFriends
) {
}
