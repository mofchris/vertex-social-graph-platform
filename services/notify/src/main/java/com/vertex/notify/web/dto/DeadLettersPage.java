package com.vertex.notify.web.dto;

import java.util.List;

/** The caller's most recent dead letters, newest first. */
public record DeadLettersPage(
        List<DeadLetterResponse> items
) {
}
