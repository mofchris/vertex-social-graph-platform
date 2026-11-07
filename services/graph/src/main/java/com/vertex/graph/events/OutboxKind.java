package com.vertex.graph.events;

/**
 * Why an {@link OutboxEvent} row exists. The relay treats every kind identically (publish in order,
 * retry on failure); the kind is purely for the system trace, so a visitor can tell their real
 * action apart from the deliberate failure-mode exercises.
 */
public enum OutboxKind {
    /** A real relationship change (follow, friend request, friend accept). */
    EVENT,
    /** The identical payload (same eventId) of an earlier event, re-enqueued to exercise dedupe. */
    REPLAY,
    /** A deliberately malformed payload, enqueued to exercise the consumer's dead-letter path. */
    POISON
}
