package com.vertex.identity.security;

import com.vertex.identity.config.JwtProperties;
import com.vertex.identity.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(new JwtProperties(
            "test-secret-test-secret-test-secret-0123456789",
            "vertex-identity",
            Duration.ofMinutes(15),
            Duration.ofDays(30)));

    @Test
    void issuesTokenThatParsesBackToTheUserId() {
        User user = new User("alice@example.com", "alice", "hash", "Alice");

        String token = jwtService.issueAccessToken(user);

        assertThat(jwtService.parseUserId(token)).contains(user.getId());
    }

    @Test
    void rejectsGarbageToken() {
        assertThat(jwtService.parseUserId("not-a-real-token")).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtService other = new JwtService(new JwtProperties(
                "a-completely-different-secret-key-987654321", "vertex-identity",
                Duration.ofMinutes(15), Duration.ofDays(30)));
        String foreignToken = other.issueAccessToken(new User("bob@example.com", "bob", "hash", "Bob"));

        assertThat(jwtService.parseUserId(foreignToken)).isEmpty();
    }
}
