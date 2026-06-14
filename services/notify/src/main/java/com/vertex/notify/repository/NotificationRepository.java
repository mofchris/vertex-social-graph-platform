package com.vertex.notify.repository;

import com.vertex.notify.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** The existing unread row to coalesce a new event into, if any. */
    Optional<Notification> findFirstByRecipientIdAndCoalesceKeyAndReadFalse(UUID recipientId, String coalesceKey);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    @Query("select n from Notification n where n.recipientId = :r and n.updatedAt < :before order by n.updatedAt desc")
    List<Notification> findPage(@Param("r") UUID recipientId, @Param("before") Instant before, Limit limit);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipientId = :r and n.read = false")
    int markAllRead(@Param("r") UUID recipientId);
}
