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

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves the read-through cache: the second lookup is served from cache, not the repo. */
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
}
