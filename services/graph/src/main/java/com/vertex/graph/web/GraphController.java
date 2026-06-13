package com.vertex.graph.web;

import com.vertex.graph.service.GraphService;
import com.vertex.graph.web.dto.CountsResponse;
import com.vertex.graph.web.dto.FriendsPage;
import com.vertex.graph.web.dto.RelationshipResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** All operations act as the authenticated user (the token's subject). */
@RestController
public class GraphController {

    private final GraphService graph;

    public GraphController(GraphService graph) {
        this.graph = graph;
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    // --- Follows ---

    @PostMapping("/v1/follow/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@PathVariable UUID userId, Authentication auth) {
        graph.follow(actor(auth), userId);
    }

    @DeleteMapping("/v1/follow/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable UUID userId, Authentication auth) {
        graph.unfollow(actor(auth), userId);
    }

    // --- Friends ---

    @PostMapping("/v1/friends/{userId}/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendRequest(@PathVariable UUID userId, Authentication auth) {
        graph.sendFriendRequest(actor(auth), userId);
    }

    @PostMapping("/v1/friends/{userId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable UUID userId, Authentication auth) {
        graph.acceptFriendRequest(actor(auth), userId);
    }

    @DeleteMapping("/v1/friends/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(@PathVariable UUID userId, Authentication auth) {
        graph.removeFriend(actor(auth), userId);
    }

    @GetMapping("/v1/friends")
    public FriendsPage friends(
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit,
            Authentication auth) {
        return graph.listFriends(actor(auth), cursor, limit);
    }

    // --- Blocks ---

    @PostMapping("/v1/block/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable UUID userId, Authentication auth) {
        graph.block(actor(auth), userId);
    }

    @DeleteMapping("/v1/block/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable UUID userId, Authentication auth) {
        graph.unblock(actor(auth), userId);
    }

    // --- Reads ---

    @GetMapping("/v1/relationship/{userId}")
    public RelationshipResponse relationship(@PathVariable UUID userId, Authentication auth) {
        return graph.relationship(actor(auth), userId);
    }

    @GetMapping("/v1/counts/{userId}")
    public CountsResponse counts(@PathVariable UUID userId) {
        return graph.counts(userId);
    }
}
