package com.vertex.graph.exception;

/**
 * The event pipeline (outbox relay → Kafka) isn't running in this deployment — the {@code kafka}
 * profile is off. Enqueuing a replay or poison row would leave it stuck in the outbox forever, so we
 * refuse instead. Maps to HTTP 503.
 */
public class PipelineDisabledException extends RuntimeException {
    public PipelineDisabledException(String message) {
        super(message);
    }
}
