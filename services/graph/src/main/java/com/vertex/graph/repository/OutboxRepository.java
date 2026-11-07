package com.vertex.graph.repository;

import com.vertex.graph.events.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest-first so the relay preserves the order events were produced in. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Limit limit);

    /** Bulk-delete already-published rows past their retention window (keeps the table small). */
    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);

    /** The caller's own rows, newest first — the system trace. Id breaks timestamp ties deterministically. */
    @Query("select e from OutboxEvent e where e.actorId = :actor order by e.createdAt desc, e.id desc")
    List<OutboxEvent> findRecentByActor(@Param("actor") UUID actorId, Limit limit);

    /**
     * The earliest row carrying {@code eventId} that the actor caused. Scoping by actor is the
     * authorization check: you can only replay your own events. Replays share the original's event
     * id, so "earliest" picks the original when it still exists (any copy has the identical payload).
     */
    Optional<OutboxEvent> findFirstByEventIdAndActorIdOrderByCreatedAtAsc(UUID eventId, UUID actorId);
}
