package com.vertex.notify.service;

import com.vertex.notify.domain.NotificationType;
import com.vertex.notify.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A notification is pushed over SSE only once its transaction has committed, so a write that rolls
 * back is never shown to anyone.
 */
@SpringBootTest
class PushAfterCommitIntegrationTest {

    @Autowired private NotificationService service;
    @Autowired private NotificationRepository notifications;
    @Autowired private TransactionTemplate tx;
    @MockitoSpyBean private SseEmitters sse;

    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void reset() {
        notifications.deleteAll();
        clearInvocations(sse);
    }

    @Test
    void aRolledBackWriteIsNeverPushed() {
        tx.executeWithoutResult(status -> {
            service.ingest(recipient, NotificationType.FOLLOW, null, UUID.randomUUID());
            // Still inside the transaction: nothing has been pushed yet.
            verify(sse, never()).push(any(), any());
            status.setRollbackOnly();
        });

        verify(sse, never()).push(any(), any());
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void aCommittedWriteIsPushedOnceAfterCommit() {
        tx.executeWithoutResult(status -> {
            service.ingest(recipient, NotificationType.FOLLOW, null, UUID.randomUUID());
            verify(sse, never()).push(any(), any());
        });

        verify(sse).push(eq(recipient), any());
    }
}
