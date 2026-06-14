package com.vertex.recommend.web;

import com.vertex.recommend.service.RecommendationService;
import com.vertex.recommend.web.dto.RecommendationsResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /** "People you may know" for the authenticated user. */
    @GetMapping("/v1/recommendations")
    public RecommendationsResponse recommendations(
            @RequestParam(defaultValue = "10") int limit,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return new RecommendationsResponse(recommendations.recommend(userId, authorization, limit));
    }
}
