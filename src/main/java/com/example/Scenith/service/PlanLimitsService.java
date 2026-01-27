package com.example.Scenith.service;


import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserPlan;
import com.example.Scenith.enums.PlanType;
import com.example.Scenith.repository.UserPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlanLimitsService {

    private final UserPlanRepository userPlanRepository;

    // ==================== AI VOICE LIMITS ====================

    public long getMonthlyTtsLimit(User user) {
        // Get limit from bundled plan
        long bundledLimit = switch (user.getRole()) {
            case BASIC -> 3000;
            case CREATOR -> 60000;
            case STUDIO -> 200000;
            case ADMIN -> -1;
        };

        // Get limit from individual plan (if exists)
        Optional<UserPlan> voicePlan = getActivePlan(user, PlanType.AI_VOICE_PRO);
        if (voicePlan.isPresent()) {
            long individualLimit = 50000;
            // Return the BETTER limit (-1 means unlimited, so it always wins)
            return getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public long getDailyTtsLimit(User user) {
        long bundledLimit = switch (user.getRole()) {
            case BASIC -> 700;
            case CREATOR -> 15000;
            case STUDIO -> -1;
            case ADMIN -> -1;
        };

        Optional<UserPlan> voicePlan = getActivePlan(user, PlanType.AI_VOICE_PRO);
        if (voicePlan.isPresent()) {
            long individualLimit = 10000;
            return getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public long getMaxCharsPerRequest(User user) {
        long bundledLimit = switch (user.getRole()) {
            case BASIC -> 250;
            case CREATOR -> 3500;
            case STUDIO -> 5000;
            case ADMIN -> 10000;
        };

        Optional<UserPlan> voicePlan = getActivePlan(user, PlanType.AI_VOICE_PRO);
        if (voicePlan.isPresent()) {
            long individualLimit = 2500;
            return getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    // ==================== SUBTITLE LIMITS ====================

    public int getMaxVideoProcessingPerMonth(User user) {
        int bundledLimit = switch (user.getRole()) {
            case BASIC -> 5;
            case CREATOR -> 45;
            case STUDIO, ADMIN -> -1;
        };

        Optional<UserPlan> subtitlePlan = getActivePlan(user, PlanType.AI_SUBTITLE_PRO);
        if (subtitlePlan.isPresent()) {
            int individualLimit = 30;
            return (int) getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public int getMaxVideoLengthMinutes(User user) {
        int bundledLimit = switch (user.getRole()) {
            case BASIC -> 5;
            case CREATOR -> 30;
            case STUDIO, ADMIN -> -1;
        };

        Optional<UserPlan> subtitlePlan = getActivePlan(user, PlanType.AI_SUBTITLE_PRO);
        if (subtitlePlan.isPresent()) {
            int individualLimit = 20;
            return (int) getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public String getMaxAllowedQuality(User user) {
        String bundledQuality = switch (user.getRole()) {
            case BASIC -> "720p";
            case CREATOR -> "1440p";
            case STUDIO, ADMIN -> "4k";
        };

        Optional<UserPlan> subtitlePlan = getActivePlan(user, PlanType.AI_SUBTITLE_PRO);
        if (subtitlePlan.isPresent()) {
            String individualQuality = "1440p";
            return getBetterQuality(bundledQuality, individualQuality);
        }

        return bundledQuality;
    }

    // ==================== VIDEO SPEED LIMITS ====================

    public int getMaxSpeedProcessingPerMonth(User user) {
        int bundledLimit = switch (user.getRole()) {
            case BASIC -> 5;
            case CREATOR -> 45;
            case STUDIO, ADMIN -> -1;
        };

        Optional<UserPlan> speedPlan = getActivePlan(user, PlanType.AI_SPEED_PRO);
        if (speedPlan.isPresent()) {
            int individualLimit = 30;
            return (int) getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public int getMaxSpeedVideoLengthMinutes(User user) {
        int bundledLimit = switch (user.getRole()) {
            case BASIC -> 5;
            case CREATOR -> 30;
            case STUDIO, ADMIN -> -1;
        };

        Optional<UserPlan> speedPlan = getActivePlan(user, PlanType.AI_SPEED_PRO);
        if (speedPlan.isPresent()) {
            int individualLimit = 20;
            return (int) getBetterLimit(bundledLimit, individualLimit);
        }

        return bundledLimit;
    }

    public String getMaxSpeedAllowedQuality(User user) {
        String bundledQuality = switch (user.getRole()) {
            case BASIC -> "720p";
            case CREATOR -> "1440p";
            case STUDIO, ADMIN -> "4k";
        };

        Optional<UserPlan> speedPlan = getActivePlan(user, PlanType.AI_SPEED_PRO);
        if (speedPlan.isPresent()) {
            String individualQuality = "1440p";
            return getBetterQuality(bundledQuality, individualQuality);
        }

        return bundledQuality;
    }

    // ==================== QUALITY VALIDATION ====================

    public boolean isQualityAllowed(User user, String quality) {
        int requestedQuality = parseQuality(quality);
        int maxQuality = parseQuality(getMaxAllowedQuality(user));
        return requestedQuality <= maxQuality;
    }

    public boolean isSpeedQualityAllowed(User user, String quality) {
        int requestedQuality = parseQuality(quality);
        int maxQuality = parseQuality(getMaxSpeedAllowedQuality(user));
        return requestedQuality <= maxQuality;
    }

    // ==================== HELPER METHODS ====================

    private Optional<UserPlan> getActivePlan(User user, PlanType planType) {
        return userPlanRepository.findActiveUserPlan(user, planType, LocalDateTime.now());
    }

    /**
     * Returns the better limit between two limits.
     * -1 means unlimited, so it always wins.
     * Otherwise, returns the higher value.
     */
    private long getBetterLimit(long limit1, long limit2) {
        // If either is unlimited (-1), return unlimited
        if (limit1 == -1 || limit2 == -1) {
            return -1;
        }
        // Otherwise return the higher limit
        return Math.max(limit1, limit2);
    }

    /**
     * Returns the better quality between two quality strings.
     * Higher resolution is better.
     */
    private String getBetterQuality(String quality1, String quality2) {
        int q1 = parseQuality(quality1);
        int q2 = parseQuality(quality2);
        return q1 >= q2 ? quality1 : quality2;
    }

    private int parseQuality(String quality) {
        return switch (quality.toLowerCase()) {
            case "144p" -> 144;
            case "240p" -> 240;
            case "360p" -> 360;
            case "480p" -> 480;
            case "720p" -> 720;
            case "1080p" -> 1080;
            case "1440p", "2k" -> 1440;
            case "4k" -> 2160;
            default -> 0;
        };
    }

    // ==================== PUBLIC UTILITY ====================

    /**
     * Get all active plans for a user (useful for displaying in UI)
     */
    public java.util.List<UserPlan> getActiveUserPlans(User user) {
        java.util.List<UserPlan> plans = userPlanRepository.findByUserAndActiveTrue(user);
        LocalDateTime now = LocalDateTime.now();
        return plans.stream()
                .filter(plan -> plan.getExpiryDate() == null || plan.getExpiryDate().isAfter(now))
                .toList();
    }
}