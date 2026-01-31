package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.UserImageGenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.Optional;

@Repository
public interface UserImageGenUsageRepository extends JpaRepository<UserImageGenUsage, Long> {
    
    @Query("SELECT u FROM UserImageGenUsage u WHERE u.user = :user AND u.year = :year AND u.month = :month")
    Optional<UserImageGenUsage> findByUserAndYearMonth(
        @Param("user") User user,
        @Param("year") int year,
        @Param("month") int month
    );
    
    default Optional<UserImageGenUsage> findByUserAndMonth(User user, YearMonth yearMonth) {
        return findByUserAndYearMonth(user, yearMonth.getYear(), yearMonth.getMonthValue());
    }
}