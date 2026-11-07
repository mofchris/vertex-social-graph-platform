package com.vertex.recommend.web;

import com.vertex.recommend.client.GraphClient;
import com.vertex.recommend.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the Recommend API with a mocked Graph, covering FoF ranking, eligibility, and the
 * cold-start fallback to popular users (including when that fallback fails).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;

    @MockitoBean private GraphClient graph;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID dave = UUID.randomUUID();
    private final UUID eve = UUID.randomUUID();
    private final UUID frank = UUID.randomUUID();
    private final UUID grace = UUID.randomUUID();
    private final UUID heidi = UUID.randomUUID();

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder().issuer(jwtProps.issuer()).subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key).compact();
        return "Bearer " + token;
    }

    /** Alice's friends are bob and carol; both are friends with dave -> dave ranks first. */
    private void wireFriendGraph() {
        when(graph.allFriends(eq(alice), anyString())).thenReturn(List.of(bob, carol));
        when(graph.sampleFriends(eq(bob), anyString(), anyInt())).thenReturn(List.of(alice, dave, eve));
        when(graph.sampleFriends(eq(carol), anyString(), anyInt())).thenReturn(List.of(alice, dave, frank));
    }

    @Test
    void ranksFriendsOfFriendsByMutualCount() throws Exception {
        wireFriendGraph();
        when(graph.isEligible(any(), anyString())).thenReturn(true);

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].userId").value(dave.toString()))
                .andExpect(jsonPath("$.items[0].mutualFriends").value(2))
                .andExpect(jsonPath("$.items[0].reason").value("MUTUAL_FRIENDS"));
    }

    @Test
    void filtersOutIneligibleCandidates() throws Exception {
        wireFriendGraph();
        when(graph.isEligible(any(), anyString())).thenReturn(true);
        when(graph.isEligible(eq(dave), anyString())).thenReturn(false); // e.g. blocked

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].userId", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(dave.toString()))));
    }

    @Test
    void coldStartWithNoPopularUsersReturnsEmpty() throws Exception {
        when(graph.allFriends(eq(alice), anyString())).thenReturn(List.of());
        when(graph.popular(anyString(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/v1/recommendations")).andExpect(status().is4xxClientError());
    }

    @Test
    void enoughFriendsOfFriendsSkipsThePopularFallback() throws Exception {
        wireFriendGraph();
        when(graph.isEligible(any(), anyString())).thenReturn(true);

        mvc.perform(get("/v1/recommendations").param("limit", "2").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].reason", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("MUTUAL_FRIENDS"))));
        verify(graph, never()).popular(anyString(), anyInt());
    }

    @Test
    void coldStartFallsBackToPopularUsersTheCallerIsNotConnectedTo() throws Exception {
        when(graph.allFriends(eq(alice), anyString())).thenReturn(List.of());
        // Graph's popular list should already exclude the caller; alice is in it anyway to
        // prove the service doesn't rely on that. Eve is already followed by alice.
        when(graph.popular(anyString(), anyInt())).thenReturn(List.of(dave, alice, eve, frank));
        when(graph.isUnconnected(any(), anyString())).thenReturn(true);
        when(graph.isUnconnected(eq(eve), anyString())).thenReturn(false);

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].userId").value(dave.toString()))
                .andExpect(jsonPath("$.items[0].reason").value("POPULAR"))
                .andExpect(jsonPath("$.items[0].mutualFriends").value(0))
                .andExpect(jsonPath("$.items[1].userId").value(frank.toString()))
                .andExpect(jsonPath("$.items[1].reason").value("POPULAR"));
        verify(graph, never()).isUnconnected(eq(alice), anyString());
    }

    @Test
    void popularTopsUpFriendsOfFriendsWithoutDuplicatesOrExistingFriends() throws Exception {
        wireFriendGraph(); // FoF yields dave, eve, frank
        when(graph.isEligible(any(), anyString())).thenReturn(true);
        when(graph.isEligible(eq(eve), anyString())).thenReturn(false); // e.g. blocked
        // bob is already a friend, dave is already suggested, eve was rejected by FoF.
        when(graph.popular(anyString(), anyInt())).thenReturn(List.of(bob, dave, eve, grace, heidi));
        when(graph.isUnconnected(any(), anyString())).thenReturn(true);

        mvc.perform(get("/v1/recommendations").param("limit", "3").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].userId").value(dave.toString()))
                .andExpect(jsonPath("$.items[0].reason").value("MUTUAL_FRIENDS"))
                .andExpect(jsonPath("$.items[1].userId").value(frank.toString()))
                .andExpect(jsonPath("$.items[1].reason").value("MUTUAL_FRIENDS"))
                .andExpect(jsonPath("$.items[2].userId").value(grace.toString()))
                .andExpect(jsonPath("$.items[2].reason").value("POPULAR"));
        verify(graph, never()).isUnconnected(eq(bob), anyString());
        verify(graph, never()).isUnconnected(eq(dave), anyString());
        verify(graph, never()).isUnconnected(eq(eve), anyString());
        verify(graph, never()).isUnconnected(eq(heidi), anyString()); // already full
    }

    @Test
    void failingPopularFallbackDegradesToFriendsOfFriends() throws Exception {
        wireFriendGraph();
        when(graph.isEligible(any(), anyString())).thenReturn(true);
        when(graph.popular(anyString(), anyInt())).thenThrow(new ResourceAccessException("graph down"));

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].reason", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("MUTUAL_FRIENDS"))));
    }

    @Test
    void failingPopularFallbackOnColdStartReturnsEmpty() throws Exception {
        when(graph.allFriends(eq(alice), anyString())).thenReturn(List.of());
        when(graph.popular(anyString(), anyInt())).thenThrow(new ResourceAccessException("graph down"));

        mvc.perform(get("/v1/recommendations").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
