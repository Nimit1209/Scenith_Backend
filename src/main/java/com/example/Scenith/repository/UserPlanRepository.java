package com.example.Scenith.repository;


import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserPlan;
import com.example.Scenith.enums.PlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    List<UserPlan> findByUserAndActiveTrue(User user);

    @Query("SELECT up FROM UserPlan up WHERE up.user = :user AND up.planType = :planType " +
            "AND up.active = true AND (up.expiryDate IS NULL OR up.expiryDate > :now)")
    Optional<UserPlan> findActiveUserPlan(@Param("user") User user,
                                          @Param("planType") PlanType planType,
                                          @Param("now") LocalDateTime now);

    // ADD THIS for scheduled job
    List<UserPlan> findByActiveTrueAndExpiryDateBefore(LocalDateTime dateTime);
}