package com.vertex.notify.service;

import com.vertex.notify.exception.NotFoundException;
import com.vertex.notify.repository.DeadLetterRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import com.vertex.notify.web.dto.DeadLetterResponse;
import com.vertex.notify.web.dto.DeadLettersPage;
import com.vertex.notify.web.dto.PipelineEventResponse;
import com.vertex.notify.web.dto.PipelineStatsResponse;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The Notify half of the system trace: read-only views over the dedupe ledger and the captured dead
 * letters, so the UI can show what the consumer really did with a visitor's event. Everything here
 * reads rows the pipeline wrote; nothing is synthesized.
 */
@Service
public class PipelineService {

    private static final int MAX_DEAD_LETTERS = 50;

    private final ProcessedEventRepository processedEvents;
    private final DeadLetterRepository deadLetters;

    public PipelineService(ProcessedEventRepository processedEvents, DeadLetterRepository deadLetters) {
        this.processedEvents = processedEvents;
        this.deadLetters = deadLetters;
    }

    /**
     * How the consumer handled {@code eventId}. Only the event's actor or recipient may look it up;
     * anyone else gets the same 404 as "not processed yet", so ids can't be probed.
     */
    @Transactional(readOnly = true)
    public PipelineEventResponse event(UUID caller, UUID eventId) {
        return processedEvents.findById(eventId)
                .filter(p -> p.concerns(caller))
                .map(PipelineEventResponse::from)
                .orElseThrow(() -> new NotFoundException("event not processed (yet)"));
    }

    /** Dead letters keyed by the caller — Graph keys a visitor's poison message by their own id. */
    @Transactional(readOnly = true)
    public DeadLettersPage deadLetters(UUID caller, int limit) {
        int size = Math.clamp(limit, 1, MAX_DEAD_LETTERS);
        return new DeadLettersPage(deadLetters.findRecentByKey(caller.toString(), Limit.of(size)).stream()
                .map(DeadLetterResponse::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public PipelineStatsResponse stats() {
        return new PipelineStatsResponse(
                processedEvents.count(),
                processedEvents.sumDuplicatesIgnored(),
                deadLetters.count());
    }
}
