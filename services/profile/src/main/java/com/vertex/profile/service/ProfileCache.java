package com.vertex.profile.service;

import com.vertex.profile.domain.Profile;
import com.vertex.profile.exception.ProfileNotFoundException;
import com.vertex.profile.repository.ProfileRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    static final String CACHE_NAME = "profiles";

    private final ProfileRepository repository;
    private final CacheManager cacheManager;

    public ProfileCache(ProfileRepository repository, CacheManager cacheManager) {
        this.repository = repository;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#userId")
    public CachedProfile load(UUID userId) {
        return repository.findById(userId)
                .map(CachedProfile::from)
                .orElseThrow(() -> new ProfileNotFoundException("profile not found"));
    }

    /**
     * Batch read-through over the same cache entries as {@link #load}: hits are served from
     * the cache, and all misses are fetched in a single {@code findAllById} query and then
     * cached. Done programmatically because {@code @Cacheable} only works per key, and N
     * single-row queries would defeat the point of a batch read.
     *
     * <p>Ids with no profile are simply absent from the returned map and, as with
     * {@link #load}, are not cached.
     */
    public Map<UUID, CachedProfile> loadAll(Collection<UUID> userIds) {
        Cache cache = Objects.requireNonNull(cacheManager.getCache(CACHE_NAME), "profiles cache");
        Map<UUID, CachedProfile> result = new HashMap<>();
        List<UUID> misses = new ArrayList<>();
        for (UUID id : userIds) {
            CachedProfile hit = cache.get(id, CachedProfile.class);
            if (hit != null) {
                result.put(id, hit);
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            for (Profile profile : repository.findAllById(misses)) {
                CachedProfile loaded = CachedProfile.from(profile);
                cache.put(loaded.userId(), loaded);
                result.put(loaded.userId(), loaded);
            }
        }
        return result;
    }
}
