package com.vertex.feed.repository;

import com.vertex.feed.domain.Celebrity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CelebrityRepository extends JpaRepository<Celebrity, UUID> {

    List<Celebrity> findByUserIdIn(Collection<UUID> userIds);
}
