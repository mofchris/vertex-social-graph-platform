package com.vertex.recommend.web.dto;

/** Why a user is suggested, so the UI can say so honestly. */
public enum RecommendationReason {
    /** Friend of one or more of your friends; see {@code mutualFriends}. */
    MUTUAL_FRIENDS,
    /** Cold-start fallback: among the most-followed users, with no mutual friends. */
    POPULAR
}
