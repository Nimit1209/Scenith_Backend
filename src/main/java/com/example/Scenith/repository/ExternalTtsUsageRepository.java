// ExternalTtsUsageRepository.java
package com.example.Scenith.repository;


import com.example.Scenith.entity.ExternalTtsUsage;
import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.YearMonth;
import java.util.Optional;

public interface ExternalTtsUsageRepository extends JpaRepository<ExternalTtsUsage, Long> {
    Optional<ExternalTtsUsage> findByUserAndProviderAndMonth(User user, SoleTTS.TtsProvider provider, YearMonth month);
}