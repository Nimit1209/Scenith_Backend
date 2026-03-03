package com.example.Scenith.enums;

public enum PlanType {
//    // ── Existing individual plans ─────────────────────────────────────────────
//    AI_VOICE_PRO,
//    AI_SUBTITLE_PRO,
//    AI_SPEED_PRO,
//    BG_REMOVAL_PRO,
//    SVG_PRO,
//
//    // ── AI Video Generation plans (3 tiers) ───────────────────────────────────
//
//    /**
//     * ₹249/month
//     * - 10 credits/month, 2 credits/day max
//     * - Access: Wan 2.5 only (STARTER tier)
//     * - Max duration: 5 seconds
//     * - Resolution: 480p
//     * - Worst case API cost: 10 × $0.25 = $2.50 = ₹210 → Profit: ₹39 ✅
//     */
//    VIDEO_GEN_STARTER,
//
//    /**
//     * ₹599/month
//     * - 40 credits/month, 8 credits/day max
//     * - Access: Wan 2.5 + Kling 2.5 Turbo Pro + Kling 2.6 Pro (PRO tier)
//     * - Max duration: 10 seconds
//     * - Resolution: 480p
//     * - Worst case API cost: 10 × Kling 2.6 Pro audio on (4cr) = $7.00 = ₹588 → Profit: ₹11 ✅
//     */
//    VIDEO_GEN_PRO,
//
//    /**
//     * ₹1,199/month
//     * - 100 credits/month, 20 credits/day max
//     * - Access: ALL models including Google Veo 3 (ELITE tier)
//     * - Max duration: 10 seconds
//     * - Resolution: 480p
//     * - Worst case API cost: 6 × Veo 3 5s (15cr each) = $12.00 = ₹1,008 → Profit: ₹191 ✅
//     */
//    VIDEO_GEN_ELITE


    /**
     * Creator Spark — Bundled plan
     * ₹499/month | $12/month
     */
    CREATOR,

    /**
     * Creator Odyssey — Bundled plan
     * ₹999/month | $24/month
     */
    STUDIO,

    /**
     * Video Gen Pro — Standalone AI Video Generation
     * ₹599/month | $15/month
     * - 40 credits/month, 8 credits/day
     * - Wan 2.5 + Kling 2.5/2.6 Pro
     * - Max 10 seconds per video
     */
    VIDEO_GEN_PRO,

    /**
     * Video Gen Elite — Standalone AI Video Generation
     * ₹1,199/month | $25/month
     * - 100 credits/month, 20 credits/day
     * - All models including Google Veo 3
     * - Max 10 seconds per video
     */
    VIDEO_GEN_ELITE,
    /**
     * Creator Lite — Entry-level bundled plan
     * ₹99/month
     */
    CREATOR_LITE,
}