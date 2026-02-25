package com.example.Scenith.enums;

/**
 * Tiers for AI Video Generation access.
 * Maps directly to the 3 individual video plans you will sell.
 *
 * STARTER  → VideoGen Starter Plan  (₹199/mo)
 * PRO      → VideoGen Pro Plan      (₹499/mo)
 * ELITE    → VideoGen Elite Plan    (₹999/mo)
 * PREMIUM  → Veo 3 only — accessible in ELITE plan (costs more credits)
 */
public enum VideoGenTier {
    STARTER(1),
    PRO(2),
    ELITE(3),
    PREMIUM(3);  // PREMIUM models are accessible in ELITE tier but cost heavy credits

    private final int level;

    VideoGenTier(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isAccessibleBy(VideoGenTier userTier) {
        return userTier.getLevel() >= this.level;
    }
}