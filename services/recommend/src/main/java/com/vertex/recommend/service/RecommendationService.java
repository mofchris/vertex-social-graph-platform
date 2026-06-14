package com.vertex.recommend.service;

import com.vertex.recommend.client.GraphClient;
import com.vertex.recommend.web.dto.Recommendation;
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
 */
@Service
public class RecommendationService {

    private static final int MAX_LIMIT = 50;

    private final GraphClient graph;
    private final int maxSeedFriends;
    private final int maxSamplePerFriend;

    public RecommendationService(GraphClient graph,
                                 @Value("${app.recommend.max-seed-friends:50}") int maxSeedFriends,
                                 @Value("${app.recommend.max-sample-per-friend:100}") int maxSamplePerFriend) {
        this.graph = graph;
        this.maxSeedFriends = maxSeedFriends;
        this.maxSamplePerFriend = maxSamplePerFriend;
    }

    public List<Recommendation> recommend(UUID userId, String authorizationHeader, int limit) {
        int max = Math.clamp(limit, 1, MAX_LIMIT);

        List<UUID> myFriends = graph.allFriends(userId, authorizationHeader);
        if (myFriends.isEmpty()) {
            return List.of(); // cold start: nothing to traverse from
        }
        Set<UUID> friendSet = new HashSet<>(myFriends);

        // Count mutual friends across friends-of-friends.
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
        for (Map.Entry<UUID, Integer> entry : ranked) {
            if (result.size() >= max) {
                break;
            }
            if (graph.isEligible(entry.getKey(), authorizationHeader)) {
                result.add(new Recommendation(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }
}
