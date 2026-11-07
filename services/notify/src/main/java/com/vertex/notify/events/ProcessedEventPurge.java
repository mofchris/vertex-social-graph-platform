package com.vertex.notify.events;

import com.vertex.notify.repository.DeadLetterRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Trims the {@code processed_events} dedupe ledger so it stays bounded. The ledger only needs to
 * remember an event id for as long as Kafka might redeliver it; past that window (longer than the
 * broker's log retention) the entry is dead weight, so we drop it — EDGE_CASES.md, "idempotency-key
 * store + TTL". Runs only under the {@code kafka} profile, where the ledger is actually written.
 *
 * <p>Captured {@code dead_letters} get the same retention: they're a trace for humans, and the DLT
 * topic itself (subject to the broker's own retention) remains the source of truth.
 */
@Component
@Profile("kafka")
public class ProcessedEventPurge {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPurge.class);

    private final ProcessedEventRepository processedEvents;
    private final DeadLetterRepository deadLetters;
    private final Duration retention;

    public ProcessedEventPurge(ProcessedEventRepository processedEvents,
                               DeadLetterRepository deadLetters,
                               @Value("${app.notify.dedupe-retention-hours:168}") long retentionHours) {
        this.processedEvents = processedEvents;
        this.deadLetters = deadLetters;
        this.retention = Duration.ofHours(retentionHours);
    }

    @Scheduled(fixedDelayString = "${app.notify.dedupe-purge-ms:3600000}")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(retention);
        int removed = processedEvents.deleteProcessedBefore(cutoff);
        int removedDead = deadLetters.deleteReceivedBefore(cutoff);
        if (removed > 0 || removedDead > 0) {
            log.debug("purged {} dedupe-ledger entries and {} dead letters past retention", removed, removedDead);
        }
    }
}
