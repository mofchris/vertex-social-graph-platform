package com.vertex.recommend.web.dto;

import java.util.UUID;

/** A suggested user, how many friends you have in common, and why they're suggested. */
public record Recommendation(
        UUID userId,
        int mutualFriends,
        RecommendationReason reason
) {
    public static Recommendation mutualFriends(UUID userId, int mutualFriends) {
        return new Recommendation(userId, mutualFriends, RecommendationReason.MUTUAL_FRIENDS);
    }

    public static Recommendation popular(UUID userId) {
        return new Recommendation(userId, 0, RecommendationReason.POPULAR);
    }
}
