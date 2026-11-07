package com.vertex.profile.web;

import com.vertex.profile.exception.InvalidRequestException;
import com.vertex.profile.service.ProfileService;
import com.vertex.profile.web.dto.ProfileResponse;
import com.vertex.profile.web.dto.ProfilesResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class ProfileController {

    /** Max ids per batch read: bounds the cache/DB/Graph work a single request can cause. */
    static final int MAX_BATCH_IDS = 100;

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

    /**
     * Many profiles at once ({@code ?ids=<uuid>,<uuid>,...}), for UIs that only hold user ids
     * (feeds, notifications, suggestions). Same per-viewer visibility as the single read;
     * hidden or missing profiles are omitted, never stubbed, so existence isn't revealed.
     */
    @GetMapping("/v1/profiles")
    public ProfilesResponse profiles(
            @RequestParam(required = false) String ids,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            Authentication authentication) {
        List<UUID> userIds = parseIds(ids);
        return new ProfilesResponse(profileService.getManyForViewer(userIds, viewerId(authentication), authorization));
    }

    /**
     * Parses a comma-separated id list (repeated {@code ids=} params arrive joined by commas).
     * Rejects the whole request on any malformed id rather than silently dropping it, and
     * de-duplicates while keeping the caller's order.
     */
    static List<UUID> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("ids", "at least one id is required");
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_BATCH_IDS) {
            throw new InvalidRequestException("ids", "at most " + MAX_BATCH_IDS + " ids per request");
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (String part : parts) {
            String id = part.strip();
            try {
                // Length check: UUID.fromString also accepts non-canonical forms like "1-2-3-4-5".
                if (id.length() != 36) {
                    throw new IllegalArgumentException(id);
                }
                unique.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("ids", "malformed id: must be comma-separated UUIDs");
            }
        }
        return new ArrayList<>(unique);
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
