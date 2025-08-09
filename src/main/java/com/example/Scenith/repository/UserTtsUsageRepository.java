package com.example.Scenith.repository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserTtsUsage;
import java.time.YearMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserTtsUsageRepository extends JpaRepository<UserTtsUsage, Long> {
    Optional<UserTtsUsage> findByUserAndMonth(User user, YearMonth month);
}