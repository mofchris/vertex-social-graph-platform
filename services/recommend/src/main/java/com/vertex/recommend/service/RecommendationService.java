package com.vertex.recommend.service;

import com.vertex.recommend.client.GraphClient;
import com.vertex.recommend.web.dto.Recommendation;
import com.vertex.recommend.web.dto.RecommendationReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "People you may know" via friends-of-friends. Candidates are ranked by mutual-friend
 * count, then filtered for eligibility (not already a friend, not in a block relationship).
 *
 * <p>Supernode safety: only the first {@code maxSeedFriends} of the user's friends are
 * expanded, and only {@code maxSamplePerFriend} friends of each are sampled — so a
 * high-degree node can't blow up the traversal (see EDGE_CASES.md: celebrity/supernode).
 *
 * <p>Cold start: a new user has no friends to traverse from, so when friends-of-friends
 * yields fewer than {@code limit}, the rest is topped up from Graph's most-followed users.
 * Each suggestion carries its {@link RecommendationReason}. The fallback is best-effort: if
 * it fails, the friends-of-friends results are returned on their own.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private static final int MAX_LIMIT = 50;

    private final GraphClient graph;
    private final int maxSeedFriends;
    private final int maxSamplePerFriend;
    private final int popularFetchLimit;

    public RecommendationService(GraphClient graph,
                                 @Value("${app.recommend.max-seed-friends:50}") int maxSeedFriends,
                                 @Value("${app.recommend.max-sample-per-friend:100}") int maxSamplePerFriend,
                                 @Value("${app.recommend.popular-fetch-limit:50}") int popularFetchLimit) {
        this.graph = graph;
        this.maxSeedFriends = maxSeedFriends;
        this.maxSamplePerFriend = maxSamplePerFriend;
        this.popularFetchLimit = popularFetchLimit;
    }

    public List<Recommendation> recommend(UUID userId, String authorizationHeader, int limit) {
        int max = Math.clamp(limit, 1, MAX_LIMIT);

        List<UUID> myFriends = graph.allFriends(userId, authorizationHeader);
        Set<UUID> friendSet = new HashSet<>(myFriends);

        // Count mutual friends across friends-of-friends (nothing to traverse on cold start).
        Map<UUID, Integer> mutualCount = new HashMap<>();
        int expanded = 0;
        for (UUID friend : myFriends) {
            if (expanded++ >= maxSeedFriends) {
                break;
            }
            for (UUID candidate : graph.sampleFriends(friend, authorizationHeader, maxSamplePerFriend)) {
                if (!candidate.equals(userId) && !friendSet.contains(candidate)) {
                    mutualCount.merge(candidate, 1, Integer::sum);
                }
            }
        }

        // Rank by mutual count, then confirm eligibility for the top candidates only.
        List<Map.Entry<UUID, Integer>> ranked = new ArrayList<>(mutualCount.entrySet());
        ranked.sort(Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue).reversed());

        List<Recommendation> result = new ArrayList<>();
        Set<UUID> rejected = new HashSet<>();
        for (Map.Entry<UUID, Integer> entry : ranked) {
            if (result.size() >= max) {
                break;
            }
            if (graph.isEligible(entry.getKey(), authorizationHeader)) {
                result.add(Recommendation.mutualFriends(entry.getKey(), entry.getValue()));
            } else {
                rejected.add(entry.getKey());
            }
        }

        if (result.size() < max) {
            topUpWithPopular(userId, authorizationHeader, max, friendSet, rejected, result);
        }
        return result;
    }

    /**
     * Fills {@code result} up to {@code max} with popular users the caller has no connection
     * to: not the caller, not already suggested, not a friend, not followed, no pending
     * friend request, no block. Never throws — on any Graph failure it logs and leaves
     * {@code result} as the friends-of-friends answer.
     */
    private void topUpWithPopular(UUID userId, String authorizationHeader, int max,
                                  Set<UUID> friendSet, Set<UUID> rejected, List<Recommendation> result) {
        Set<UUID> suggested = new HashSet<>();
        result.forEach(r -> suggested.add(r.userId()));
        try {
            for (UUID candidate : graph.popular(authorizationHeader, popularFetchLimit)) {
                if (result.size() >= max) {
                    break;
                }
                // Cheap local exclusions first; only survivors cost a relationship call.
                if (candidate.equals(userId) || friendSet.contains(candidate)
                        || suggested.contains(candidate) || rejected.contains(candidate)) {
                    continue;
                }
                if (graph.isUnconnected(candidate, authorizationHeader)) {
                    result.add(Recommendation.popular(candidate));
                    suggested.add(candidate);
                }
            }
        } catch (RuntimeException e) {
            // Keep whatever we have (FoF plus any popular already verified).
            log.warn("popular fallback failed, returning {} result(s) without it: {}",
                    result.size(), e.getMessage());
        }
    }
}
