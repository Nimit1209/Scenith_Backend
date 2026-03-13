package com.example.Scenith.service;


import com.example.Scenith.entity.ExternalTtsDailyUsage;
import com.example.Scenith.entity.ExternalTtsUsage;
import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ExternalTtsDailyUsageRepository;
import com.example.Scenith.repository.ExternalTtsUsageRepository;
import com.example.Scenith.repository.SoleTTSRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class ExternalTtsService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalTtsService.class);

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Value("${azure.tts.key}")
    private String azureApiKey;

    @Value("${azure.tts.region}")
    private String azureRegion;

    private final SoleTTSRepository soleTTSRepository;
    private final ExternalTtsUsageRepository usageRepository;
    private final ExternalTtsDailyUsageRepository dailyUsageRepository;
    private final PlanLimitsService planLimitsService;
    private final CloudflareR2Service cloudflareR2Service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ExternalTtsService(
            SoleTTSRepository soleTTSRepository,
            ExternalTtsUsageRepository usageRepository,
            ExternalTtsDailyUsageRepository dailyUsageRepository,
            PlanLimitsService planLimitsService,
            CloudflareR2Service cloudflareR2Service) {
        this.soleTTSRepository = soleTTSRepository;
        this.usageRepository = usageRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.planLimitsService = planLimitsService;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    public SoleTTS generateTTS(User user, String text, String voiceId,
                               SoleTTS.TtsProvider provider, Double speed) throws IOException, InterruptedException {

        // 1. Access check — BASIC users cannot use external providers
        if (!planLimitsService.hasExternalTtsAccess(user)) {
            throw new IllegalStateException(
                    "External AI voices require an active paid plan (Creator Lite or higher)"
            );
        }

        // 2. Basic validation
        if (text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("Text is required");
        if (voiceId == null || voiceId.trim().isEmpty())
            throw new IllegalArgumentException("Voice ID is required");

        // 3. Per-request character limit
        long maxChars = planLimitsService.getMaxExternalTtsCharsPerRequest(user);
        if (maxChars > 0 && text.length() > maxChars) {
            throw new IllegalArgumentException(
                    "Text exceeds limit for your plan (" + maxChars + " chars per request)"
            );
        }

        // 4. Monthly quota check
        long monthlyLimit = planLimitsService.getMonthlyExternalTtsLimit(user);
        if (monthlyLimit > 0) {
            long used = getMonthlyUsage(user, provider);
            if (used + text.length() > monthlyLimit) {
                throw new IllegalStateException(
                        "Monthly " + provider + " voice limit reached (Limit: " + monthlyLimit + ", Used: " + used + ")"
                );
            }
        }

        // 5. Daily quota check
        long dailyLimit = planLimitsService.getDailyExternalTtsLimit(user);
        if (dailyLimit > 0) {
            long usedToday = getDailyUsage(user, provider);
            if (usedToday + text.length() > dailyLimit) {
                throw new IllegalStateException(
                        "Daily " + provider + " voice limit reached (Daily Limit: " + dailyLimit + ", Used: " + usedToday + ")"
                );
            }
        }

        // 6. Route to correct provider
        byte[] audioBytes = switch (provider) {
            case OPENAI -> generateWithOpenAI(text, voiceId, speed);
            case AZURE  -> generateWithAzure(text, voiceId, speed);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };

        // 7. Write to temp file → upload to R2 → delete temp file
        //    Mirrors the pattern in SoleTTSService exactly:
        //    cloudflareR2Service.uploadFile(r2Path, tempFile)
        String prefix = provider.name().toLowerCase();
        String audioFileName = prefix + "_tts_" + System.currentTimeMillis() + ".mp3";
        String r2Path = "audio/sole_tts/" + user.getId() + "/" + audioFileName;

        String tempAudioPath = System.getProperty("java.io.tmpdir")
                + File.separator + "videoeditor"
                + File.separator + audioFileName;
        File tempAudioFile = new File(tempAudioPath);

        // Ensure temp directory exists
        Path tempDir = tempAudioFile.toPath().getParent();
        Files.createDirectories(tempDir);

        try {
            try (FileOutputStream out = new FileOutputStream(tempAudioFile)) {
                out.write(audioBytes);
            }
            logger.info("Saved {} TTS audio to temp file: {}", provider, tempAudioFile.getAbsolutePath());

            // ✅ Correct signature: uploadFile(String r2Path, File file)
            cloudflareR2Service.uploadFile(r2Path, tempAudioFile);
            logger.info("Uploaded {} TTS audio to R2: {}", provider, r2Path);

        } finally {
            // Always clean up temp file — mirrors SoleTTSService finally block
            if (tempAudioFile.exists()) {
                try {
                    Files.delete(tempAudioFile.toPath());
                    logger.debug("Deleted temp TTS file: {}", tempAudioFile.getAbsolutePath());
                } catch (IOException ex) {
                    logger.warn("Failed to delete temp TTS file: {}, error: {}", tempAudioFile.getAbsolutePath(), ex.getMessage());
                }
            }
        }

        // 8. Generate signed URLs — mirrors SoleTTSService exactly
        Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600); // 1 hour

        // 9. Persist SoleTTS record — store R2 path (same as SoleTTSService.audioPath)
        SoleTTS soleTTS = new SoleTTS();
        soleTTS.setUser(user);
        soleTTS.setAudioPath(r2Path);
        soleTTS.setCreatedAt(LocalDateTime.now());
        soleTTS.setProvider(provider);
        // Populate CDN & presigned URLs if the entity supports them
        soleTTS.setCdnUrl(urls.get("cdnUrl"));
        soleTTS.setPresignedUrl(urls.get("presignedUrl"));
        soleTTSRepository.save(soleTTS);

        // 10. Update usage
        updateMonthlyUsage(user, provider, text.length());
        updateDailyUsage(user, provider, text.length());

        logger.info("{} TTS generated for user {}: {} chars → {}", provider, user.getId(), text.length(), urls.get("cdnUrl"));
        return soleTTS;
    }

    // ─── OpenAI TTS ───────────────────────────────────────────────────────────

    private byte[] generateWithOpenAI(String text, String voiceId, Double speed)
            throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "tts-1");
        body.put("input", text);
        body.put("voice", voiceId);
        if (speed != null && speed != 1.0) {
            body.put("speed", speed); // OpenAI supports 0.25–4.0 natively
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                .header("Authorization", "Bearer " + openAiApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI TTS error " + response.statusCode() + ": " + new String(response.body()));
        }
        return response.body();
    }

    // ─── Azure TTS ────────────────────────────────────────────────────────────

    private byte[] generateWithAzure(String text, String voiceId, Double speed)
            throws IOException, InterruptedException {
        String rateAttr = (speed != null && speed != 1.0)
                ? String.format("%.2f", speed)
                : "1.0";

        String ssml = "<speak version='1.0' xml:lang='en-US'>" +
                "<voice name='" + voiceId + "'>" +
                "<prosody rate='" + rateAttr + "'>" + text + "</prosody>" +
                "</voice>" +
                "</speak>";

        String url = "https://" + azureRegion + ".tts.speech.microsoft.com/cognitiveservices/v1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Ocp-Apim-Subscription-Key", azureApiKey)
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                .POST(HttpRequest.BodyPublishers.ofString(ssml))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("Azure TTS error " + response.statusCode() + ": " + new String(response.body()));
        }
        return response.body();
    }

    // ─── Usage Tracking ───────────────────────────────────────────────────────

    public long getMonthlyUsage(User user, SoleTTS.TtsProvider provider) {
        return usageRepository.findByUserAndProviderAndMonth(user, provider, YearMonth.now())
                .map(ExternalTtsUsage::getCharactersUsed)
                .orElse(0L);
    }

    public long getDailyUsage(User user, SoleTTS.TtsProvider provider) {
        return dailyUsageRepository.findByUserAndProviderAndUsageDate(user, provider, LocalDate.now())
                .map(ExternalTtsDailyUsage::getCharactersUsed)
                .orElse(0L);
    }

    private void updateMonthlyUsage(User user, SoleTTS.TtsProvider provider, long chars) {
        YearMonth month = YearMonth.now();
        ExternalTtsUsage usage = usageRepository
                .findByUserAndProviderAndMonth(user, provider, month)
                .orElseGet(() -> new ExternalTtsUsage(user, provider, month));
        usage.setCharactersUsed(usage.getCharactersUsed() + chars);
        usageRepository.save(usage);
    }

    private void updateDailyUsage(User user, SoleTTS.TtsProvider provider, long chars) {
        LocalDate today = LocalDate.now();
        ExternalTtsDailyUsage usage = dailyUsageRepository
                .findByUserAndProviderAndUsageDate(user, provider, today)
                .orElseGet(() -> new ExternalTtsDailyUsage(user, provider, today));
        usage.setCharactersUsed(usage.getCharactersUsed() + chars);
        dailyUsageRepository.save(usage);
    }
}