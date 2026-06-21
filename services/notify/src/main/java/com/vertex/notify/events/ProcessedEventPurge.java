package com.vertex.notify.events;

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
 */
@Component
@Profile("kafka")
public class ProcessedEventPurge {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPurge.class);

    private final ProcessedEventRepository processedEvents;
    private final Duration retention;

    public ProcessedEventPurge(ProcessedEventRepository processedEvents,
                               @Value("${app.notify.dedupe-retention-hours:168}") long retentionHours) {
        this.processedEvents = processedEvents;
        this.retention = Duration.ofHours(retentionHours);
    }

    @Scheduled(fixedDelayString = "${app.notify.dedupe-purge-ms:3600000}")
    @Transactional
    public void purge() {
        int removed = processedEvents.deleteProcessedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.debug("purged {} dedupe-ledger entries past retention", removed);
        }
    }
}
