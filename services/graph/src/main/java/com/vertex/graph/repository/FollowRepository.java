package com.vertex.graph.repository;

import com.vertex.graph.domain.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long deleteByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFollowerId(UUID followerId);   // how many this user follows

    long countByFolloweeId(UUID followeeId);    // how many follow this user

    /** Remove follows in both directions between two users (used when a block is created). */
    @Modifying
    @Query("delete from Follow f where (f.followerId = :a and f.followeeId = :b) "
            + "or (f.followerId = :b and f.followeeId = :a)")
    int deleteBetween(@Param("a") UUID a, @Param("b") UUID b);
}
