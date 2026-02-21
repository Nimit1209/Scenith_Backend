package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.imageentity.ElementDownloadUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ElementDownloadUsageRepository extends JpaRepository<ElementDownloadUsage, Long> {

    Optional<ElementDownloadUsage> findByUserIdAndUsageDate(Long userId, LocalDate date);

    @Query("SELECT COALESCE(SUM(e.monthlyCount), 0) FROM ElementDownloadUsage e WHERE e.userId = :userId AND e.yearMonth = :yearMonth")
    int sumMonthlyCountByUserIdAndYearMonth(Long userId, String yearMonth);
}