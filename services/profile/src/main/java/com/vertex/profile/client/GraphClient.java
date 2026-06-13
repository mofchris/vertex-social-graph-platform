package com.vertex.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Calls the Graph service to answer "are these two users friends?" for FRIENDS-visibility
 * profiles. The viewer's own bearer token is forwarded (token propagation), so Graph
 * evaluates the relationship as that viewer.
 */
@Component
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

    private final RestClient restClient;

    public GraphClient(@Value("${app.graph.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** True if the bearer's owner is friends with {@code ownerId}. Fails closed on any error. */
    public boolean areFriends(UUID ownerId, String authorizationHeader) {
        try {
            Relationship rel = restClient.get()
                    .uri("/v1/relationship/{id}", ownerId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(Relationship.class);
            return rel != null && "FRIENDS".equals(rel.friendStatus());
        } catch (RestClientException e) {
            // Privacy-first: if we can't confirm the friendship, don't reveal the profile.
            log.warn("graph relationship check failed for {}: {}", ownerId, e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Relationship(String friendStatus) {
    }
}
