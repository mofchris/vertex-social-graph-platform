package com.vertex.profile.service;

import com.vertex.profile.exception.ProfileNotFoundException;
import com.vertex.profile.repository.ProfileRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Read-through cache for profiles. Kept as its own bean so {@code @Cacheable} is invoked
 * through the Spring proxy (a self-call inside {@code ProfileService} would bypass it).
 *
 * <p>On a miss this loads from the database and caches the result; a not-found throws and
 * is intentionally not cached. (Negative caching would defend against cache penetration —
 * see EDGE_CASES.md — and is a deliberate next step.)
 */
@Component
public class ProfileCache {

    private final ProfileRepository repository;

    public ProfileCache(ProfileRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = "profiles", key = "#userId")
    public CachedProfile load(UUID userId) {
        return repository.findById(userId)
                .map(CachedProfile::from)
                .orElseThrow(() -> new ProfileNotFoundException("profile not found"));
    }
}
