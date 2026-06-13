package com.vertex.identity.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full auth lifecycle against embedded H2 (no Docker): signup, authenticated
 * read, duplicate handling, bad login, refresh rotation, and refresh-token reuse detection.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String SIGNUP_BODY = """
            {"email":"alice@example.com","username":"alice","password":"password123","displayName":"Alice"}
            """;

    private static String refreshBody(String token) {
        return "{\"refreshToken\":\"" + token + "\"}";
    }

    @Test
    void signupAuthenticateRefreshAndDetectReuse() throws Exception {
        // 1. Signup -> 201 with tokens.
        MvcResult signup = mvc.perform(post("/v1/auth/signup")
                        .contentType(APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.createdAt").isNotEmpty())
                .andReturn();

        String body = signup.getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");

        // 2. /me with the access token -> 200.
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));

        // 3. /me without a token -> rejected.
        mvc.perform(get("/v1/me")).andExpect(status().is4xxClientError());

        // 4. Duplicate signup -> 409.
        mvc.perform(post("/v1/auth/signup").contentType(APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isConflict());

        // 5. Wrong password -> 401.
        mvc.perform(post("/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized());

        // 6. Refresh rotates the token.
        MvcResult refreshed = mvc.perform(post("/v1/auth/refresh").contentType(APPLICATION_JSON)
                        .content(refreshBody(refresh)))
                .andExpect(status().isOk())
                .andReturn();
        String rotated = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.refreshToken");
        assertThat(rotated).isNotEqualTo(refresh);

        // 7. Reusing the old (now-rotated) refresh token -> 401, and it burns the family.
        mvc.perform(post("/v1/auth/refresh").contentType(APPLICATION_JSON)
                        .content(refreshBody(refresh)))
                .andExpect(status().isUnauthorized());

        // 8. The rotated token is now revoked too (whole family burned on reuse) -> 401.
        mvc.perform(post("/v1/auth/refresh").contentType(APPLICATION_JSON)
                        .content(refreshBody(rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidSignupPayload() throws Exception {
        mvc.perform(post("/v1/auth/signup").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"username\":\"a\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
