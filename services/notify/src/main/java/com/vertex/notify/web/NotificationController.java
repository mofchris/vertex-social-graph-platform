package com.vertex.notify.web;

import com.vertex.notify.service.NotificationService;
import com.vertex.notify.service.SseEmitters;
import com.vertex.notify.web.dto.CreateEventRequest;
import com.vertex.notify.web.dto.NotificationResponse;
import com.vertex.notify.web.dto.NotificationsPage;
import com.vertex.notify.web.dto.UnreadCountResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
public class NotificationController {

    private final NotificationService notifications;
    private final SseEmitters sse;

    public NotificationController(NotificationService notifications, SseEmitters sse) {
        this.notifications = notifications;
        this.sse = sse;
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    /** Report a social event; the acting user is the caller. Coalesced into a notification. */
    @PostMapping("/v1/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NotificationResponse event(@Valid @RequestBody CreateEventRequest request, Authentication auth) {
        return notifications.ingest(request.recipientId(), request.type(), request.targetId(), actor(auth));
    }

    /** The caller's notifications, newest first. */
    @GetMapping("/v1/notifications")
    public NotificationsPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            Authentication auth) {
        return notifications.list(actor(auth), cursor, limit);
    }

    @GetMapping("/v1/notifications/unread-count")
    public UnreadCountResponse unreadCount(Authentication auth) {
        return new UnreadCountResponse(notifications.unreadCount(actor(auth)));
    }

    @PostMapping("/v1/notifications/read")
    public ResponseEntity<Void> markAllRead(Authentication auth) {
        notifications.markAllRead(actor(auth));
        return ResponseEntity.noContent().build();
    }

    /** Real-time stream of the caller's notifications (Server-Sent Events). */
    @GetMapping("/v1/notifications/stream")
    public SseEmitter stream(Authentication auth) {
        return sse.register(actor(auth));
    }
}
