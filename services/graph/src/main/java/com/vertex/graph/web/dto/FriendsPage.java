package com.vertex.graph.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** A page of friend user ids. {@code nextCursor} is null on the last page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FriendsPage(
        List<UUID> items,
        String nextCursor
) {
}
