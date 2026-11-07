package com.vertex.graph.web;

import com.vertex.graph.service.EventTraceService;
import com.vertex.graph.web.dto.OutboxEventResponse;
import com.vertex.graph.web.dto.OutboxEventsPage;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The system trace: the caller's own outbox rows, plus the two deliberate pipeline exercises
 * (replay an event to test dedupe, enqueue a poison message to test the dead-letter topic).
 * Everything is scoped to the authenticated caller.
 */
@RestController
public class EventTraceController {

    private final EventTraceService trace;

    public EventTraceController(EventTraceService trace) {
        this.trace = trace;
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    /** The caller's recent outbox rows, newest first; {@code publishedAt == null} means still queued. */
    @GetMapping("/v1/events/mine")
    public OutboxEventsPage mine(@RequestParam(defaultValue = "10") int limit, Authentication auth) {
        return trace.mine(actor(auth), limit);
    }

    /** Re-enqueue the identical payload (same eventId) of one of the caller's events. */
    @PostMapping("/v1/events/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OutboxEventResponse replay(@PathVariable UUID eventId, Authentication auth) {
        return trace.replay(actor(auth), eventId);
    }

    /** Enqueue a deliberately malformed message that Notify will dead-letter. */
    @PostMapping("/v1/events/poison")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OutboxEventResponse poison(Authentication auth) {
        return trace.poison(actor(auth));
    }
}
