package com.example.Scenith.service;

import com.example.Scenith.entity.AiVideoGen;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserVideoGenCredits;
import com.example.Scenith.enums.VideoGenModel;
import com.example.Scenith.repository.AiVideoGenRepository;
import com.example.Scenith.repository.UserVideoGenCreditsRepository;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AiVideoGenService {

    private static final Logger logger = LoggerFactory.getLogger(AiVideoGenService.class);

    // fal.ai queue base URL — all video models use this async pattern
    private static final String FAL_QUEUE_URL = "https://queue.fal.run/";

    // R2 folder structure: mirrors what was local before
    private static final String R2_VIDEO_GEN_PREFIX = "ai_video_gen/";

    private final AiVideoGenRepository videoGenRepository;
    private final UserVideoGenCreditsRepository creditsRepository;
    private final VideoGenPlanService planService;
    private final CloudflareR2Service cloudflareR2Service;
    private final OkHttpClient httpClient;

    @Value("${app.fal-api-key-path:/app/credentials/fal-api-key.txt}")
    private String falApiKeyPath;

    @Value("${app.temp.dir:/mnt/scenith-temp/video-exports}")
    private String tempDir;

    private String apiKey;

    public AiVideoGenService(
            AiVideoGenRepository videoGenRepository,
            UserVideoGenCreditsRepository creditsRepository,
            VideoGenPlanService planService,
            CloudflareR2Service cloudflareR2Service) {
        this.videoGenRepository = videoGenRepository;
        this.creditsRepository = creditsRepository;
        this.planService = planService;
        this.cloudflareR2Service = cloudflareR2Service;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @PostConstruct
    public void init() throws IOException {
        // Load fal.ai API key from configurable path (set via env var FAL_API_KEY_PATH or app property)
        File keyFile = new File(falApiKeyPath);
        if (!keyFile.exists()) {
            throw new IllegalStateException(
                    "fal.ai API key file not found at: " + falApiKeyPath
                            + ". Set app.fal-api-key-path in your properties or FAL_API_KEY_PATH env var.");
        }
        this.apiKey = Files.readString(keyFile.toPath()).trim();
        logger.info("AiVideoGenService initialized. API key loaded from: {}", falApiKeyPath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * STEP 1: Submit a text-to-video generation job to fal.ai.
     * Returns immediately with a falRequestId — generation is async.
     *
     * @throws IllegalStateException if user has no plan or insufficient credits
     * @throws IllegalArgumentException if model is not accessible or params invalid
     */
    public AiVideoGen submitTextToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String negativePrompt,
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio) throws IOException {

        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled);
        checkAndReserveCredits(user, creditsNeeded);

        // Build fal.ai request payload
        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            payload.put("negative_prompt", negativePrompt);
        }
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        payload.put("resolution", "480p");

        // Kling-specific: audio flag
        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getTextToVideoEndpoint(), payload);

        // Persist record (no local path yet — will be set when COMPLETED)
        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.TEXT_TO_VIDEO,
                prompt, negativePrompt, null, durationSeconds, audioEnabled,
                aspectRatio, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 1 (variant): Submit an image-to-video job.
     * referenceImageUrl should be a public URL or base64 data URL of the uploaded image.
     */
    public AiVideoGen submitImageToVideo(
            User user,
            VideoGenModel model,
            String prompt,
            String referenceImageUrl,
            String referenceImageR2Path,   // R2 path of the reference image (replaces local path)
            int durationSeconds,
            boolean audioEnabled,
            String aspectRatio) throws IOException {

        validateRequest(user, model, durationSeconds, prompt);
        checkFalBalance();

        int creditsNeeded = model.calculateCredits(durationSeconds, audioEnabled);
        checkAndReserveCredits(user, creditsNeeded);

        JSONObject payload = new JSONObject();
        payload.put("prompt", prompt);
        payload.put("image_url", referenceImageUrl);
        payload.put("duration", String.valueOf(durationSeconds));
        payload.put("aspect_ratio", aspectRatio != null ? aspectRatio : "16:9");
        if (model.isSupportsAudio()) {
            payload.put("enable_audio", audioEnabled);
        }

        String falRequestId = submitToFalQueue(model.getImageToVideoEndpoint(), payload);

        AiVideoGen record = buildRecord(user, model, AiVideoGen.GenerationType.IMAGE_TO_VIDEO,
                prompt, null, referenceImageR2Path, durationSeconds, audioEnabled,
                aspectRatio, creditsNeeded, falRequestId);
        return videoGenRepository.save(record);
    }

    /**
     * STEP 2: Poll fal.ai for job status.
     * Call this from your /status/{falRequestId} endpoint.
     * When status is COMPLETED, automatically downloads the video and uploads to R2.
     *
     * Returns the updated AiVideoGen record.
     */
    public AiVideoGen checkAndUpdateStatus(String falRequestId) throws IOException {
        AiVideoGen record = videoGenRepository.findByFalRequestId(falRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Video job not found: " + falRequestId));

        // Skip if already terminal
        if (record.getStatus() == AiVideoGen.Status.COMPLETED
                || record.getStatus() == AiVideoGen.Status.FAILED) {
            return record;
        }

        String appId = record.getGenerationType() == AiVideoGen.GenerationType.IMAGE_TO_VIDEO
                ? extractAppId(record.getModel().getImageToVideoEndpoint())
                : extractAppId(record.getModel().getTextToVideoEndpoint());

        String statusEndpoint = FAL_QUEUE_URL + appId + "/requests/" + falRequestId + "/status";
        logger.info("Checking fal.ai status at: {}", statusEndpoint);

        Request statusRequest = new Request.Builder()
                .url(statusEndpoint)
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(statusRequest).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("fal.ai status check failed for {}: {}", falRequestId, response.code());
                return record;
            }

            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            String status = json.optString("status", "IN_QUEUE");

            switch (status) {
                case "COMPLETED" -> {
                    String resultUrl = getResultUrl(record, falRequestId);
                    if (resultUrl != null) {
                        // Download from fal.ai → upload to R2 → store CDN URL
                        String r2Path = downloadAndUploadToR2(record, resultUrl);
                        Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);

                        record.setVideoPath(r2Path);                    // R2 path (replaces local path)
                        record.setVideoUrl(urls.get("cdnUrl"));         // Public CDN URL for frontend
                        record.setStatus(AiVideoGen.Status.COMPLETED);
                        record.setCompletedAt(LocalDateTime.now());
                        logger.info("Video generation completed and uploaded to R2: {} → {}", falRequestId, r2Path);
                    }
                }
                case "FAILED" -> {
                    String errorMsg = json.optString("error", "Unknown error from fal.ai");
                    record.setStatus(AiVideoGen.Status.FAILED);
                    record.setErrorMessage(errorMsg);
                    // Refund credits on failure
                    refundCredits(record.getUser(), record.getCreditsUsed());
                    logger.error("Video generation failed for {}: {}", falRequestId, errorMsg);
                }
                case "IN_PROGRESS" -> record.setStatus(AiVideoGen.Status.PROCESSING);
                // IN_QUEUE → stays PENDING
            }

            return videoGenRepository.save(record);
        }
    }

    /**
     * Upload a reference image to R2 (for image-to-video).
     * Returns the R2 path AND a public CDN URL suitable for sending to fal.ai.
     */
    public Map<String, String> uploadReferenceImage(MultipartFile imageFile, Long userId) throws IOException {
        String fileName = "ref_" + System.currentTimeMillis() + "_" + sanitizeFileName(imageFile.getOriginalFilename());
        // R2 path mirrors old local folder structure: ai_video_gen/{userId}/{fileName}
        String r2Path = R2_VIDEO_GEN_PREFIX + userId + "/" + fileName;

        cloudflareR2Service.uploadFile(imageFile, r2Path);

        Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 86400);
        urls.put("r2Path", r2Path);
        logger.info("Reference image uploaded to R2: {}", r2Path);
        return urls;
    }

    /**
     * Generate a fresh CDN/presigned URL for an already-completed video.
     * Useful when the stored videoUrl has expired.
     */
    public Map<String, String> refreshVideoUrls(String falRequestId, long expirationSeconds) throws IOException {
        AiVideoGen record = videoGenRepository.findByFalRequestId(falRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Video job not found: " + falRequestId));

        if (record.getStatus() != AiVideoGen.Status.COMPLETED || record.getVideoPath() == null) {
            throw new IllegalStateException("Video is not yet available.");
        }

        return cloudflareR2Service.generateUrls(record.getVideoPath(), expirationSeconds);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREDIT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    public int getRemainingMonthlyCredits(User user) {
        int limit = planService.getMonthlyCredits(user);
        int used = getMonthlyCreditsUsed(user);
        return limit - used;
    }

    public int getRemainingDailyCredits(User user) {
        int dailyLimit = planService.getDailyCredits(user);
        UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
        resetDailyIfNeeded(credits);
        return dailyLimit - credits.getTodayCreditsUsed();
    }

    public int getMonthlyCreditsUsed(User user) {
        return getOrCreateCreditsRecord(user).getCreditsUsed();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HISTORY
    // ─────────────────────────────────────────────────────────────────────────

    public List<AiVideoGen> getUserHistory(User user) {
        return videoGenRepository.findByUserOrderByCreatedAtDesc(user);
    }

    public List<AiVideoGen> getUserPendingJobs(User user) {
        return videoGenRepository.findByUserAndStatusOrderByCreatedAtDesc(
                user, AiVideoGen.Status.PENDING);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void validateRequest(User user, VideoGenModel model, int durationSeconds, String prompt) {
        if (!planService.hasAnyVideoGenPlan(user)) {
            throw new IllegalStateException("No active AI Video Generation plan. Please purchase a plan to continue.");
        }
        if (!planService.canUseModel(user, model)) {
            throw new IllegalArgumentException(
                    "Model '" + model.getDisplayName() + "' requires a higher plan. "
                            + "Please upgrade to access this model.");
        }
        int maxDuration = planService.getMaxDurationSeconds(user);
        if (durationSeconds > maxDuration) {
            throw new IllegalArgumentException(
                    "Your plan supports a maximum of " + maxDuration + " seconds. "
                            + "Upgrade to Video Pro or Elite for 10-second videos.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
    }

    private void checkAndReserveCredits(User user, int creditsNeeded) {
        int monthlyLimit = planService.getMonthlyCredits(user);
        int dailyLimit = planService.getDailyCredits(user);

        UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
        resetDailyIfNeeded(credits);

        if (credits.getCreditsUsed() + creditsNeeded > monthlyLimit) {
            throw new IllegalStateException(
                    "Insufficient monthly credits. You need " + creditsNeeded
                            + " credits but only have " + (monthlyLimit - credits.getCreditsUsed()) + " remaining.");
        }
        if (credits.getTodayCreditsUsed() + creditsNeeded > dailyLimit) {
            throw new IllegalStateException(
                    "Daily credit limit reached. You need " + creditsNeeded
                            + " credits but only have " + (dailyLimit - credits.getTodayCreditsUsed()) + " remaining today.");
        }

        // Deduct credits immediately (before generation starts)
        credits.setCreditsUsed(credits.getCreditsUsed() + creditsNeeded);
        credits.setTodayCreditsUsed(credits.getTodayCreditsUsed() + creditsNeeded);
        creditsRepository.save(credits);
    }

    private void refundCredits(User user, int creditsToRefund) {
        try {
            UserVideoGenCredits credits = getOrCreateCreditsRecord(user);
            credits.setCreditsUsed(Math.max(0, credits.getCreditsUsed() - creditsToRefund));
            credits.setTodayCreditsUsed(Math.max(0, credits.getTodayCreditsUsed() - creditsToRefund));
            creditsRepository.save(credits);
            logger.info("Refunded {} credits to user {}", creditsToRefund, user.getId());
        } catch (Exception e) {
            logger.error("Failed to refund credits for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private void checkFalBalance() throws IOException {
        Request request = new Request.Builder()
                .url("https://fal.ai/api/auth/key/info")
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return; // fail silently, let fal.ai reject it
            JSONObject json = new JSONObject(response.body().string());
            double balance = json.optDouble("balance", -1);
            if (balance == 0.0) {
                throw new IllegalStateException(
                        "Service temporarily unavailable. Please try again later.");
            }
        }
    }

    /**
     * Submit a job to fal.ai's async queue.
     * Returns the fal.ai request ID.
     */
    private String submitToFalQueue(String modelEndpoint, JSONObject payload) throws IOException {
        String url = FAL_QUEUE_URL + modelEndpoint;

        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Key " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("fal.ai submission failed (" + response.code() + "): " + errorBody);
            }
            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            String requestId = json.getString("request_id");
            logger.info("Submitted to fal.ai. Request ID: {}", requestId);
            return requestId;
        }
    }

    /**
     * Once status is COMPLETED, fetch the result JSON to get the video URL.
     */
    private String getResultUrl(AiVideoGen record, String falRequestId) throws IOException {
        String appId = record.getGenerationType() == AiVideoGen.GenerationType.IMAGE_TO_VIDEO
                ? extractAppId(record.getModel().getImageToVideoEndpoint())
                : extractAppId(record.getModel().getTextToVideoEndpoint());

        String resultUrl = FAL_QUEUE_URL + appId + "/requests/" + falRequestId;

        Request request = new Request.Builder()
                .url(resultUrl)
                .addHeader("Authorization", "Key " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            String body = response.body().string();
            JSONObject json = new JSONObject(body);

            // fal.ai returns: { "video": { "url": "https://..." } }
            if (json.has("video")) {
                return json.getJSONObject("video").optString("url");
            }
            return null;
        }
    }

    /**
     * Download the video from fal.ai's temporary CDN URL,
     * then upload it to Cloudflare R2 under the same folder structure as before.
     *
     * R2 path: ai_video_gen/{userId}/video_{falRequestId}.mp4
     *
     * @return the R2 path of the uploaded video
     */
    private String downloadAndUploadToR2(AiVideoGen record, String videoUrl) throws IOException {
        // Build R2 path — mirrors old local structure: ai_video_gen/{userId}/video_{requestId}.mp4
        String r2Path = R2_VIDEO_GEN_PREFIX + record.getUser().getId()
                + "/video_" + record.getFalRequestId() + ".mp4";

        // Download from fal.ai to a temp file
        File tempFile = downloadToTemp(videoUrl, record.getFalRequestId());
        try {
            // Upload temp file to R2
            cloudflareR2Service.uploadFile(tempFile, r2Path);
            logger.info("Uploaded video to R2: {}", r2Path);
        } finally {
            // Always clean up the temp file
            if (tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                    logger.debug("Deleted temp video file: {}", tempFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                }
            }
        }

        return r2Path;
    }

    /**
     * Downloads a video from a URL into a temporary local file.
     * The file is written to the configured temp directory.
     */
    private File downloadToTemp(String videoUrl, String falRequestId) throws IOException {
        File tempDirectory = new File(tempDir, "ai_video_gen");
        tempDirectory.mkdirs();

        File tempFile = new File(tempDirectory, "tmp_" + falRequestId + ".mp4");

        Request downloadRequest = new Request.Builder()
                .url(videoUrl)
                .get()
                .build();

        try (Response response = httpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to download video from fal.ai: " + response.code());
            }
            try (InputStream inputStream = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        }

        logger.debug("Downloaded video to temp: {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    private AiVideoGen buildRecord(User user, VideoGenModel model,
                                   AiVideoGen.GenerationType type,
                                   String prompt, String negativePrompt,
                                   String referenceImagePath,
                                   int durationSeconds, boolean audioEnabled,
                                   String aspectRatio, int creditsUsed,
                                   String falRequestId) {
        AiVideoGen record = new AiVideoGen();
        record.setUser(user);
        record.setModel(model);
        record.setGenerationType(type);
        record.setPrompt(prompt);
        record.setNegativePrompt(negativePrompt);
        record.setReferenceImagePath(referenceImagePath);
        record.setDurationSeconds(durationSeconds);
        record.setAudioEnabled(audioEnabled);
        record.setAspectRatio(aspectRatio);
        record.setCreditsUsed(creditsUsed);
        record.setFalRequestId(falRequestId);
        record.setStatus(AiVideoGen.Status.PENDING);
        record.setResolution(model.getResolution());
        return record;
    }

    private UserVideoGenCredits getOrCreateCreditsRecord(User user) {
        String month = YearMonth.now().toString();
        return creditsRepository.findByUserAndCreditMonth(user, month)
                .orElseGet(() -> {
                    UserVideoGenCredits newRecord = new UserVideoGenCredits(user, YearMonth.now());
                    newRecord.setLastResetDate(LocalDate.now().toString());
                    return creditsRepository.save(newRecord);
                });
    }

    /**
     * Resets today's credit usage if the date has changed since last access.
     * This handles the daily cap reset automatically without a cron job.
     */
    private void resetDailyIfNeeded(UserVideoGenCredits credits) {
        String today = LocalDate.now().toString();
        if (!today.equals(credits.getLastResetDate())) {
            credits.setTodayCreditsUsed(0);
            credits.setLastResetDate(today);
            creditsRepository.save(credits);
        }
    }

    /**
     * Encode an uploaded image as a base64 data URI for sending to fal.ai.
     * Used when submitting image-to-video jobs without a public URL.
     */
    public String encodeImageAsDataUri(MultipartFile imageFile) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(imageFile.getBytes());
        String contentType = imageFile.getContentType() != null
                ? imageFile.getContentType() : "image/jpeg";
        return "data:" + contentType + ";base64," + base64;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "image.jpg";
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    /**
     * fal.ai queue status/result URLs use only the app ID (owner/app-name),
     * NOT the full model endpoint path used for submission.
     *
     * Example:
     *   Submission : https://queue.fal.run/fal-ai/wan-25-preview/text-to-video
     *   Status/Result: https://queue.fal.run/fal-ai/wan-25-preview/requests/{id}/status
     *
     * So we strip everything after the second path segment.
     * "fal-ai/wan-25-preview/text-to-video" → "fal-ai/wan-25-preview"
     * "fal-ai/kling-video/v2.5-turbo/pro/text-to-video" → "fal-ai/kling-video"
     */
    private String extractAppId(String modelEndpoint) {
        // Split on "/" and take only the first two segments: owner/app-name
        String[] parts = modelEndpoint.split("/");
        if (parts.length >= 2) {
            return parts[0] + "/" + parts[1];
        }
        return modelEndpoint; // fallback: return as-is
    }
}