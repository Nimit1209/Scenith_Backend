package com.example.Scenith.repository;


import com.example.Scenith.entity.ExternalTtsDailyUsage;
import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface ExternalTtsDailyUsageRepository extends JpaRepository<ExternalTtsDailyUsage, Long> {
    Optional<ExternalTtsDailyUsage> findByUserAndProviderAndUsageDate(User user, SoleTTS.TtsProvider provider, LocalDate date);
}