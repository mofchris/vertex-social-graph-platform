package com.vertex.graph.events;

import com.vertex.graph.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the {@link OutboxEvent} table to Kafka — the publishing half of the transactional outbox.
 * Runs only under the {@code kafka} profile.
 *
 * <p>Each tick claims a batch of unpublished rows oldest-first, publishes each, and stamps it
 * published in the same transaction. If the broker is unreachable the row keeps {@code publishedAt}
 * null and is retried on the next tick — nothing is lost. We stop the batch on the first failure so
 * a dead broker doesn't make us spin, and so we never publish a newer row before an older one that
 * just failed.
 *
 * <p>Delivery is at-least-once by construction: we can publish a row and then crash before stamping
 * it, so the consumer must dedupe on the event id (it does — see Notify's {@code processed_events}).
 *
 * <p>One relay instance is assumed. Scaled horizontally, two relays could publish the same row
 * twice; that is harmless given the idempotent consumer, but the textbook fix is to claim rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}.
 */
@Component
@Profile("kafka")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       @Value("${app.outbox.batch-size:100}") int batchSize,
                       @Value("${app.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                       @Value("${app.outbox.retention-hours:24}") long retentionHours) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
        this.retention = Duration.ofHours(retentionHours);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            try {
                // Block for the broker ack (acks=all) so we only mark a row published once it is
                // durable. A timeout/failure leaves it unpublished for the next tick to retry.
                kafka.send(event.getTopic(), event.getMsgKey(), event.getPayload())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception broker) {
                log.warn("outbox relay could not publish {} to {}; will retry next tick",
                        event.getId(), event.getTopic(), broker);
                return; // keep ordering: don't skip ahead of a row that just failed
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.purge-ms:3600000}")
    @Transactional
    public void purgePublished() {
        int removed = outbox.deletePublishedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.debug("purged {} published outbox rows", removed);
        }
    }
}
