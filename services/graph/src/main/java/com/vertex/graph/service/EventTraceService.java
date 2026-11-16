package com.vertex.graph.service;

import com.vertex.graph.events.OutboxEvent;
import com.vertex.graph.events.OutboxEventWriter;
import com.vertex.graph.events.OutboxRelay;
import com.vertex.graph.exception.NotFoundException;
import com.vertex.graph.exception.PipelineDisabledException;
import com.vertex.graph.repository.OutboxRepository;
import com.vertex.graph.web.dto.OutboxEventResponse;
import com.vertex.graph.web.dto.OutboxEventsPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The Graph half of the system trace: lets a visitor see the outbox rows their own actions produced,
 * and deliberately exercise the pipeline's failure handling.
 *
 * <ul>
 *   <li><b>Replay</b> re-enqueues the identical payload (same {@code eventId}) of one of the caller's
 *       events as a new outbox row. It travels outbox → Kafka again exactly like a real redelivery,
 *       so Notify's dedupe ledger should recognise it and bump its duplicate counter instead of
 *       notifying twice.</li>
 *   <li><b>Poison</b> enqueues a payload that is not valid JSON. The relay publishes it like any
 *       other row; Notify's parse fails and its dead-letter handler routes it to
 *       {@code social.events.DLT}.</li>
 * </ul>
 *
 * Both go through the normal outbox (not straight to Kafka) on purpose: the visitor sees the same
 * staged → published lifecycle as a real event, and the relay's ordering/retry rules apply unchanged.
 */
@Service
public class EventTraceService {

    private static final int MAX_TRACE = 50;

    private final OutboxRepository outbox;
    private final ObjectProvider<OutboxRelay> relay;

    public EventTraceService(OutboxRepository outbox, ObjectProvider<OutboxRelay> relay) {
        this.outbox = outbox;
        this.relay = relay;
    }

    @Transactional(readOnly = true)
    public OutboxEventsPage mine(UUID actor, int limit) {
        int size = Math.clamp(limit, 1, MAX_TRACE);
        return new OutboxEventsPage(outbox.findRecentByActor(actor, Limit.of(size)).stream()
                .map(OutboxEventResponse::from)
                .toList());
    }

    /**
     * Re-enqueue one of the caller's own events. 404 when the event doesn't exist, isn't the caller's
     * (indistinguishable on purpose — don't confirm other people's event ids), or has already been
     * purged from the outbox after its retention window.
     */
    @Transactional
    public OutboxEventResponse replay(UUID actor, UUID eventId) {
        requirePipeline();
        OutboxEvent original = outbox.findFirstByEventIdAndActorIdOrderByCreatedAtAsc(eventId, actor)
                .orElseThrow(() -> new NotFoundException("no such event of yours in the outbox"));
        return OutboxEventResponse.from(outbox.save(OutboxEvent.replayOf(original)));
    }

    /** Enqueue a deliberately malformed message, keyed by the caller so its dead letter is traceable. */
    @Transactional
    public OutboxEventResponse poison(UUID actor) {
        requirePipeline();
        return OutboxEventResponse.from(outbox.save(
                OutboxEvent.poison(OutboxEventWriter.TOPIC, actor, poisonPayload(actor))));
    }

    /**
     * A JSON object cut off mid-string: recognisably ours ({@code "vertexPoison":true}) to anyone
     * reading the dead-letter topic, but unparseable, so the consumer fails on it deterministically.
     */
    static String poisonPayload(UUID actor) {
        return "{\"vertexPoison\":true,\"actorId\":\"" + actor + "\",\"note\":\"deliberately truncat";
    }

    /**
     * Without the {@code kafka} profile there is no relay, so a staged row would sit unpublished
     * forever (and never be purged — only published rows are). Refuse rather than mislead.
     */
    private void requirePipeline() {
        if (relay.getIfAvailable() == null) {
            throw new PipelineDisabledException("the event pipeline is not enabled in this deployment");
        }
    }
}
