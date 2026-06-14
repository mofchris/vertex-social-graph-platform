package com.vertex.recommend.web.dto;

import java.util.List;

public record RecommendationsResponse(
        List<Recommendation> items
) {
}
