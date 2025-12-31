package com.example.Scenith.repository;

import com.example.Scenith.entity.ScheduledUpgradeEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledUpgradeEmailRepository extends JpaRepository<ScheduledUpgradeEmail, Long> {
    List<ScheduledUpgradeEmail> findBySentFalseAndScheduledTimeBefore(LocalDateTime now);
    List<ScheduledUpgradeEmail> findByUserIdAndSentFalse(Long userId);
}