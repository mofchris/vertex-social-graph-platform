package com.vertex.notify.repository;

import com.vertex.notify.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** Drop ledger entries older than the retention window so the dedupe table can't grow forever. */
    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Count a redelivery against an already-processed event, in one atomic UPDATE: the row lock
     * serializes concurrent duplicates, so no increment is lost and no read-modify-write race exists.
     * Returns 1 if the event was already in the ledger (it is a duplicate), 0 if it is new.
     */
    @Modifying
    @Query("update ProcessedEvent p set p.duplicatesIgnored = p.duplicatesIgnored + 1, "
            + "p.lastDuplicateAt = :at where p.eventId = :id")
    int recordDuplicate(@Param("id") UUID eventId, @Param("at") Instant at);

    /** Total redeliveries the ledger has absorbed (system-wide). */
    @Query("select coalesce(sum(p.duplicatesIgnored), 0) from ProcessedEvent p")
    long sumDuplicatesIgnored();
}
