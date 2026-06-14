package com.vertex.feed.web;

import com.vertex.feed.service.FeedService;
import com.vertex.feed.web.dto.CreatePostRequest;
import com.vertex.feed.web.dto.FeedPage;
import com.vertex.feed.web.dto.PostResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    /** Create a post as the authenticated user. */
    @PostMapping("/v1/posts")
    public ResponseEntity<PostResponse> create(
            @Valid @RequestBody CreatePostRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            Authentication auth) {
        PostResponse created = feedService.createPost(actor(auth), request.content(), authorization);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** A single user's posts (author timeline). */
    @GetMapping("/v1/posts/{userId}")
    public FeedPage userPosts(
            @PathVariable UUID userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return feedService.userPosts(userId, cursor, limit);
    }

    /** The authenticated user's home timeline (hybrid fan-out). */
    @GetMapping("/v1/feed")
    public FeedPage feed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            Authentication auth) {
        return feedService.homeFeed(actor(auth), cursor, limit, authorization);
    }
}
