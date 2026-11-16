package com.vertex.notify.web.dto;

import com.vertex.notify.domain.DeadLetter;

import java.time.Instant;
import java.util.UUID;

/**
 * A message captured off the dead-letter topic: why the consumer rejected it and where it originally
 * sat in Kafka. {@code payloadPreview} is at most the first 500 characters of the raw message.
 */
public record DeadLetterResponse(
        UUID id,
        String error,
        String payloadPreview,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset,
        Instant receivedAt
) {
    public static DeadLetterResponse from(DeadLetter d) {
        return new DeadLetterResponse(d.getId(), d.getErrorMessage(), d.getPayload(), d.getOriginalTopic(),
                d.getOriginalPartition(), d.getOriginalOffset(), d.getReceivedAt());
    }
}
