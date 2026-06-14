package com.vertex.graph.repository;

import com.vertex.graph.domain.Follow;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long deleteByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFollowerId(UUID followerId);   // how many this user follows

    long countByFolloweeId(UUID followeeId);    // how many follow this user

    // Keyset pagination over follow edges, ordered by id desc.
    @Query("select f from Follow f where f.followeeId = :u order by f.id desc")
    List<Follow> followersFirstPage(@Param("u") UUID u, Limit limit);

    @Query("select f from Follow f where f.followeeId = :u and f.id < :cursor order by f.id desc")
    List<Follow> followersAfter(@Param("u") UUID u, @Param("cursor") UUID cursor, Limit limit);

    @Query("select f from Follow f where f.followerId = :u order by f.id desc")
    List<Follow> followingFirstPage(@Param("u") UUID u, Limit limit);

    @Query("select f from Follow f where f.followerId = :u and f.id < :cursor order by f.id desc")
    List<Follow> followingAfter(@Param("u") UUID u, @Param("cursor") UUID cursor, Limit limit);

    /** Remove follows in both directions between two users (used when a block is created). */
    @Modifying
    @Query("delete from Follow f where (f.followerId = :a and f.followeeId = :b) "
            + "or (f.followerId = :b and f.followeeId = :a)")
    int deleteBetween(@Param("a") UUID a, @Param("b") UUID b);
}
