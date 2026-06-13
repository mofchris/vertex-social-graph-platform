package com.vertex.graph.web;

import com.vertex.graph.config.JwtProperties;
import com.vertex.graph.repository.BlockRepository;
import com.vertex.graph.repository.FollowRepository;
import com.vertex.graph.repository.FriendshipRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drives the Graph API against embedded H2 (no Docker), focusing on the tricky edge cases. */
@SpringBootTest
@AutoConfigureMockMvc
class GraphFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;
    @Autowired private FollowRepository follows;
    @Autowired private FriendshipRepository friendships;
    @Autowired private BlockRepository blocks;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void reset() {
        follows.deleteAll();
        friendships.deleteAll();
        blocks.deleteAll();
    }

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .issuer(jwtProps.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
        return "Bearer " + token;
    }

    @Test
    void rejectsSelfEdges() throws Exception {
        mvc.perform(post("/v1/follow/" + alice).header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/friends/" + alice + "/request").header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/block/" + alice).header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void followIsIdempotentAndCounted() throws Exception {
        mvc.perform(post("/v1/follow/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/v1/follow/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent()); // idempotent

        mvc.perform(get("/v1/counts/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followers").value(1));
        mvc.perform(get("/v1/counts/" + alice).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.following").value(1));
        mvc.perform(get("/v1/relationship/" + bob).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.followedBy").value(false));
    }

    @Test
    void crossingFriendRequestsAutoAccept() throws Exception {
        mvc.perform(post("/v1/friends/" + bob + "/request").header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/relationship/" + bob).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.friendStatus").value("PENDING_OUTGOING"));

        // Bob independently requests Alice -> the two requests collapse into a friendship.
        mvc.perform(post("/v1/friends/" + alice + "/request").header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/relationship/" + bob).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.friendStatus").value("FRIENDS"));
        mvc.perform(get("/v1/counts/" + alice).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.friends").value(1));
    }

    @Test
    void acceptRulesAndCannotAcceptOwnRequest() throws Exception {
        mvc.perform(post("/v1/friends/" + bob + "/request").header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        // The requester cannot accept their own request.
        mvc.perform(post("/v1/friends/" + bob + "/accept").header("Authorization", bearer(alice)))
                .andExpect(status().isConflict());

        // The addressee accepts -> friends.
        mvc.perform(post("/v1/friends/" + alice + "/accept").header("Authorization", bearer(bob)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/relationship/" + alice).header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.friendStatus").value("FRIENDS"));
    }

    @Test
    void blockWinsAndFailsSilently() throws Exception {
        // Become friends and mutual followers first.
        mvc.perform(post("/v1/follow/" + bob).header("Authorization", bearer(alice))).andExpect(status().isNoContent());
        mvc.perform(post("/v1/follow/" + alice).header("Authorization", bearer(bob))).andExpect(status().isNoContent());
        mvc.perform(post("/v1/friends/" + bob + "/request").header("Authorization", bearer(alice))).andExpect(status().isNoContent());
        mvc.perform(post("/v1/friends/" + alice + "/accept").header("Authorization", bearer(bob))).andExpect(status().isNoContent());

        // Alice blocks Bob: friendship and follows (both directions) are dropped.
        mvc.perform(post("/v1/block/" + bob).header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/relationship/" + bob).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.blocking").value(true))
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.followedBy").value(false))
                .andExpect(jsonPath("$.friendStatus").value("NONE"));

        // Bob can't re-engage Alice: blocked actions fail silently (404), not 403.
        mvc.perform(post("/v1/friends/" + alice + "/request").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/follow/" + alice).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void friendsListIsCursorPaginated() throws Exception {
        UUID hub = UUID.randomUUID();
        UUID[] friends = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (UUID f : friends) {
            mvc.perform(post("/v1/friends/" + f + "/request").header("Authorization", bearer(hub)))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/v1/friends/" + hub + "/accept").header("Authorization", bearer(f)))
                    .andExpect(status().isNoContent());
        }

        // First page of 2 -> a next cursor.
        var firstPage = mvc.perform(get("/v1/friends?limit=2").header("Authorization", bearer(hub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.nextCursor");

        // Second page -> the remaining 1, no further cursor.
        mvc.perform(get("/v1/friends?limit=2&cursor=" + cursor).header("Authorization", bearer(hub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mvc.perform(post("/v1/follow/" + bob)).andExpect(status().is4xxClientError());
    }
}
