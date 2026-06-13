package com.vertex.graph.web.dto;

import java.util.UUID;

public record CountsResponse(
        UUID userId,
        long followers,
        long following,
        long friends
) {
}
