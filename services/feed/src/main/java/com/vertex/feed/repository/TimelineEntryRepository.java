package com.vertex.feed.repository;

import com.vertex.feed.domain.TimelineEntry;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, UUID> {

    @Query("select t from TimelineEntry t where t.ownerId = :owner and t.createdAt < :before order by t.createdAt desc")
    List<TimelineEntry> findHomeBefore(@Param("owner") UUID owner, @Param("before") Instant before, Limit limit);
}
