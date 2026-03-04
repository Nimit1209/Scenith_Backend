// REPLACE entire class in SoleImageGenService.java

package com.example.Scenith.service.imageService;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserImageGenCredits;
import com.example.Scenith.entity.imageentity.SoleImageGen;
import com.example.Scenith.enums.ImageGenModel;
import com.example.Scenith.repository.UserImageGenCreditsRepository;
import com.example.Scenith.repository.imagerepository.SoleImageGenRepository;
import com.example.Scenith.service.PlanLimitsService;
import com.example.Scenith.service.CloudflareR2Service;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class SoleImageGenService {

    private static final Logger logger = LoggerFactory.getLogger(SoleImageGenService.class);

    private final SoleImageGenRepository soleImageGenRepository;
    private final UserImageGenCreditsRepository creditsRepository;
    private final PlanLimitsService planLimitsService;
    private final CloudflareR2Service cloudflareR2Service;
    private final OkHttpClient httpClient;

    @Value("${app.image-gen-api-key-path:/app/credentials/image-gen-api-key.txt}")
    private String stabilityKeyPath;

    @Value("${app.openai-api-key-path:/app/credentials/openai-api-key.txt}")
    private String openAiKeyPath;

    @Value("${app.google-ai-api-key-path:/app/credentials/google-ai-api-key.txt}")
    private String googleAiKeyPath;

    @Value("${app.bfl-api-key-path:/app/credentials/bfl-api-key.txt}")
    private String bflKeyPath;

    @Value("${app.xai-api-key-path:/app/credentials/xai-api-key.txt}")
    private String xaiKeyPath;

    // Lazy-loaded API keys
    private String openAiKey;
    private String googleAiKey;
    private String bflKey;
    private String xaiKey;
    private String stabilityKey;

    public SoleImageGenService(
            SoleImageGenRepository soleImageGenRepository,
            UserImageGenCreditsRepository creditsRepository,
            PlanLimitsService planLimitsService,
            CloudflareR2Service cloudflareR2Service) {
        this.soleImageGenRepository = soleImageGenRepository;
        this.creditsRepository      = creditsRepository;
        this.planLimitsService      = planLimitsService;
        this.cloudflareR2Service    = cloudflareR2Service;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .writeTimeout(java.time.Duration.ofSeconds(30))
                // No callTimeout — FLUX polling loop manages its own timing
                .build();
    }

    // ── API key loading ───────────────────────────────────────────────────────

    private String loadKeyFromPath(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) throw new IOException("API key file not found: " + p);
        return Files.readString(p).trim();
    }

    private String getOpenAiKey()    throws IOException { if (openAiKey    == null) openAiKey    = loadKeyFromPath(openAiKeyPath);    return openAiKey; }
    private String getGoogleAiKey()  throws IOException { if (googleAiKey  == null) googleAiKey  = loadKeyFromPath(googleAiKeyPath);  return googleAiKey; }
    private String getBflKey()       throws IOException { if (bflKey       == null) bflKey       = loadKeyFromPath(bflKeyPath);       return bflKey; }
    private String getXaiKey()       throws IOException { if (xaiKey       == null) xaiKey       = loadKeyFromPath(xaiKeyPath);       return xaiKey; }
    private String getStabilityKey() throws IOException { if (stabilityKey == null) stabilityKey = loadKeyFromPath(stabilityKeyPath); return stabilityKey; }
    // ── Public API ────────────────────────────────────────────────────────────

    public SoleImageGen generateImage(
            User user,
            String prompt,
            String negativePrompt,
            ImageGenModel model) throws IOException {

        if (prompt == null || prompt.isBlank())
            throw new IllegalArgumentException("Prompt cannot be empty");

        // 1. Access control
        if (!planLimitsService.canAccessImageModel(user, model))
            throw new IllegalStateException("Your plan does not include access to " + model.getDisplayName());

        int credCost = model.getCreditsPerImage();

        // 2. Daily cap
        UserImageGenCredits credits = getOrCreateCredits(user);
        rolloverDailyIfNeeded(credits);

        int dailyLimit   = planLimitsService.getDailyImageGenCredits(user);
        int monthlyLimit = planLimitsService.getMonthlyImageGenCredits(user);

        if (dailyLimit > 0 && credits.getTodayCreditsUsed() + credCost > dailyLimit)
            throw new IllegalStateException(
                    "Daily image credit limit reached (limit: " + dailyLimit +
                            ", used: " + credits.getTodayCreditsUsed() + ", cost: " + credCost + ")");

        if (monthlyLimit > 0 && credits.getCreditsUsed() + credCost > monthlyLimit)
            throw new IllegalStateException(
                    "Monthly image credit limit reached (limit: " + monthlyLimit +
                            ", used: " + credits.getCreditsUsed() + ", cost: " + credCost + ")");

        // 3. Call the correct API
        byte[] imageBytes = callImageApi(model, prompt, negativePrompt);

        // 4. Upload to R2
        String tempDir = System.getProperty("java.io.tmpdir") + File.separator + "imagegen";
        Path tempDirPath = Path.of(tempDir);
        Files.createDirectories(tempDirPath);

        String tempFileName = "img_" + System.currentTimeMillis() + ".png";
        Path tempFilePath = tempDirPath.resolve(tempFileName);
        File tempFile = tempFilePath.toFile();
        SoleImageGen saved;

        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile)) { fos.write(imageBytes); }

            String r2Path = "images/sole_image_gen/" + user.getId() + "/" + UUID.randomUUID() + ".png";
            cloudflareR2Service.uploadFile(r2Path, tempFile);
            logger.info("Uploaded generated image to R2: {}", r2Path);

            // 5. Persist entity
            SoleImageGen entity = new SoleImageGen();
            entity.setUser(user);
            entity.setPrompt(prompt);
            entity.setNegativePrompt(negativePrompt);
            entity.setImagePath(r2Path);
            entity.setResolution("1024x1024");
            entity.setSteps(0);
            entity.setCfgScale(0.0);
            entity.setCreatedAt(LocalDateTime.now());
            saved = soleImageGenRepository.save(entity);

        } finally {
            Files.deleteIfExists(tempFilePath);
        }

        // 6. Deduct credits
        credits.setCreditsUsed(credits.getCreditsUsed() + credCost);
        credits.setTodayCreditsUsed(credits.getTodayCreditsUsed() + credCost);
        creditsRepository.save(credits);

        return saved;
    }

    // ── Per-model API dispatchers ─────────────────────────────────────────────

    private byte[] callImageApi(ImageGenModel model, String prompt, String negativePrompt) throws IOException {
        return switch (model) {
            case STABILITY_AI_CORE -> callStabilityCore(prompt, negativePrompt);
            case GPT_IMAGE_1_MINI  -> callOpenAI(prompt, "low");
            case GPT_IMAGE_1_MEDIUM -> callOpenAI(prompt, "medium");
            case IMAGEN_4_FAST     -> callImagen(model, prompt);
            case IMAGEN_4_STANDARD -> callImagen(model, prompt);
            case FLUX_1_1_PRO      -> callFlux(prompt);
            case GROK_AURORA       -> callGrok(prompt);
        };
    }

    private byte[] callStabilityCore(String prompt, String negativePrompt) throws IOException {
        JSONObject body = new JSONObject();
        JSONArray textPrompts = new JSONArray();
        textPrompts.put(new JSONObject().put("text", prompt).put("weight", 1));
        if (negativePrompt != null && !negativePrompt.isBlank())
            textPrompts.put(new JSONObject().put("text", negativePrompt).put("weight", -1));

        body.put("text_prompts", textPrompts);
        body.put("cfg_scale", 7);
        body.put("height", 1024);
        body.put("width", 1024);
        body.put("steps", 30);
        body.put("samples", 1);

        Request req = new Request.Builder()
                .url("https://api.stability.ai/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image")
                .addHeader("Authorization", "Bearer " + getStabilityKey())
                .addHeader("Accept", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            assertSuccess(resp, "Stability AI");
            JSONObject json = new JSONObject(resp.body().string());
            return Base64.getDecoder().decode(json.getJSONArray("artifacts").getJSONObject(0).getString("base64"));
        }
    }

    private byte[] callOpenAI(String prompt, String quality) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", "gpt-image-1");
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", "1024x1024");
        body.put("quality", quality);
        // gpt-image-1 does not accept response_format — it always returns b64_json

        Request req = new Request.Builder()
                .url("https://api.openai.com/v1/images/generations")
                .addHeader("Authorization", "Bearer " + getOpenAiKey())
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            assertSuccess(resp, "OpenAI");
            String bodyStr = resp.body().string();
            JSONObject json = new JSONObject(bodyStr);
            JSONObject imageObj = json.getJSONArray("data").getJSONObject(0);
            // gpt-image-1 returns b64_json directly
            if (imageObj.has("b64_json")) {
                return Base64.getDecoder().decode(imageObj.getString("b64_json"));
            }
            // fallback: if url is returned instead, download it
            return downloadUrl(imageObj.getString("url"));
        }
    }

    private byte[] callImagen(ImageGenModel model, String prompt) throws IOException {
        String modelId = model.getApiModelId();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + modelId + ":predict";   // ← removed ?key= from URL

        JSONObject instance  = new JSONObject().put("prompt", prompt);
        JSONObject parameters = new JSONObject()
                .put("sampleCount", 1)
                .put("aspectRatio", "1:1")
                .put("safetySetting", "block_low_and_above");

        JSONObject body = new JSONObject();
        body.put("instances", new JSONArray().put(instance));
        body.put("parameters", parameters);

        Request req = new Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", getGoogleAiKey())   // ← correct header
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            assertSuccess(resp, "Google Imagen");
            String respBody = resp.body().string();
            logger.debug("Google Imagen raw response: {}", respBody);
            JSONObject json = new JSONObject(respBody);

            // Imagen 4 returns predictions[].bytesBase64Encoded
            JSONArray predictions = json.getJSONArray("predictions");
            if (predictions.length() == 0)
                throw new IOException("Google Imagen returned empty predictions array");

            JSONObject prediction = predictions.getJSONObject(0);

            // Field name varies slightly across model versions — handle both
            String b64;
            if (prediction.has("bytesBase64Encoded")) {
                b64 = prediction.getString("bytesBase64Encoded");
            } else if (prediction.has("imageBytes")) {
                b64 = prediction.getJSONObject("imageBytes").getString("bytesBase64Encoded");
            } else {
                throw new IOException("Google Imagen response missing image bytes. Full response: " + respBody);
            }
            return Base64.getDecoder().decode(b64);
        }
    }

    private byte[] callFlux(String prompt) throws IOException {
        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        body.put("width", 1024);
        body.put("height", 1024);
        body.put("prompt_upsampling", false);
        body.put("safety_tolerance", 2);
        body.put("output_format", "png");

        Request submitReq = new Request.Builder()
                .url("https://api.bfl.ai/v1/flux-pro-1.1")  // ← was: api.us1.bfl.ai
                .addHeader("x-key", getBflKey())
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        String pollingUrl;  // ← use polling_url directly from response
        try (Response resp = httpClient.newCall(submitReq).execute()) {
            assertSuccess(resp, "BFL FLUX (submit)");
            JSONObject submitJson = new JSONObject(resp.body().string());
            pollingUrl = submitJson.getString("polling_url");  // ← was: getString("id") then constructing URL manually
        }

        logger.info("BFL FLUX job submitted, polling: {}", pollingUrl);
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        for (int i = 0; i < 22; i++) {
            Request pollReq = new Request.Builder()
                    .url(pollingUrl)          // ← use polling_url directly
                    .addHeader("x-key", getBflKey())
                    .get()
                    .build();

            try (Response pollResp = httpClient.newCall(pollReq).execute()) {
                if (!pollResp.isSuccessful()) {
                    try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    continue;
                }
                String pollBodyStr = pollResp.body().string();
                JSONObject pollJson = new JSONObject(pollBodyStr);
                String status = pollJson.optString("status", "Pending");
                logger.debug("BFL FLUX poll {}: status={}", i + 1, status);

                if ("Ready".equals(status)) {
                    String imageUrl = pollJson.getJSONObject("result").getString("sample");
                    return downloadUrl(imageUrl);
                }
                if ("Error".equals(status) || "Failed".equals(status) || "Request Moderated".equals(status)) {
                    throw new IOException("FLUX generation failed with status: " + status + " — " + pollBodyStr);
                }
            }
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new IOException("FLUX generation timed out after ~90 seconds.");
    }

    private byte[] callGrok(String prompt) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", "grok-2-image-1212");
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("response_format", "b64_json");

        Request req = new Request.Builder()
                .url("https://api.x.ai/v1/images/generations")
                .addHeader("Authorization", "Bearer " + getXaiKey())
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            assertSuccess(resp, "xAI Grok Aurora");
            JSONObject json = new JSONObject(resp.body().string());
            return Base64.getDecoder().decode(json.getJSONArray("data").getJSONObject(0).getString("b64_json"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] downloadUrl(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = httpClient.newCall(req).execute()) {
            assertSuccess(resp, "image download");
            return resp.body().bytes();
        }
    }

    private void assertSuccess(Response resp, String provider) throws IOException {
        if (!resp.isSuccessful()) {
            String err = resp.body() != null ? resp.body().string() : "no body";
            throw new IOException(provider + " API error " + resp.code() + ": " + err);
        }
    }

    private UserImageGenCredits getOrCreateCredits(User user) {
        String month = YearMonth.now().toString();
        return creditsRepository.findByUserAndCreditMonth(user, month)
                .orElseGet(() -> creditsRepository.save(new UserImageGenCredits(user, YearMonth.now())));
    }

    private void rolloverDailyIfNeeded(UserImageGenCredits credits) {
        String today = LocalDate.now().toString();
        if (!today.equals(credits.getLastResetDate())) {
            credits.setTodayCreditsUsed(0);
            credits.setLastResetDate(today);
        }
    }

    // ── Usage queries (used by controller) ───────────────────────────────────

    public int getMonthlyCreditsUsed(User user) {
        return creditsRepository.findByUserAndCreditMonth(user, YearMonth.now().toString())
                .map(UserImageGenCredits::getCreditsUsed).orElse(0);
    }

    public int getDailyCreditsUsed(User user) {
        return creditsRepository.findByUserAndCreditMonth(user, YearMonth.now().toString())
                .map(c -> {
                    rolloverDailyIfNeeded(c);
                    return c.getTodayCreditsUsed();
                }).orElse(0);
    }

    public List<SoleImageGen> getUserGenerations(User user) {
        return soleImageGenRepository.findByUserOrderByCreatedAtDesc(user);
    }
}