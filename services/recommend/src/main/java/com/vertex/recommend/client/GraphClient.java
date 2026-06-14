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
import java.util.UUID;

/**
 * Reads the friend graph from the Graph service for friends-of-friends recommendations.
 * The user's token is forwarded (token propagation). Calls degrade gracefully: empty lists
 * on error, and eligibility fails closed (an unverifiable candidate is not recommended).
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
    private record Relationship(String friendStatus, boolean blocking, boolean blockedBy) {
    }
}
