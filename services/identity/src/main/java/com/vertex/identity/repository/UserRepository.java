package com.vertex.identity.repository;

import com.vertex.identity.domain.User;
import com.vertex.identity.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndStatus(String email, UserStatus status);

    Optional<User> findByIdAndStatus(UUID id, UserStatus status);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
