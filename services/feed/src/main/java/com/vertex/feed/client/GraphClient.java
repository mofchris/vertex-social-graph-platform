package com.vertex.feed.client;

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
 * Reads the social graph from the Graph service for fan-out decisions. The acting user's
 * bearer token is forwarded (token propagation). Calls degrade gracefully: on error the
 * count is 0 and lists are empty, so a Graph hiccup can't crash a post or a feed read.
 */
@Component
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

    private final RestClient restClient;

    public GraphClient(@Value("${app.graph.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public long followerCount(UUID userId, String authorizationHeader) {
        try {
            Counts counts = restClient.get()
                    .uri("/v1/counts/{id}", userId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(Counts.class);
            return counts == null ? 0 : counts.followers();
        } catch (RestClientException e) {
            log.warn("graph counts failed for {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    public List<UUID> followers(UUID userId, String authorizationHeader) {
        return pageAll("/v1/followers/{id}", userId, authorizationHeader);
    }

    public List<UUID> following(UUID userId, String authorizationHeader) {
        return pageAll("/v1/following/{id}", userId, authorizationHeader);
    }

    private List<UUID> pageAll(String path, UUID userId, String authorizationHeader) {
        List<UUID> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            String uri = (cursor == null)
                    ? path + "?limit=100"
                    : path + "?limit=100&cursor=" + cursor;
            UserPage page;
            try {
                page = restClient.get()
                        .uri(uri, userId)
                        .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                        .retrieve()
                        .body(UserPage.class);
            } catch (RestClientException e) {
                log.warn("graph {} failed for {}: {}", path, userId, e.getMessage());
                break;
            }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Counts(long followers, long following, long friends) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserPage(List<UUID> items, String nextCursor) {
    }
}
