package com.vertex.feed.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A page of posts. {@code nextCursor} (epoch-millis string) is null on the last page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedPage(
        List<PostResponse> items,
        String nextCursor
) {
}
