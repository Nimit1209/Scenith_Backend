package com.example.Scenith.repository;

import com.example.Scenith.entity.StandaloneImageUsage;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StandaloneImageUsageRepository extends JpaRepository<StandaloneImageUsage, Long> {
    Optional<StandaloneImageUsage> findByUserAndUsageMonth(User user, String usageMonth);
}