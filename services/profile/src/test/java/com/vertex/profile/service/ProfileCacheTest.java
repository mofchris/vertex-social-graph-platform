package com.vertex.profile.service;

import com.vertex.profile.domain.Profile;
import com.vertex.profile.domain.ProfileVisibility;
import com.vertex.profile.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the read-through cache: the second lookup is served from cache, not the repo; and
 * the batch read shares those entries, loading only the misses in one query.
 */
@SpringBootTest
class ProfileCacheTest {

    @MockitoBean
    private ProfileRepository repository;

    @Autowired
    private ProfileCache cache;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("profiles")).clear();
    }

    @Test
    void secondReadIsServedFromCache() {
        UUID id = UUID.randomUUID();
        Profile profile = new Profile(id);
        profile.setDisplayName("Alice");
        profile.setVisibility(ProfileVisibility.PUBLIC);
        when(repository.findById(id)).thenReturn(Optional.of(profile));

        cache.load(id);
        cache.load(id);

        verify(repository, times(1)).findById(id);
    }

    private static Profile profile(UUID id, String name) {
        Profile p = new Profile(id);
        p.setDisplayName(name);
        p.setVisibility(ProfileVisibility.PUBLIC);
        return p;
    }

    @Test
    void batchReadServesHitsFromCacheAndLoadsMissesInOneQuery() {
        UUID cached = UUID.randomUUID();
        UUID uncached = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(repository.findById(cached)).thenReturn(Optional.of(profile(cached, "Alice")));
        when(repository.findAllById(List.of(uncached, missing))).thenReturn(List.of(profile(uncached, "Bob")));
        when(repository.findAllById(List.of(missing))).thenReturn(List.of());

        cache.load(cached); // warms the shared "profiles" entry

        Map<UUID, CachedProfile> first = cache.loadAll(List.of(cached, uncached, missing));
        assertThat(first).containsOnlyKeys(cached, uncached);
        assertThat(first.get(uncached).displayName()).isEqualTo("Bob");
        verify(repository, times(1)).findAllById(List.of(uncached, missing));

        // Second batch: the loaded profile is now cached; the missing id is not (no negative caching).
        Map<UUID, CachedProfile> second = cache.loadAll(List.of(cached, uncached, missing));
        assertThat(second).containsOnlyKeys(cached, uncached);
        verify(repository, times(1)).findAllById(List.of(missing));

        // And the single read is served from the entry the batch populated.
        cache.load(uncached);
        verify(repository, never()).findById(uncached);
        verify(repository, times(1)).findById(cached);
    }
}
