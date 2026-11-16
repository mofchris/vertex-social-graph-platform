package com.vertex.notify.events;

/**
 * A record that parsed as JSON but isn't a usable social event (no id, recipient or actor). Like a
 * parse failure it can never succeed on retry, so {@code NotifyKafkaConfig} marks it non-retryable
 * and the error handler dead-letters it straight away.
 */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message) {
        super(message);
    }
}
