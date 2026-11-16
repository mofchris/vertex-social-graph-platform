package com.vertex.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A record captured off {@code social.events.DLT}: a message the social-event consumer could not
 * process, with the reason and the coordinates it originally had. Kafka already keeps the DLT topic
 * itself; this copy exists so the system trace can answer "where did my poison message go, and why?"
 * with a simple query instead of a topic scan. Payload and error are stored truncated — they're for
 * a human to recognise, not to reprocess from.
 */
@Entity
@Table(name = "dead_letters")
public class DeadLetter implements Persistable<UUID> {

    public static final int MAX_PAYLOAD = 500;
    public static final int MAX_ERROR = 1000;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "msg_key", updatable = false)
    private String msgKey;

    @Column(updatable = false, length = 1000)
    private String payload;

    @Column(name = "error_message", updatable = false, length = MAX_ERROR)
    private String errorMessage;

    @Column(name = "original_topic", updatable = false)
    private String originalTopic;

    @Column(name = "original_partition", updatable = false)
    private Integer originalPartition;

    @Column(name = "original_offset", updatable = false)
    private Long originalOffset;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    // Lets Spring Data skip the pre-insert SELECT for an assigned id, while still treating a loaded
    // row as existing (so repository deletes work): true only for a freshly constructed instance.
    @Transient
    private boolean isNew = true;

    protected DeadLetter() {
    }

    public DeadLetter(String msgKey, String payload, String errorMessage,
                      String originalTopic, Integer originalPartition, Long originalOffset) {
        this.id = UUID.randomUUID();
        // Everything here came off the wire, so clamp it to the column sizes rather than let an
        // oversized value fail the insert (the capture listener must never choke on a record).
        this.msgKey = truncate(msgKey, 255);
        this.payload = truncate(payload, MAX_PAYLOAD);
        this.errorMessage = truncate(errorMessage, MAX_ERROR);
        this.originalTopic = truncate(originalTopic, 255);
        this.originalPartition = originalPartition;
        this.originalOffset = originalOffset;
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    @PrePersist
    void onCreate() {
        this.receivedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getMsgKey() {
        return msgKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public Integer getOriginalPartition() {
        return originalPartition;
    }

    public Long getOriginalOffset() {
        return originalOffset;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
