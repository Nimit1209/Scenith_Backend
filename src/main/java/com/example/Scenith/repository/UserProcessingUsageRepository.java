package com.example.Scenith.repository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserProcessingUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProcessingUsageRepository extends JpaRepository<UserProcessingUsage, Long> {
    
    Optional<UserProcessingUsage> findByUserAndServiceTypeAndYearMonth(
            User user, String serviceType, String yearMonth);
}