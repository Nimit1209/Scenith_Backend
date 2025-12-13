package com.example.Scenith.repository;

import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Modifying
    @Query("DELETE FROM User u WHERE u.emailVerified = false AND u.createdAt < :threshold")
    void deleteByIsEmailVerifiedFalseAndCreatedBefore(LocalDateTime threshold);

    // Add this method inside your UserRepository interface
    @Query("SELECT u FROM User u WHERE u.planExpiresAt IS NOT NULL AND u.planExpiresAt < :now AND u.role != 'BASIC'")
    List<User> findAllExpiredPremiumUsers(@Param("now") LocalDateTime now);
}