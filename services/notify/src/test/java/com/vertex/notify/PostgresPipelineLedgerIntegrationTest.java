package com.vertex.notify;

import com.vertex.notify.domain.DeadLetter;
import com.vertex.notify.domain.NotificationType;
import com.vertex.notify.domain.ProcessedEvent;
import com.vertex.notify.repository.DeadLetterRepository;
import com.vertex.notify.repository.NotificationRepository;
import com.vertex.notify.repository.ProcessedEventRepository;
import com.vertex.notify.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger and dead-letter changes against a real PostgreSQL (Testcontainers), not just H2's
 * PostgreSQL mode: V3 must apply on the database production runs, the duplicate counter must
 * increment atomically there, and the dead-letter origin constraint must hold.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresPipelineLedgerIntegrationTest {

    @Autowired private NotificationService service;
    @Autowired private NotificationRepository notifications;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private DeadLetterRepository deadLetters;

    @BeforeEach
    void reset() {
        notifications.deleteAll();
        processedEvents.deleteAll();
        deadLetters.deleteAll();
    }

    @Test
    void duplicatesAreCountedOnPostgres() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 2, 99L)).isTrue();
        assertThat(service.ingestEvent(eventId, recipient, NotificationType.FOLLOW, null, actor, 2, 100L)).isFalse();

        ProcessedEvent ledger = processedEvents.findById(eventId).orElseThrow();
        assertThat(ledger.getKafkaPartition()).isEqualTo(2);
        assertThat(ledger.getKafkaOffset()).isEqualTo(99L);
        assertThat(ledger.getDuplicatesIgnored()).isEqualTo(1);
        assertThat(ledger.getLastDuplicateAt()).isNotNull();
        assertThat(processedEvents.sumDuplicatesIgnored()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void deadLetterOriginIsUniqueOnPostgres() {
        String key = UUID.randomUUID().toString();
        deadLetters.save(new DeadLetter(key, "bad", "boom", "social.events", 0, 5L));
        assertThatThrownBy(() -> deadLetters.save(new DeadLetter(key, "bad", "boom", "social.events", 0, 5L)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deadLetters.findRecentByKey(key, Limit.of(10))).hasSize(1);
    }
}
