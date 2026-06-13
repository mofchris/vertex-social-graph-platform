package com.vertex.profile.service;

import com.vertex.profile.domain.Profile;
import com.vertex.profile.repository.ProfileRepository;
import com.vertex.profile.exception.ProfileNotFoundException;
import com.vertex.profile.web.dto.ProfileResponse;
import com.vertex.profile.web.dto.UpsertProfileRequest;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProfileService {

    private final ProfileRepository repository;
    private final ProfileCache cache;

    public ProfileService(ProfileRepository repository, ProfileCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    /** Create or replace the caller's own profile, then evict the stale cache entry. */
    @Transactional
    @CacheEvict(cacheNames = "profiles", key = "#userId")
    public ProfileResponse upsert(UUID userId, UpsertProfileRequest req) {
        Profile profile = repository.findById(userId).orElseGet(() -> new Profile(userId));
        profile.setDisplayName(req.displayName());
        profile.setBio(req.bio());
        profile.setAvatarUrl(req.avatarUrl());
        profile.setLocation(req.location());
        profile.setVisibility(req.visibility());
        // saveAndFlush so the @PrePersist/@PreUpdate timestamps are set before we map the response.
        repository.saveAndFlush(profile);
        return ProfileResponse.from(CachedProfile.from(profile));
    }

    /** The caller's own profile — always fully visible to the owner. */
    public ProfileResponse getOwn(UUID userId) {
        return ProfileResponse.from(cache.load(userId));
    }

    /**
     * A profile as seen by {@code viewerId} (nullable = anonymous). Privacy is enforced
     * here, at serve time, on top of the cached value — not baked into the cache.
     * Hidden profiles 404 silently (don't reveal that the profile exists).
     */
    public ProfileResponse getForViewer(UUID targetUserId, UUID viewerId) {
        CachedProfile profile = cache.load(targetUserId);
        if (!isVisibleTo(profile, viewerId)) {
            throw new ProfileNotFoundException("profile not found");
        }
        return ProfileResponse.from(profile);
    }

    private boolean isVisibleTo(CachedProfile profile, UUID viewerId) {
        if (viewerId != null && viewerId.equals(profile.userId())) {
            return true; // the owner always sees their own profile
        }
        return switch (profile.visibility()) {
            case PUBLIC -> true;
            // FRIENDS will consult the Graph service once it exists; until then, owner-only.
            case FRIENDS, PRIVATE -> false;
        };
    }
}
