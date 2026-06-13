package com.vertex.profile.web;

import com.vertex.profile.service.ProfileService;
import com.vertex.profile.web.dto.ProfileResponse;
import com.vertex.profile.web.dto.UpsertProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /** Create or replace the authenticated caller's own profile. */
    @PutMapping("/v1/me/profile")
    public ProfileResponse upsertMyProfile(
            @Valid @RequestBody UpsertProfileRequest request,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return profileService.upsert(userId, request);
    }

    /** The authenticated caller's own profile. */
    @GetMapping("/v1/me/profile")
    public ProfileResponse myProfile(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return profileService.getOwn(userId);
    }

    /** A user's profile, subject to its visibility for the (possibly anonymous) viewer. */
    @GetMapping("/v1/profiles/{userId}")
    public ProfileResponse profile(
            @PathVariable UUID userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            Authentication authentication) {
        return profileService.getForViewer(userId, viewerId(authentication), authorization);
    }

    /** The authenticated viewer's id, or null for an anonymous request. */
    private static UUID viewerId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
