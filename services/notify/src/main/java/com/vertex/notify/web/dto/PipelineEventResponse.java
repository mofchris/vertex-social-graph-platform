package com.vertex.notify.web.dto;

import com.vertex.notify.domain.ProcessedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * What Notify's consumer did with one event: when it processed it, where in Kafka it read it from,
 * and how many redeliveries of the same event id the dedupe ledger has ignored since.
 * {@code lastDuplicateAt} is null until the first duplicate arrives.
 */
public record PipelineEventResponse(
        UUID eventId,
        String type,
        Instant processedAt,
        Integer partition,
        Long offset,
        int duplicatesIgnored,
        Instant lastDuplicateAt
) {
    public static PipelineEventResponse from(ProcessedEvent p) {
        return new PipelineEventResponse(p.getEventId(), p.getEventType(), p.getProcessedAt(),
                p.getKafkaPartition(), p.getKafkaOffset(), p.getDuplicatesIgnored(), p.getLastDuplicateAt());
    }
}
