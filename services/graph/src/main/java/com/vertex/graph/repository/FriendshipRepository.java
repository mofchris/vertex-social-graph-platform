package com.vertex.graph.repository;

import com.vertex.graph.domain.Friendship;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

    Optional<Friendship> findByPairKey(String pairKey);

    long deleteByPairKey(String pairKey);

    @Query("select count(f) from Friendship f "
            + "where f.status = com.vertex.graph.domain.FriendshipStatus.ACCEPTED "
            + "and (f.requesterId = :u or f.addresseeId = :u)")
    long countAcceptedFor(@Param("u") UUID userId);

    // Keyset pagination by id (stable sort key — no offset, so no skips/dupes on a mutating list).
    @Query("select f from Friendship f "
            + "where f.status = com.vertex.graph.domain.FriendshipStatus.ACCEPTED "
            + "and (f.requesterId = :u or f.addresseeId = :u) order by f.id desc")
    List<Friendship> findAcceptedFirstPage(@Param("u") UUID userId, Limit limit);

    @Query("select f from Friendship f "
            + "where f.status = com.vertex.graph.domain.FriendshipStatus.ACCEPTED "
            + "and (f.requesterId = :u or f.addresseeId = :u) and f.id < :cursor order by f.id desc")
    List<Friendship> findAcceptedAfter(@Param("u") UUID userId, @Param("cursor") UUID cursor, Limit limit);
}
