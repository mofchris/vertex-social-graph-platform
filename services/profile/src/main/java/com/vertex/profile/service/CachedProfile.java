package com.vertex.profile.service;

import com.vertex.profile.domain.Profile;
import com.vertex.profile.domain.ProfileVisibility;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * The cached representation of a profile. Holds everything needed to decide visibility
 * (including {@code visibility} and the owner {@code userId}) so that privacy is applied
 * per-viewer at serve time, never baked into the cached value.
 *
 * <p>{@link Serializable} so it works with the default Redis cache serializer.
 */
public record CachedProfile(
        UUID userId,
        String displayName,
        String bio,
        String avatarUrl,
        String location,
        ProfileVisibility visibility,
        Instant updatedAt
) implements Serializable {

    public static CachedProfile from(Profile p) {
        return new CachedProfile(
                p.getUserId(), p.getDisplayName(), p.getBio(), p.getAvatarUrl(),
                p.getLocation(), p.getVisibility(), p.getUpdatedAt());
    }
}
