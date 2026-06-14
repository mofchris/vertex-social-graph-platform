package com.vertex.notify.service;

import com.vertex.notify.domain.Notification;
import com.vertex.notify.domain.NotificationType;
import com.vertex.notify.repository.NotificationRepository;
import com.vertex.notify.web.dto.NotificationResponse;
import com.vertex.notify.web.dto.NotificationsPage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository repository;
    private final SseEmitters sse;

    public NotificationService(NotificationRepository repository, SseEmitters sse) {
        this.repository = repository;
        this.sse = sse;
    }

    /**
     * Turn an event into a notification, coalescing into the recipient's existing unread row
     * for the same (type, target) if one exists. Pushes the result to any open SSE stream.
     */
    @Transactional
    public NotificationResponse ingest(UUID recipientId, NotificationType type, UUID targetId, UUID actorId) {
        String key = Notification.coalesceKey(type, targetId);
        Optional<Notification> existing =
                repository.findFirstByRecipientIdAndCoalesceKeyAndReadFalse(recipientId, key);

        Notification notification;
        if (existing.isPresent()) {
            notification = existing.get();
            notification.addActor(actorId); // bump count + latest actor; @PreUpdate bumps updatedAt
        } else {
            notification = new Notification(recipientId, type, targetId, actorId);
        }
        // flush so counts/timestamps are current before we serialize and push.
        notification = repository.saveAndFlush(notification);

        NotificationResponse response = NotificationResponse.from(notification);
        sse.push(recipientId, response);
        return response;
    }

    @Transactional(readOnly = true)
    public NotificationsPage list(UUID recipientId, String cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        List<Notification> rows = repository.findPage(recipientId, parseCursor(cursor), Limit.of(pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<Notification> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<NotificationResponse> items = page.stream().map(NotificationResponse::from).toList();
        String nextCursor = hasMore
                ? String.valueOf(page.get(page.size() - 1).getUpdatedAt().toEpochMilli())
                : null;
        return new NotificationsPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return repository.countByRecipientIdAndReadFalse(recipientId);
    }

    @Transactional
    public int markAllRead(UUID recipientId) {
        return repository.markAllRead(recipientId);
    }

    private static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.now().plusSeconds(1);
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(cursor));
        } catch (NumberFormatException e) {
            return Instant.now().plusSeconds(1);
        }
    }
}
