package com.vertex.feed.repository;

import com.vertex.feed.domain.Post;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    @Query("select p from Post p where p.authorId = :author and p.createdAt < :before order by p.createdAt desc")
    List<Post> findByAuthorBefore(@Param("author") UUID author, @Param("before") Instant before, Limit limit);

    // Fan-out-on-read: recent posts by the celebrities a reader follows.
    @Query("select p from Post p where p.authorId in :authors and p.createdAt < :before order by p.createdAt desc")
    List<Post> findByAuthorsBefore(@Param("authors") Collection<UUID> authors, @Param("before") Instant before, Limit limit);
}
