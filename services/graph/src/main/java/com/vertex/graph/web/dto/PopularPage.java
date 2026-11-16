package com.vertex.graph.web.dto;

import java.util.List;

/** Most-followed users, descending. Not paginated: it is a short discovery list (max 50). */
public record PopularPage(
        List<PopularUser> items
) {
}
