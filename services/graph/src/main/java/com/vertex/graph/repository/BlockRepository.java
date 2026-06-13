package com.vertex.graph.repository;

import com.vertex.graph.domain.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BlockRepository extends JpaRepository<Block, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    long deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    /** True if either user has blocked the other — blocking is symmetric for enforcement. */
    @Query("select case when count(b) > 0 then true else false end from Block b "
            + "where (b.blockerId = :a and b.blockedId = :b) "
            + "or (b.blockerId = :b and b.blockedId = :a)")
    boolean existsBetween(@Param("a") UUID a, @Param("b") UUID b);
}
