package com.vertex.graph.web.dto;

import java.util.List;

/** The caller's most recent outbox rows, newest first. */
public record OutboxEventsPage(
        List<OutboxEventResponse> items
) {
}
