package com.vertex.notify.repository;

import com.vertex.notify.domain.DeadLetter;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {

    /** Dead letters whose message key is {@code key} (a user id), newest first. */
    @Query("select d from DeadLetter d where d.msgKey = :key order by d.receivedAt desc, d.id desc")
    List<DeadLetter> findRecentByKey(@Param("key") String key, Limit limit);

    /** Same bound as the dedupe ledger, so neither trace table grows without limit. */
    @Modifying
    @Query("delete from DeadLetter d where d.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}
