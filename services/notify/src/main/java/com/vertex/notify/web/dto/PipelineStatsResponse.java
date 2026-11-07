package com.vertex.notify.web.dto;

/**
 * System-wide consumer counters, each a live count over the trace tables (so they shrink when the
 * retention purge runs): events in the dedupe ledger, redeliveries it has ignored, captured dead letters.
 */
public record PipelineStatsResponse(
        long processedEvents,
        long duplicatesIgnored,
        long deadLetters
) {
}
