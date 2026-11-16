package com.vertex.notify.web;

import com.vertex.notify.service.PipelineService;
import com.vertex.notify.web.dto.DeadLettersPage;
import com.vertex.notify.web.dto.PipelineEventResponse;
import com.vertex.notify.web.dto.PipelineStatsResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The consumer's side of the system trace. The UI polls {@code /v1/pipeline/events/{eventId}} after
 * an action to show "Notify consumed it from partition P offset O", and the dead-letter list after a
 * deliberate poison message.
 */
@RestController
public class PipelineController {

    private final PipelineService pipeline;

    public PipelineController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    private static UUID caller(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    @GetMapping("/v1/pipeline/events/{eventId}")
    public PipelineEventResponse event(@PathVariable UUID eventId, Authentication auth) {
        return pipeline.event(caller(auth), eventId);
    }

    @GetMapping("/v1/pipeline/dead-letters/mine")
    public DeadLettersPage deadLetters(@RequestParam(defaultValue = "10") int limit, Authentication auth) {
        return pipeline.deadLetters(caller(auth), limit);
    }

    /** System-wide counts (not scoped to the caller): ledger size, duplicates ignored, dead letters. */
    @GetMapping("/v1/pipeline/stats")
    public PipelineStatsResponse stats() {
        return pipeline.stats();
    }
}
