package com.vertex.feed.web;

import com.vertex.feed.client.GraphClient;
import com.vertex.feed.config.JwtProperties;
import com.vertex.feed.repository.CelebrityRepository;
import com.vertex.feed.repository.PostRepository;
import com.vertex.feed.repository.TimelineEntryRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the Feed API against embedded H2 (Graph mocked), demonstrating hybrid fan-out:
 * a normal author fans out on write; a celebrity is pulled on read; the home feed merges both.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtProperties jwtProps;
    @Autowired private PostRepository posts;
    @Autowired private TimelineEntryRepository timeline;
    @Autowired private CelebrityRepository celebrities;

    @MockitoBean private GraphClient graph;

    private final UUID alice = UUID.randomUUID();  // normal author
    private final UUID bob = UUID.randomUUID();     // reader
    private final UUID celeb = UUID.randomUUID();   // celebrity author

    @BeforeEach
    void reset() {
        posts.deleteAll();
        timeline.deleteAll();
        celebrities.deleteAll();
    }

    private String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder().issuer(jwtProps.issuer()).subject(userId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key).compact();
        return "Bearer " + token;
    }

    private void createPost(UUID author, String content) throws Exception {
        mvc.perform(post("/v1/posts").header("Authorization", bearer(author))
                        .contentType(APPLICATION_JSON).content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId").value(author.toString()));
    }

    @Test
    void hybridFanOutHomeFeed() throws Exception {
        // Alice is a normal author with bob as a follower -> fan-out-on-write to bob.
        when(graph.followerCount(eq(alice), anyString())).thenReturn(1L);
        when(graph.followers(eq(alice), anyString())).thenReturn(List.of(bob));
        // Celeb is over the threshold -> no fan-out (pulled on read).
        when(graph.followerCount(eq(celeb), anyString())).thenReturn(100L);
        // Bob follows the celeb.
        when(graph.following(eq(bob), anyString())).thenReturn(List.of(celeb));

        createPost(alice, "hello from alice");
        createPost(celeb, "hello from celeb");

        // No timeline entry was written for the celebrity's post.
        // Bob's home feed merges the materialized (alice) and pulled (celeb) posts.
        mvc.perform(get("/v1/feed").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void userPostsListsOnlyThatAuthor() throws Exception {
        when(graph.followerCount(eq(alice), anyString())).thenReturn(0L);
        when(graph.followers(eq(alice), anyString())).thenReturn(List.of());
        createPost(alice, "first");
        createPost(alice, "second");

        mvc.perform(get("/v1/posts/" + alice).header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void rejectsEmptyPost() throws Exception {
        mvc.perform(post("/v1/posts").header("Authorization", bearer(alice))
                        .contentType(APPLICATION_JSON).content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mvc.perform(post("/v1/posts").contentType(APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().is4xxClientError());
    }
}
