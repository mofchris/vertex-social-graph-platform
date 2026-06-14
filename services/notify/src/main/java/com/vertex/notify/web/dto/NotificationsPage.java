package com.vertex.notify.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A page of notifications. {@code nextCursor} (epoch-millis string) is null on the last page. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationsPage(
        List<NotificationResponse> items,
        String nextCursor
) {
}
