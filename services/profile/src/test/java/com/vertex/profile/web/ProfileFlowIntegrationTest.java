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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the Profile API against embedded H2 + the simple cache (no Docker): upsert, owner
 * read, public/private visibility for other viewers, cache eviction on update, and 404s —
 * plus the batch read, which must apply exactly the same visibility rules.
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
    private final UUID dave = UUID.randomUUID();

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

    private void createProfile(UUID owner, String displayName, String visibility) throws Exception {
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer " + token(owner))
                        .contentType(APPLICATION_JSON).content(body(displayName, visibility)))
                .andExpect(status().isOk());
    }

    private static String ids(UUID... ids) {
        return Stream.of(ids).map(UUID::toString).collect(Collectors.joining(","));
    }

    @Test
    void batchAppliesTheSameVisibilityRulesAsSingleRead() throws Exception {
        createProfile(alice, "Alice", "PUBLIC");
        createProfile(bob, "Bobby", "PRIVATE");
        createProfile(carol, "Carol", "FRIENDS");
        UUID unknown = UUID.randomUUID();
        // Dave is friends with Carol only.
        when(graphClient.areFriends(any(), anyString())).thenReturn(false);
        when(graphClient.areFriends(eq(carol), anyString())).thenReturn(true);

        // Dave sees PUBLIC + his friend's FRIENDS profile, in request order (duplicates dropped).
        // The PRIVATE profile and the unknown id are omitted entirely: no stub, no fields,
        // nothing to tell a hidden profile apart from a missing one.
        String query = ids(carol, bob, unknown, alice, carol);
        mvc.perform(get("/v1/profiles").param("ids", query).header("Authorization", "Bearer " + token(dave)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].userId").value(carol.toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Carol"))
                .andExpect(jsonPath("$.items[1].userId").value(alice.toString()))
                .andExpect(content().string(not(containsString(bob.toString()))))
                .andExpect(content().string(not(containsString("Bobby"))))
                .andExpect(content().string(not(containsString(unknown.toString()))));

        // Repeated ids= params work too.
        mvc.perform(get("/v1/profiles").param("ids", alice.toString()).param("ids", carol.toString())
                        .header("Authorization", "Bearer " + token(dave)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // A non-friend (Graph says no) doesn't see the FRIENDS profile.
        when(graphClient.areFriends(eq(carol), anyString())).thenReturn(false);
        mvc.perform(get("/v1/profiles").param("ids", ids(carol)).header("Authorization", "Bearer " + token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // The owner sees their own PRIVATE profile in a batch, like the single read.
        mvc.perform(get("/v1/profiles").param("ids", ids(alice, bob)).header("Authorization", "Bearer " + token(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].displayName").value("Bobby"))
                .andExpect(jsonPath("$.items[1].visibility").value("PRIVATE"));
    }

    @Test
    void batchForAnonymousViewerShowsOnlyPublicAndNeverCallsGraph() throws Exception {
        createProfile(alice, "Alice", "PUBLIC");
        createProfile(bob, "Bobby", "PRIVATE");
        createProfile(carol, "Carol", "FRIENDS");

        mvc.perform(get("/v1/profiles").param("ids", ids(alice, bob, carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(alice.toString()));
        verify(graphClient, never()).areFriends(any(), any());
    }

    @Test
    void batchReflectsUpdatesThroughTheSharedCache() throws Exception {
        createProfile(alice, "Alice", "PUBLIC");
        // Warm the cache via both reads, then flip to PRIVATE (which evicts that same entry).
        mvc.perform(get("/v1/profiles/" + alice)).andExpect(status().isOk());
        mvc.perform(get("/v1/profiles").param("ids", ids(alice)))
                .andExpect(jsonPath("$.items.length()").value(1));
        createProfile(alice, "Alice", "PRIVATE");

        mvc.perform(get("/v1/profiles").param("ids", ids(alice)).header("Authorization", "Bearer " + token(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void batchAcceptsUpToOneHundredIds() throws Exception {
        UUID[] hundred = Stream.generate(UUID::randomUUID).limit(100).toArray(UUID[]::new);
        mvc.perform(get("/v1/profiles").param("ids", ids(hundred)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void batchRejectsMoreThanOneHundredIds() throws Exception {
        UUID[] tooMany = Stream.generate(UUID::randomUUID).limit(101).toArray(UUID[]::new);
        mvc.perform(get("/v1/profiles").param("ids", ids(tooMany)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.ids").value(containsString("100")));
    }

    @Test
    void batchRejectsMalformedOrMissingIds() throws Exception {
        mvc.perform(get("/v1/profiles").param("ids", alice + ",not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.ids").exists());
        // Non-canonical forms that UUID.fromString would accept are rejected too.
        mvc.perform(get("/v1/profiles").param("ids", "1-2-3-4-5")).andExpect(status().isBadRequest());
        // Empty entries (e.g. a trailing comma) are malformed, not silently skipped.
        mvc.perform(get("/v1/profiles").param("ids", alice + ",")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/profiles").param("ids", "")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/profiles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
