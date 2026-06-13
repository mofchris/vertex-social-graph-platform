package com.vertex.profile.web.dto;

import com.vertex.profile.domain.ProfileVisibility;
import com.vertex.profile.service.CachedProfile;

import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(
        UUID userId,
        String displayName,
        String bio,
        String avatarUrl,
        String location,
        ProfileVisibility visibility,
        Instant updatedAt
) {
    public static ProfileResponse from(CachedProfile p) {
        return new ProfileResponse(
                p.userId(), p.displayName(), p.bio(), p.avatarUrl(),
                p.location(), p.visibility(), p.updatedAt());
    }
}
