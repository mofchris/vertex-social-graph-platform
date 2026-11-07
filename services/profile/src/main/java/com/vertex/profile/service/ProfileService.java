package com.vertex.profile.service;

import com.vertex.profile.client.GraphClient;
import com.vertex.profile.domain.Profile;
import com.vertex.profile.domain.ProfileVisibility;
import com.vertex.profile.repository.ProfileRepository;
import com.vertex.profile.exception.ProfileNotFoundException;
import com.vertex.profile.web.dto.ProfileResponse;
import com.vertex.profile.web.dto.UpsertProfileRequest;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

@Service
public class ProfileService {

    /**
     * Upper bound on concurrent Graph friendship checks for one batch read. Graph has no
     * batch relationship API, so a batch with many FRIENDS-only profiles needs one call per
     * owner; running them in parallel keeps latency near a single call, and the cap stops a
     * single page load from fanning out 100 simultaneous requests at Graph.
     */
    private static final int GRAPH_CHECK_CONCURRENCY = 8;

    private final ProfileRepository repository;
    private final ProfileCache cache;
    private final GraphClient graphClient;

    public ProfileService(ProfileRepository repository, ProfileCache cache, GraphClient graphClient) {
        this.repository = repository;
        this.cache = cache;
        this.graphClient = graphClient;
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
     * {@code authorizationHeader} is the viewer's bearer token, forwarded to the Graph
     * service for FRIENDS checks. Hidden profiles 404 silently.
     */
    public ProfileResponse getForViewer(UUID targetUserId, UUID viewerId, String authorizationHeader) {
        CachedProfile profile = cache.load(targetUserId);
        Predicate<UUID> viewerIsFriendOf = ownerId -> viewerId != null
                && authorizationHeader != null
                && graphClient.areFriends(ownerId, authorizationHeader);
        if (!isVisibleTo(profile, viewerId, viewerIsFriendOf)) {
            throw new ProfileNotFoundException("profile not found");
        }
        return ProfileResponse.from(profile);
    }

    /**
     * Many profiles as seen by {@code viewerId}, applying exactly the same rules as
     * {@link #getForViewer}. Profiles that don't exist or are hidden from the viewer are
     * omitted, so — like the single endpoint's silent 404 — the response never reveals
     * whether a hidden profile exists. Results keep the order of {@code userIds}.
     *
     * <p>Cache hits are served from the cache and misses load in one query; FRIENDS checks
     * run only for profiles that need one, in parallel (see {@link #GRAPH_CHECK_CONCURRENCY}).
     */
    public List<ProfileResponse> getManyForViewer(List<UUID> userIds, UUID viewerId, String authorizationHeader) {
        Map<UUID, CachedProfile> found = cache.loadAll(userIds);

        Set<UUID> friendOwners = Set.of();
        if (viewerId != null && authorizationHeader != null) {
            List<UUID> needsCheck = found.values().stream()
                    .filter(p -> p.visibility() == ProfileVisibility.FRIENDS)
                    .map(CachedProfile::userId)
                    .filter(ownerId -> !ownerId.equals(viewerId))
                    .toList();
            friendOwners = friendsAmong(needsCheck, authorizationHeader);
        }

        List<ProfileResponse> visible = new ArrayList<>();
        for (UUID id : userIds) {
            CachedProfile profile = found.get(id);
            if (profile != null && isVisibleTo(profile, viewerId, friendOwners::contains)) {
                visible.add(ProfileResponse.from(profile));
            }
        }
        return visible;
    }

    /**
     * The subset of {@code ownerIds} the bearer is friends with. Each check uses the same
     * Graph call as the single read and fails closed: any error means "not a friend".
     */
    private Set<UUID> friendsAmong(List<UUID> ownerIds, String authorizationHeader) {
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        if (ownerIds.size() == 1) {
            UUID only = ownerIds.getFirst();
            return graphClient.areFriends(only, authorizationHeader) ? Set.of(only) : Set.of();
        }
        Set<UUID> friends = new HashSet<>();
        int threads = Math.min(GRAPH_CHECK_CONCURRENCY, ownerIds.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads, Thread.ofVirtual().factory())) {
            List<Future<Boolean>> checks = ownerIds.stream()
                    .map(ownerId -> pool.submit(() -> graphClient.areFriends(ownerId, authorizationHeader)))
                    .toList();
            for (int i = 0; i < ownerIds.size(); i++) {
                if (isTrue(checks.get(i))) {
                    friends.add(ownerIds.get(i));
                }
            }
        }
        return friends;
    }

    private static boolean isTrue(Future<Boolean> check) {
        try {
            return Objects.equals(check.get(), Boolean.TRUE);
        } catch (ExecutionException e) {
            return false; // fail closed, as in GraphClient
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The visibility rules, shared by the single and batch reads. {@code viewerIsFriendOf}
     * answers "is the viewer friends with this owner?" and is only consulted for FRIENDS.
     */
    private static boolean isVisibleTo(CachedProfile profile, UUID viewerId, Predicate<UUID> viewerIsFriendOf) {
        if (viewerId != null && viewerId.equals(profile.userId())) {
            return true; // the owner always sees their own profile
        }
        return switch (profile.visibility()) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            // FRIENDS: ask the Graph service whether the viewer is friends with the owner.
            case FRIENDS -> viewerId != null && viewerIsFriendOf.test(profile.userId());
        };
    }
}
