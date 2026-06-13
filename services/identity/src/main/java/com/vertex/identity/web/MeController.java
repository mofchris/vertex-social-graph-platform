package com.vertex.identity.web;

import com.vertex.identity.service.AuthService;
import com.vertex.identity.web.dto.UserResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    /** Returns the profile of the caller identified by the Bearer access token. */
    @GetMapping
    public UserResponse me(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return UserResponse.from(authService.requireActiveUser(userId));
    }
}
