package com.vertex.feed.service;

import com.vertex.feed.client.GraphClient;
import com.vertex.feed.domain.Celebrity;
import com.vertex.feed.domain.Post;
import com.vertex.feed.domain.TimelineEntry;
import com.vertex.feed.repository.CelebrityRepository;
import com.vertex.feed.repository.PostRepository;
import com.vertex.feed.repository.TimelineEntryRepository;
import com.vertex.feed.web.dto.FeedPage;
import com.vertex.feed.web.dto.PostResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FeedService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PostRepository posts;
    private final TimelineEntryRepository timeline;
    private final CelebrityRepository celebrities;
    private final GraphClient graph;
    private final long celebrityThreshold;

    public FeedService(PostRepository posts,
                       TimelineEntryRepository timeline,
                       CelebrityRepository celebrities,
                       GraphClient graph,
                       @Value("${app.feed.celebrity-threshold:20}") long celebrityThreshold) {
        this.posts = posts;
        this.timeline = timeline;
        this.celebrities = celebrities;
        this.graph = graph;
        this.celebrityThreshold = celebrityThreshold;
    }

    /**
     * Create a post and apply the hybrid fan-out strategy: normal authors fan out on write
     * to each follower's timeline; celebrities (followers over the threshold) skip fan-out
     * and are pulled on read instead.
     */
    @Transactional
    public PostResponse createPost(UUID authorId, String content, String authorizationHeader) {
        // saveAndFlush so the @PrePersist createdAt is set before we copy it into timeline entries.
        Post post = posts.saveAndFlush(new Post(authorId, content));

        long followerCount = graph.followerCount(authorId, authorizationHeader);
        if (followerCount > celebrityThreshold) {
            Celebrity celebrity = celebrities.findById(authorId)
                    .orElseGet(() -> new Celebrity(authorId, followerCount));
            celebrity.setFollowerCount(followerCount);
            celebrities.save(celebrity); // fan-out-on-read; no timeline writes
        } else {
            List<TimelineEntry> entries = graph.followers(authorId, authorizationHeader).stream()
                    .map(follower -> new TimelineEntry(follower, post.getId(), authorId, post.getCreatedAt()))
                    .toList();
            timeline.saveAll(entries);
        }
        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public FeedPage userPosts(UUID authorId, String cursor, int limit) {
        int pageSize = clamp(limit);
        List<Post> rows = posts.findByAuthorBefore(authorId, parseCursor(cursor), Limit.of(pageSize + 1));
        return pageOf(rows, pageSize);
    }

    /**
     * The reader's home timeline: materialized entries (normal authors they follow) merged
     * with a pull of recent posts from the celebrities they follow.
     */
    @Transactional(readOnly = true)
    public FeedPage homeFeed(UUID readerId, String cursor, int limit, String authorizationHeader) {
        int pageSize = clamp(limit);
        Instant before = parseCursor(cursor);
        Limit fetch = Limit.of(pageSize + 1);

        // Materialized timeline (fan-out-on-write). Stores references, so resolve the posts.
        List<TimelineEntry> entries = timeline.findHomeBefore(readerId, before, fetch);
        List<UUID> postIds = entries.stream().map(TimelineEntry::getPostId).toList();
        Map<UUID, Post> resolved = posts.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));

        Map<UUID, Post> merged = new LinkedHashMap<>();
        entries.stream()
                .map(e -> resolved.get(e.getPostId()))
                .filter(Objects::nonNull)
                .forEach(p -> merged.put(p.getId(), p));

        // Fan-out-on-read: posts from the celebrities this reader follows.
        List<UUID> following = graph.following(readerId, authorizationHeader);
        if (!following.isEmpty()) {
            List<UUID> celebFollowees = celebrities.findByUserIdIn(following).stream()
                    .map(Celebrity::getUserId)
                    .toList();
            if (!celebFollowees.isEmpty()) {
                posts.findByAuthorsBefore(celebFollowees, before, fetch)
                        .forEach(p -> merged.put(p.getId(), p));
            }
        }

        List<Post> sorted = merged.values().stream()
                .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
                .toList();
        return pageOf(sorted, pageSize);
    }

    private FeedPage pageOf(List<Post> rows, int pageSize) {
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<PostResponse> items = page.stream().map(PostResponse::from).toList();
        String nextCursor = hasMore
                ? String.valueOf(page.get(page.size() - 1).getCreatedAt().toEpochMilli())
                : null;
        return new FeedPage(items, nextCursor);
    }

    private static int clamp(int limit) {
        return Math.clamp(limit, 1, MAX_PAGE_SIZE);
    }

    private static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.now().plusSeconds(1); // include the newest posts
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(cursor));
        } catch (NumberFormatException e) {
            return Instant.now().plusSeconds(1);
        }
    }
}
