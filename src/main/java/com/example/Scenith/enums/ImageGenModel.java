// NEW FILE: src/main/java/com/example/Scenith/enums/ImageGenModel.java

package com.example.Scenith.enums;

public enum ImageGenModel {

    // ── Creator Spark models ──────────────────────────────────────────────────
    STABILITY_AI_CORE(
            "Stability AI Core",
            "stability-ai-core",
            2,
            PlanType.CREATOR_LITE,
            "https://api.stability.ai/v2beta/stable-image/generate/core"
    ),
    GPT_IMAGE_1_MINI(
            "GPT Image 1 Mini",
            "gpt-image-1",
            3,
            PlanType.CREATOR_LITE,
            "https://api.openai.com/v1/images/generations"
    ),
    IMAGEN_4_FAST(
            "Imagen 4 Fast",
            "imagen-4.0-fast-generate-001",   // ← was: imagen-4.0-flash-001
            5,
            PlanType.CREATOR_LITE,
            "https://generativelanguage.googleapis.com/v1beta/models/imagen-4.0-fast-generate-001:predict"
    ),

    FLUX_1_1_PRO(
            "FLUX 1.1 Pro",
            "flux-pro-1.1",
            7,
            PlanType.CREATOR_LITE,
            "https://api.bfl.ai/v1/flux-pro-1.1"
    ),

    // ── Creator Odyssey models ────────────────────────────────────────────────
    GPT_IMAGE_1_MEDIUM(
            "GPT Image 1 (Medium)",
            "gpt-image-1",
            10,
            PlanType.CREATOR,
            "https://api.openai.com/v1/images/generations"
    ),
    IMAGEN_4_STANDARD(
            "Imagen 4 Standard",
            "imagen-4.0-generate-001",        // ← was: imagen-4.0-001
            8,
            PlanType.CREATOR,
            "https://generativelanguage.googleapis.com/v1beta/models/imagen-4.0-generate-001:predict"
    ),
    GROK_AURORA(
            "Grok Aurora",
            "aurora",
            12,
            PlanType.CREATOR,
            "https://api.x.ai/v1/images/generations"
    );

    private final String displayName;
    private final String apiModelId;
    private final int creditsPerImage;
    private final PlanType minimumPlan;   // CREATOR_LITE = Spark, CREATOR = Odyssey
    private final String apiEndpoint;

    ImageGenModel(String displayName, String apiModelId, int creditsPerImage,
                  PlanType minimumPlan, String apiEndpoint) {
        this.displayName    = displayName;
        this.apiModelId     = apiModelId;
        this.creditsPerImage = creditsPerImage;
        this.minimumPlan    = minimumPlan;
        this.apiEndpoint    = apiEndpoint;
    }

    public String getDisplayName()    { return displayName; }
    public String getApiModelId()     { return apiModelId; }
    public int getCreditsPerImage()   { return creditsPerImage; }
    public PlanType getMinimumPlan()  { return minimumPlan; }
    public String getApiEndpoint()    { return apiEndpoint; }
}