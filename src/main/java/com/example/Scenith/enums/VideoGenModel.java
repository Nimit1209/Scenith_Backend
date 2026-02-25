package com.example.Scenith.enums;

/**
 * All confirmed AI Video Generation models available on fal.ai.
 *
 * VERIFIED PRICING (from fal.ai, February 2026):
 *   Wan 2.5              → $0.05/sec
 *   Kling 2.5 Turbo Pro  → $0.07/sec
 *   Kling 2.6 Pro        → $0.07/sec (audio off) | $0.14/sec (audio on)
 *   Veo 3                → $0.40/sec
 *
 * CREDIT SYSTEM:
 *   1 credit = roughly ₹21 API cost buffer (designed for profitability)
 *
 *   Wan 2.5 (5s)                    = 1 credit   → API cost ₹21  ✅
 *   Kling 2.5 Turbo Pro (5s)        = 2 credits  → API cost ₹29  ✅
 *   Kling 2.6 Pro (5s, audio off)   = 2 credits  → API cost ₹29  ✅
 *   Kling 2.6 Pro (5s, audio on)    = 4 credits  → API cost ₹59  ✅
 *   Veo 3 (5s)                      = 10 credits → API cost ₹168 ✅
 *
 *   10-second videos cost 2× base credits automatically.
 *
 * API KEY: Single fal.ai key covers ALL models.
 *   Get it at: https://fal.ai/dashboard → Account → API Keys
 *   Store in: credentials/fal-api-key.txt
 */
public enum VideoGenModel {

    // ── TIER 1: Express ───────────────────────────────────────────────────────

    WAN_2_5(
            "Wan 2.5",
            "fal-ai/wan-25-preview/text-to-video",
            "fal-ai/wan-25-preview/image-to-video",
            VideoGenTier.STARTER,
            1,          // credits per 5s (audio not supported)
            0.05,       // $/sec — confirmed on fal.ai pricing page
            "720p",
            false,
            "Fast & lightweight. Best for quick drafts, social content, and testing ideas."
    ),

    // ── TIER 2: Pro ───────────────────────────────────────────────────────────

    KLING_2_5_TURBO(
            "Kling 2.5 Turbo Pro",
            "fal-ai/kling-video/v2.5-turbo/pro/text-to-video",
            "fal-ai/kling-video/v2.5-turbo/pro/image-to-video",
            VideoGenTier.PRO,
            2,          // credits per 5s
            0.07,       // $/sec — confirmed on fal.ai pricing page
            "1080p",
            false,      // no native audio on turbo
            "Cinematic motion with excellent physics and camera control. Best for product demos and action shots."
    ),

    KLING_2_6_PRO(
            "Kling 2.6 Pro",
            "fal-ai/kling-video/v2.6/pro/text-to-video",
            "fal-ai/kling-video/v2.6/pro/image-to-video",
            VideoGenTier.PRO,
            2,          // credits per 5s, audio off ($0.07/s)
            // audio on is handled via calculateCredits() → 4 credits ($0.14/s)
            0.07,       // $/sec audio off — $0.14/sec audio on (confirmed on fal.ai)
            "1080p",
            true,       // native audio supported
            "Broadcast-quality cinematic video with native audio. Best for storytelling and social media."
    ),

    // ── TIER 3: Premium ───────────────────────────────────────────────────────

    VEO_3(
            "Google Veo 3",
            "fal-ai/veo3/text-to-video",
            "fal-ai/veo3/image-to-video",
            VideoGenTier.ELITE,
            10,         // credits per 5s — API cost $2.00 = ₹168, charged 10 credits (₹210+)
            0.40,       // $/sec — confirmed on fal.ai pricing page
            "1080p",
            true,       // native audio built-in
            "Google's flagship model. Photorealistic quality with native audio in one pass. Premium tier only."
    );

    // ─────────────────────────────────────────────────────────────────────────

    private final String displayName;
    private final String textToVideoEndpoint;
    private final String imageToVideoEndpoint;
    private final VideoGenTier tier;
    private final int creditsPerFiveSeconds;
    private final double costPerSecondUsd;
    private final String resolution;
    private final boolean supportsAudio;
    private final String description;

    VideoGenModel(String displayName,
                  String textToVideoEndpoint,
                  String imageToVideoEndpoint,
                  VideoGenTier tier,
                  int creditsPerFiveSeconds,
                  double costPerSecondUsd,
                  String resolution,
                  boolean supportsAudio,
                  String description) {
        this.displayName = displayName;
        this.textToVideoEndpoint = textToVideoEndpoint;
        this.imageToVideoEndpoint = imageToVideoEndpoint;
        this.tier = tier;
        this.creditsPerFiveSeconds = creditsPerFiveSeconds;
        this.costPerSecondUsd = costPerSecondUsd;
        this.resolution = resolution;
        this.supportsAudio = supportsAudio;
        this.description = description;
    }

    /**
     * Calculates total credits for a generation.
     *
     * Rules:
     *  - 10s video = 2× base credits
     *  - Audio ON  = 2× the credit total (only for Kling 2.6 Pro and Veo 3)
     *
     * Examples:
     *  - Wan 2.5,  5s, no audio  = 1 credit
     *  - Wan 2.5, 10s, no audio  = 2 credits
     *  - Kling 2.6 Pro, 5s,  audio off = 2 credits  (~$0.35 cost)
     *  - Kling 2.6 Pro, 5s,  audio on  = 4 credits  (~$0.70 cost)
     *  - Kling 2.6 Pro, 10s, audio on  = 8 credits  (~$1.40 cost)
     *  - Veo 3, 5s  = 10 credits (~$2.00 cost)
     *  - Veo 3, 10s = 20 credits (~$4.00 cost)
     */
    public int calculateCredits(int durationSeconds, boolean audioOn) {
        int base = (durationSeconds <= 5) ? creditsPerFiveSeconds : creditsPerFiveSeconds * 2;
        if (audioOn && supportsAudio) {
            base = base * 2;
        }
        return base;
    }

    public VideoGenTier getRequiredTier() { return tier; }
    public String getDisplayName() { return displayName; }
    public String getTextToVideoEndpoint() { return textToVideoEndpoint; }
    public String getImageToVideoEndpoint() { return imageToVideoEndpoint; }
    public VideoGenTier getTier() { return tier; }
    public int getCreditsPerFiveSeconds() { return creditsPerFiveSeconds; }
    public double getCostPerSecondUsd() { return costPerSecondUsd; }
    public String getResolution() { return resolution; }
    public boolean isSupportsAudio() { return supportsAudio; }
    public String getDescription() { return description; }
}