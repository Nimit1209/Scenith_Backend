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
        if (user.isAdmin()) return -1;
        if (isStudio(user))   return 250000;
        if (isCreator(user))  return 75000;
        if (isCreatorLite(user)) return 10000;
        return 2000;
    }

    public long getDailyTtsLimit(User user) {
        if (user.isAdmin()) return -1;
        if (isStudio(user))   return -1;
        if (isCreator(user))  return 20000;
        if (isCreatorLite(user)) return 2500;
        return 500;
    }
    public long getMaxCharsPerRequest(User user) {
        if (user.isAdmin()) return 10000;
        if (isStudio(user))   return 6000;
        if (isCreator(user))  return 4000;
        if (isCreatorLite(user)) return 700;
        return 200;
    }


    // ==================== SUBTITLE LIMITS ====================

    public int getMaxVideoProcessingPerMonth(User user) {
        if (user.isAdmin() || isStudio(user)) return -1;
        if (isCreator(user)) return 60;
        if (isCreatorLite(user)) return 30;
        return 5;
    }


    public int getMaxVideoLengthMinutes(User user) {
        if (user.isAdmin() || isStudio(user)) return -1;
        if (isCreator(user)) return 30;
        if (isCreatorLite(user)) return 10;
        return 5;
    }

    public String getMaxAllowedQuality(User user) {
        if (user.isAdmin() || isStudio(user)) return "4k";
        if (isCreator(user)) return "1440p";
        if (isCreatorLite(user)) return "1080p";
        return "720p";
    }


    // ==================== VIDEO SPEED LIMITS ====================

    public int getMaxSpeedProcessingPerMonth(User user) {
        if (user.isAdmin() || isStudio(user)) return -1;
        if (isCreator(user)) return 60;
        if (isCreatorLite(user)) return 30;
        return 5;
    }
    public int getMaxSpeedVideoLengthMinutes(User user) {
        if (user.isAdmin() || isStudio(user)) return -1;
        if (isCreator(user)) return 30;
        if (isCreatorLite(user)) return 10;
        return 5;
    }

    public String getMaxSpeedAllowedQuality(User user) {
        if (user.isAdmin() || isStudio(user)) return "4k";
        if (isCreator(user)) return "1440p";
        if (isCreatorLite(user)) return "1080p";
        return "720p";
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


    public long getDailyImageGenLimit(User user) {
        if (user.isAdmin() || isStudio(user)) return 30;
        if (isCreator(user)) return 15;
        if (isCreatorLite(user)) return 5;
        return 1;
    }

    public long getMonthlyImageGenLimit(User user) {
        if (user.isAdmin() || isStudio(user)) return 900;
        if (isCreator(user)) return 400;
        if (isCreatorLite(user)) return 50;
        return 5;
    }

    public int getImagesPerRequest(User user) {
        if (isStudio(user)) return 4;
        if (isCreator(user)) return 2;
        return 1;
    }

    public String getImageResolution(User user) {
        if (isStudio(user))  return "1024x1024";
        if (isCreator(user)) return "896x1152";
        return "1024x1024";
    }


    public int getImageSteps(User user) {
        return 22; // Same for all plans
    }

    public double getImageCfgScale(User user) {
        return 8.0; // Same for all plans
    }

    // ==================== BACKGROUND REMOVAL LIMITS ====================

    public int getMonthlyBackgroundRemovalLimit(User user) {
        if (user.isAdmin() || isStudio(user)) return 1500;
        if (isCreator(user)) return 500;
        if (isCreatorLite(user)) return 100;
        return 5;
    }


    public String getMaxBackgroundRemovalQuality(User user) {
        if (user.isAdmin() || isStudio(user)) return "4k";
        if (isCreator(user)) return "1080p";
        if (isCreatorLite(user)) return "1080p";
        return "720p";
    }

    public int getMaxBackgroundRemovalDimension(User user) {
        String quality = getMaxBackgroundRemovalQuality(user);
        return switch (quality.toLowerCase()) {
            case "720p"       -> 1280;
            case "1080p"      -> 1920;
            case "1440p", "2k" -> 2560;
            case "4k"         -> 3840;
            default           -> 1280;
        };
    }
    // ==================== ELEMENT DOWNLOAD LIMITS ====================

    public boolean canDownloadSvg(User user) {
        return isPremium(user) || isCreatorLite(user);
    }

    public int getMaxElementDownloadResolution(User user) {
        if (isPremium(user)) return Integer.MAX_VALUE;
        return 512;
    }


    public int getMonthlyElementDownloadLimit(User user) {
        if (isPremium(user)) return -1;
        return 10;
    }

    public int getDailyElementDownloadLimit(User user) {
        if (isPremium(user)) return -1;
        return 2;
    }

    private boolean hasActivePlan(User user, PlanType planType) {
        return userPlanRepository.findActiveUserPlan(user, planType, LocalDateTime.now()).isPresent();
    }

    private boolean isCreator(User user) {
        return hasActivePlan(user, PlanType.CREATOR);
    }

    private boolean isStudio(User user) {
        return hasActivePlan(user, PlanType.STUDIO);
    }

    private boolean isCreatorLite(User user) {
        return hasActivePlan(user, PlanType.CREATOR_LITE);
    }

    private boolean isPremium(User user) {
        return isCreatorLite(user) || isCreator(user) || isStudio(user) || user.isAdmin();
    }

    // ADD this method in the "PUBLIC UTILITY" section:
    public java.util.List<UserPlan> getActiveUserPlans(User user) {
        return userPlanRepository.findByUserAndActiveTrue(user)
                .stream()
                .filter(plan -> plan.getExpiryDate() == null
                        || plan.getExpiryDate().isAfter(LocalDateTime.now()))
                .toList();
    }

}