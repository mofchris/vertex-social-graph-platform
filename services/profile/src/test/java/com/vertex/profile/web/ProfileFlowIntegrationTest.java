package com.vertex.profile.web;

import com.vertex.profile.client.GraphClient;
import com.vertex.profile.config.JwtProperties;
import com.vertex.profile.repository.ProfileRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the Profile API against embedded H2 + the simple cache (no Docker): upsert, owner
 * read, public/private visibility for other viewers, cache eviction on update, and 404s.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtProperties jwtProps;

    @Autowired
    private ProfileRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private GraphClient graphClient;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @BeforeEach
    void reset() {
        repository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache("profiles")).clear();
    }

    /** Mints an access token exactly as the Identity service would (shared secret + issuer). */
    private String token(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(jwtProps.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private static String body(String displayName, String visibility) {
        return "{\"displayName\":\"" + displayName + "\",\"bio\":\"hello\",\"visibility\":\"" + visibility + "\"}";
    }

    @Test
    void upsertReadAndVisibilityRules() throws Exception {
        // Create my profile (PUBLIC).
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer " + token(alice))
                        .contentType(APPLICATION_JSON).content(body("Alice", "PUBLIC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(alice.toString()))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // Read my own profile.
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer " + token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice"));

        // Public profile is visible anonymously and to other users.
        mvc.perform(get("/v1/profiles/" + alice)).andExpect(status().isOk());
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(bob)))
                .andExpect(status().isOk());

        // Switch to PRIVATE (this also evicts the cached PUBLIC entry).
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer " + token(alice))
                        .contentType(APPLICATION_JSON).content(body("Alice", "PRIVATE")))
                .andExpect(status().isOk());

        // Now hidden from anonymous and from other users (404, silent).
        mvc.perform(get("/v1/profiles/" + alice)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(bob)))
                .andExpect(status().isNotFound());

        // The owner still sees their own private profile.
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(alice)))
                .andExpect(status().isOk());

        // Unknown profile -> 404.
        mvc.perform(get("/v1/profiles/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void friendsVisibilityConsultsGraph() throws Exception {
        // Alice's profile is FRIENDS-only.
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer " + token(alice))
                        .contentType(APPLICATION_JSON).content(body("Alice", "FRIENDS")))
                .andExpect(status().isOk());

        // Bob is a friend (Graph says so) -> visible.
        when(graphClient.areFriends(eq(alice), anyString())).thenReturn(true);
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(bob)))
                .andExpect(status().isOk());

        // Carol is not a friend -> hidden (404).
        when(graphClient.areFriends(eq(alice), anyString())).thenReturn(false);
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(carol)))
                .andExpect(status().isNotFound());

        // Anonymous viewer -> hidden, and Graph is never consulted.
        mvc.perform(get("/v1/profiles/" + alice)).andExpect(status().isNotFound());

        // The owner always sees their own profile.
        mvc.perform(get("/v1/profiles/" + alice).header("Authorization", "Bearer " + token(alice)))
                .andExpect(status().isOk());
    }

    @Test
    void writesRequireAuthentication() throws Exception {
        mvc.perform(put("/v1/me/profile").contentType(APPLICATION_JSON).content(body("X", "PUBLIC")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rejectsInvalidPayload() throws Exception {
        // visibility is required.
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer " + token(alice))
                        .contentType(APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
