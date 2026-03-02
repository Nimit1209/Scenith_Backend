package com.example.Scenith.service;


import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserPlan;
import com.example.Scenith.enums.PlanType;
import com.example.Scenith.enums.VideoGenModel;
import com.example.Scenith.enums.VideoGenTier;
import com.example.Scenith.repository.UserPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Manages video generation plan limits.
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 * │  Plan              Price    Credits/mo  Daily cap  Max dur  Models accessible           │
 * ├─────────────────────────────────────────────────────────────────────────────────────────┤
 * │  VIDEO_GEN_STARTER ₹249     10 cr       2 cr/day   5s       Wan 2.5 only               │
 * │  VIDEO_GEN_PRO     ₹549     40 cr       8 cr/day   10s      Wan 2.5 + Kling 2.5 + 2.6  │
 * │  VIDEO_GEN_ELITE   ₹1,199   100 cr      20 cr/day  10s      All models incl. Veo 3      │
 * └─────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * Profit analysis — worst case (all credits burned on most expensive accessible model):
 *
 *   STARTER  (₹249):
 *     10 credits ÷ 1 cr/video = 10 × Wan 2.5 (5s) = 10 × ₹21 = ₹210 API cost
 *     Profit: ₹39 (16% margin) ✅
 *
 *   PRO  (₹549):
 *     Worst: 40 cr ÷ 4 cr/video = 10 × Kling 2.6 Pro (5s, audio on) = 10 × ₹59 = ₹590
 *     → Slight loss at this worst case. Fix: 5s audio-on Kling costs 4 credits.
 *       Realistic avg user uses mix → actual avg cost ~₹250-300, profit ~₹249-300 ✅
 *     Safe worst case: 40 cr ÷ 2 cr = 20 × Kling 2.6 Pro audio off = 20 × ₹29 = ₹580
 *     → Still marginal. RECOMMENDATION: Cap Kling 2.6 Pro (audio on) to PRO plan
 *       as 4 credits per 5s, so max spend = 10 videos × ₹59 = ₹590 on ₹549.
 *       To protect: Add a monthly Kling audio cap OR move Kling 2.6 Pro audio to ELITE only.
 *       Current code: Kling 2.6 Pro is accessible in PRO tier. Audio costs 4 credits.
 *       10 audio videos at 4 credits each = 40 credits used = ₹590 cost on ₹549 plan.
 *       → Price PRO at ₹599 to stay safe (₹9 buffer becomes ₹9 profit). Done.
 *
 *   ELITE  (₹1,199):
 *     Worst: 100 cr ÷ 10 cr = 10 × Veo 3 (5s) = 10 × ₹168 = ₹1,680 → LOSS
 *     Fix: Veo 3 costs 10 credits per 5s on 100 credit plan = max 10 Veo 3 videos.
 *     10 × ₹168 = ₹1,680. Price Elite at ₹1,499 still loses.
 *     → SOLUTION: Charge Veo 3 at 15 credits per 5s (not 10).
 *       Then max Veo 3 videos = 100 ÷ 15 = 6 videos = 6 × ₹168 = ₹1,008 API cost.
 *       Elite at ₹1,199 → profit ₹191 (16%) ✅
 *     See VideoGenModel.VEO_3 → creditsPerFiveSeconds = 15
 *
 * FINAL SAFE PRICING:
 *   VIDEO_GEN_STARTER : ₹249  | 10 credits  | 2/day  | 5s max
 *   VIDEO_GEN_PRO     : ₹599  | 40 credits  | 8/day  | 10s max
 *   VIDEO_GEN_ELITE   : ₹1,199| 100 credits | 20/day | 10s max
 */
@Service
@RequiredArgsConstructor
public class VideoGenPlanService {

    private final UserPlanRepository userPlanRepository;

    // ── Monthly credit allowances ─────────────────────────────────────────────

    public int getMonthlyCredits(User user) {
        if (hasActivePlan(user, PlanType.VIDEO_GEN_ELITE))   return 100;
        if (hasActivePlan(user, PlanType.VIDEO_GEN_PRO))     return 40;
        return 0;
    }

    // ── Daily credit caps ─────────────────────────────────────────────────────

    public int getDailyCredits(User user) {
        if (hasActivePlan(user, PlanType.VIDEO_GEN_ELITE))   return 20;
        if (hasActivePlan(user, PlanType.VIDEO_GEN_PRO))     return 8;
        return 0;
    }

    // ── Max video duration ────────────────────────────────────────────────────

    public int getMaxDurationSeconds(User user) {
        if (hasActivePlan(user, PlanType.VIDEO_GEN_ELITE))   return 10;
        if (hasActivePlan(user, PlanType.VIDEO_GEN_PRO))     return 10;
        return 0;
    }

    // ── Model access control ──────────────────────────────────────────────────

    /**
     * Returns the video gen tier for this user based on their active plan.
     * Returns null if the user has no active video generation plan.
     *
     * Tier → Model access:
     *   STARTER → Wan 2.5 only
     *   PRO     → Wan 2.5 + Kling 2.5 Turbo + Kling 2.6 Pro
     *   ELITE   → All models including Veo 3
     */
    public VideoGenTier getUserVideoGenTier(User user) {
        if (hasActivePlan(user, PlanType.VIDEO_GEN_ELITE))   return VideoGenTier.ELITE;
        if (hasActivePlan(user, PlanType.VIDEO_GEN_PRO))     return VideoGenTier.PRO;
        return null;
    }

    /**
     * Returns all models available to this user — used to populate the frontend dropdown.
     */
    public List<VideoGenModel> getAvailableModels(User user) {
        VideoGenTier userTier = getUserVideoGenTier(user);
        if (userTier == null) return List.of();

        return Arrays.stream(VideoGenModel.values())
                .filter(model -> model.getRequiredTier().isAccessibleBy(userTier))
                .toList();
    }

    /**
     * Checks whether the user's plan permits access to a specific model.
     */
    public boolean canUseModel(User user, VideoGenModel model) {
        VideoGenTier userTier = getUserVideoGenTier(user);
        if (userTier == null) return false;
        return model.getRequiredTier().isAccessibleBy(userTier);
    }

    /**
     * Returns true if the user has any active video generation plan.
     * Use this as a gate before showing the video generation UI.
     */
    public boolean hasAnyVideoGenPlan(User user) {
        return hasActivePlan(user, PlanType.VIDEO_GEN_PRO)
                || hasActivePlan(user, PlanType.VIDEO_GEN_ELITE);
    }

    /**
     * Returns a human-readable plan name for API responses and UI display.
     */
    public String getActivePlanName(User user) {
        if (hasActivePlan(user, PlanType.VIDEO_GEN_ELITE))   return "VIDEO_GEN_ELITE";
        if (hasActivePlan(user, PlanType.VIDEO_GEN_PRO))     return "VIDEO_GEN_PRO";
        return "NONE";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean hasActivePlan(User user, PlanType planType) {
        Optional<UserPlan> plan = userPlanRepository.findActiveUserPlan(
                user, planType, LocalDateTime.now());
        return plan.isPresent();
    }
}