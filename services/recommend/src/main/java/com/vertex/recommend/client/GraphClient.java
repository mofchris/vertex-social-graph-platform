package com.vertex.recommend.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads the friend graph from the Graph service for friends-of-friends recommendations, and
 * the popular-users list used as the cold-start fallback. The user's token is forwarded
 * (token propagation). Calls degrade gracefully: empty lists on error, and eligibility fails
 * closed (an unverifiable candidate is not recommended).
 */
@Component
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

    private final RestClient restClient;

    public GraphClient(@Value("${app.graph.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** All of a user's friends (pages through). Used for the requesting user's own friends. */
    public List<UUID> allFriends(UUID userId, String authorizationHeader) {
        List<UUID> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            String uri = (cursor == null) ? "/v1/friends/{id}?limit=100"
                    : "/v1/friends/{id}?limit=100&cursor=" + cursor;
            UserPage page = getPage(uri, userId, authorizationHeader);
            if (page == null || page.items() == null) {
                break;
            }
            all.addAll(page.items());
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        return all;
    }

    /** Up to {@code cap} friends of a user (single page) — supernode-safe sampling for FoF. */
    public List<UUID> sampleFriends(UUID userId, String authorizationHeader, int cap) {
        int limit = Math.clamp(cap, 1, 100);
        UserPage page = getPage("/v1/friends/{id}?limit=" + limit, userId, authorizationHeader);
        return (page == null || page.items() == null) ? List.of() : page.items();
    }

    /** A candidate is eligible if not already a friend and not in a block relationship. */
    public boolean isEligible(UUID candidate, String authorizationHeader) {
        try {
            Relationship rel = restClient.get()
                    .uri("/v1/relationship/{id}", candidate)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(Relationship.class);
            if (rel == null) {
                return false;
            }
            return !rel.blocking() && !rel.blockedBy() && !"FRIENDS".equals(rel.friendStatus());
        } catch (RestClientException e) {
            log.warn("graph relationship failed for {}: {}", candidate, e.getMessage());
            return false; // fail closed
        }
    }

    /**
     * Stricter eligibility for popular (non-FoF) suggestions: no block either way, not already
     * friends or with a friend request in flight, and not already followed. A popular user the
     * caller already follows isn't a useful "you may know". Fails closed.
     */
    public boolean isUnconnected(UUID candidate, String authorizationHeader) {
        try {
            Relationship rel = restClient.get()
                    .uri("/v1/relationship/{id}", candidate)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(Relationship.class);
            if (rel == null) {
                return false;
            }
            return !rel.blocking() && !rel.blockedBy() && !rel.following()
                    && (rel.friendStatus() == null || "NONE".equals(rel.friendStatus()));
        } catch (RestClientException e) {
            log.warn("graph relationship failed for {}: {}", candidate, e.getMessage());
            return false; // fail closed
        }
    }

    /**
     * The most-followed users, most first, as seen by the bearer (Graph already excludes the
     * caller and anyone in a block relationship with them). Unlike the other reads this
     * throws on failure, so the caller decides how to degrade.
     */
    public List<UUID> popular(String authorizationHeader, int limit) {
        PopularPage page = restClient.get()
                .uri("/v1/popular?limit={limit}", Math.clamp(limit, 1, 100))
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve()
                .body(PopularPage.class);
        if (page == null || page.items() == null) {
            return List.of();
        }
        return page.items().stream()
                .map(PopularUser::userId)
                .filter(Objects::nonNull)
                .toList();
    }

    private UserPage getPage(String uri, UUID userId, String authorizationHeader) {
        try {
            return restClient.get()
                    .uri(uri, userId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(UserPage.class);
        } catch (RestClientException e) {
            log.warn("graph friends failed for {}: {}", userId, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserPage(List<UUID> items, String nextCursor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Relationship(String friendStatus, boolean following, boolean blocking, boolean blockedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PopularPage(List<PopularUser> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PopularUser(UUID userId, long followers) {
    }
}
