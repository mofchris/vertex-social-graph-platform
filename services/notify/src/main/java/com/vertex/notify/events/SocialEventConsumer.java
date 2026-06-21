package com.vertex.notify.events;

import com.vertex.notify.domain.NotificationType;
import com.vertex.notify.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the social events Graph publishes and turns them into notifications — only under the
 * {@code kafka} profile, so the default JDK-only build needs no broker and starts no consumer.
 *
 * <p>The container commits offsets only after this method returns normally (at-least-once). If we
 * crash mid-batch the events redeliver; {@link NotificationService#ingestEvent} dedupes on
 * {@code eventId}, so redelivery is harmless. A message we can't parse is left to throw: the
 * configured dead-letter error handler ({@code NotifyKafkaConfig}) routes it to {@code
 * social.events.DLT} instead of letting it block the partition forever (the poison-pill defence in
 * EDGE_CASES.md). An event whose <em>type</em> we simply don't model yet is not an error — it is
 * dropped quietly, so a new producer can add event kinds without breaking this consumer.
 */
@Component
@Profile("kafka")
public class SocialEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SocialEventConsumer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationService notifications;

    public SocialEventConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "social.events", groupId = "notify")
    public void onSocialEvent(String payload) {
        // A parse failure throws on purpose: the dead-letter error handler routes the poison record
        // to social.events.DLT rather than letting it crash-loop the partition.
        IncomingSocialEvent event = JSON.readValue(payload, IncomingSocialEvent.class);

        NotificationType type = mapType(event.type());
        if (type == null) {
            log.warn("dropping social event {} with unknown type '{}'", event.eventId(), event.type());
            return; // forward-compatible: an event kind we don't model yet is not a failure
        }

        notifications.ingestEvent(event.eventId(), event.recipientId(), type, event.targetId(), event.actorId());
    }

    /** Map Graph's event vocabulary onto Notify's; unknown kinds return null and are dropped. */
    private static NotificationType mapType(String wireType) {
        if (wireType == null) {
            return null;
        }
        try {
            return NotificationType.valueOf(wireType);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
