package com.example.Scenith.service;

import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserDailyTtsUsage;
import com.example.Scenith.entity.UserTtsUsage;
import com.example.Scenith.repository.SoleTTSRepository;
import com.example.Scenith.repository.UserDailyTtsUsageRepository;
import com.example.Scenith.repository.UserTtsUsageRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SoleTTSService {

    private static final Logger logger = LoggerFactory.getLogger(SoleTTSService.class);

    private final SoleTTSRepository soleTTSRepository;
    private final UserTtsUsageRepository userTtsUsageRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final UserDailyTtsUsageRepository userDailyTtsUsageRepository;
    private final ProcessingEmailHelper emailHelper;
    private final UpgradeEmailSchedulerService upgradeEmailSchedulerService;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${google.credentials.path}")
    private String credentialsPath;

    public SoleTTS generateTTS(
            User user,
            String text,
            String voiceName,
            String languageCode,
            String emotion,
            Map<String, String> customConfig) throws IOException, InterruptedException {

        // Validate input
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text is required and cannot be empty");
        }
        if (voiceName == null || voiceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Voice name is required");
        }
        if (languageCode == null || languageCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Language code is required");
        }

        // Check max chars per request
        long maxCharsPerRequest = user.getMaxCharsPerRequest();
        if (maxCharsPerRequest > 0 && text.length() > maxCharsPerRequest) {
            throw new IllegalArgumentException(
                    "Text exceeds max characters allowed per request for " + user.getRole() +
                            " plan (Limit: " + maxCharsPerRequest + " characters per request)"
            );
        }

        // Check monthly TTS usage (skip if unlimited)
        long monthlyLimit = user.getMonthlyTtsLimit();
        if (monthlyLimit > 0) {
            long userUsage = getUserTtsUsage(user);
            if (userUsage + text.length() > monthlyLimit) {
                throw new IllegalStateException(
                        "AI Voice Generation limit exceeded for plan: " + user.getRole() +
                                " (Limit: " + monthlyLimit + ", Used: " + userUsage + ")"
                );
            }
        }

        // Check daily TTS usage (skip if unlimited)
        long dailyLimit = user.getDailyTtsLimit();
        if (dailyLimit > 0) {
            long dailyUsage = getUserDailyTtsUsage(user);
            if (dailyUsage + text.length() > dailyLimit) {
                throw new IllegalStateException(
                        "Daily AI Voice Generation limit exceeded for plan: " + user.getRole() +
                                " (Daily Limit: " + dailyLimit + ", Used Today: " + dailyUsage + ")"
                );
            }
        }

        // ────────────────────────────────────────────────
        // Google Cloud TTS setup
        // ────────────────────────────────────────────────
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath));
        TextToSpeechSettings settings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        SoleTTS soleTTS = new SoleTTS();
        soleTTS.setUser(user);

        // Temporary file for audio
        String tempAudioFileName = "tts_temp_" + System.currentTimeMillis() + ".mp3";
        String tempAudioPath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + tempAudioFileName;
        File tempAudioFile = new File(tempAudioPath);

        // Ensure temp directory exists
        Path tempDir = tempAudioFile.toPath().getParent();
        Files.createDirectories(tempDir);
        if (!Files.isWritable(tempDir)) {
            logger.error("Directory is not writable: {}", tempDir);
            throw new IOException("Cannot write to directory: " + tempDir);
        }

        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create(settings)) {
            // Apply emotion preset (your local logic)
            Map<String, String> finalSsmlConfig = applyEmotionPreset(emotion, customConfig);

            // Build SSML or plain text
            String inputText = (finalSsmlConfig != null && !finalSsmlConfig.isEmpty())
                    ? buildSSMLText(text, finalSsmlConfig)
                    : text;

            SynthesisInput.Builder inputBuilder = SynthesisInput.newBuilder();
            if (finalSsmlConfig != null && !finalSsmlConfig.isEmpty()) {
                inputBuilder.setSsml(inputText);
            } else {
                inputBuilder.setText(inputText);
            }
            SynthesisInput input = inputBuilder.build();

            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(languageCode)
                    .setName(voiceName)
                    .build();

            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .build();

            SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
            ByteString audioContent = response.getAudioContent();

            // Save to temporary file
            try (FileOutputStream out = new FileOutputStream(tempAudioFile)) {
                out.write(audioContent.toByteArray());
            }
            logger.info("Saved TTS audio to temporary file: {}", tempAudioFile.getAbsolutePath());

            if (!tempAudioFile.exists()) {
                logger.error("Temporary audio file not created: {}", tempAudioFile.getAbsolutePath());
                throw new IOException("Failed to create temporary audio file");
            }

            // ────────────────────────────────────────────────
            // Upload to Cloudflare R2 (server pattern)
            // ────────────────────────────────────────────────
            String userR2Dir = "audio/sole_tts/" + user.getId();
            String audioFileName = "tts_" + System.currentTimeMillis() + ".mp3";
            String audioR2Path = userR2Dir + "/" + audioFileName;

            cloudflareR2Service.uploadFile(audioR2Path, tempAudioFile);
            logger.info("Uploaded TTS audio to R2: {}", audioR2Path);

            String audioPath = audioR2Path;

            // Generate waveform (server pattern)
            String waveformJsonPath = generateAndSaveWaveformJson(audioPath, user.getId());

            // Set fields
            soleTTS.setAudioPath(audioPath);
            soleTTS.setCreatedAt(LocalDateTime.now());

            // Save entity
            soleTTSRepository.save(soleTTS);

            // Update usage counters
            updateUserTtsUsage(user, text.length());
            updateUserDailyTtsUsage(user, text.length());

            // Generate signed URLs
            Map<String, String> urls = cloudflareR2Service.generateUrls(audioR2Path, 3600); // 1 hour
            soleTTS.setCdnUrl(urls.get("cdnUrl"));
            soleTTS.setPresignedUrl(urls.get("presignedUrl"));

            // Notifications (server pattern)
            emailHelper.sendProcessingCompleteEmail(
                    user,
                    ProcessingEmailHelper.ServiceType.TTS,
                    audioFileName,
                    urls.get("cdnUrl"),
                    soleTTS.getId()
            );

            upgradeEmailSchedulerService.scheduleUpgradeEmail(user, "TTS_GENERATION");

            return soleTTS;
        } finally {
            // Clean up temp file (important on server)
            if (tempAudioFile.exists()) {
                try {
                    Files.delete(tempAudioFile.toPath());
                    logger.debug("Deleted temporary audio file: {}", tempAudioFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temp file: {}, error: {}", tempAudioFile.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    public long getUserTtsUsage(User user) {
        YearMonth currentMonth = YearMonth.now();
        return userTtsUsageRepository.findByUserAndMonth(user, currentMonth)
                .map(UserTtsUsage::getCharactersUsed)
                .orElse(0L);
    }

    private void updateUserTtsUsage(User user, long characters) {
        YearMonth currentMonth = YearMonth.now();
        UserTtsUsage usage = userTtsUsageRepository.findByUserAndMonth(user, currentMonth)
                .orElseGet(() -> new UserTtsUsage(user, currentMonth));
        usage.setCharactersUsed(usage.getCharactersUsed() + characters);
        userTtsUsageRepository.save(usage);
    }

    private String generateAndSaveWaveformJson(String audioPath, Long userId) throws IOException, InterruptedException {
        String userR2Dir = "audio/sole_tts/" + userId;
        String waveformFileName = "waveform_" + System.currentTimeMillis() + ".json";
        String waveformR2Path = userR2Dir + "/waveforms/" + waveformFileName;

        String tempWaveformPath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + "waveform_temp_" + System.currentTimeMillis() + ".json";
        File tempWaveformFile = new File(tempWaveformPath);

        // Ensure parent directory for waveform
        Path tempWaveformDir = tempWaveformFile.toPath().getParent();
        Files.createDirectories(tempWaveformDir);
        if (!Files.isWritable(tempWaveformDir)) {
            logger.error("Waveform directory is not writable: {}", tempWaveformDir);
            throw new IOException("Cannot write to waveform directory: " + tempWaveformDir);
        }

        File tempAudioDownload = null;
        try {
            // Placeholder for waveform generation
            // If needed, download audio for processing
            tempAudioDownload = new File(System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + "temp_download_" + System.currentTimeMillis() + ".mp3");
            cloudflareR2Service.downloadFile(audioPath, tempAudioDownload.getAbsolutePath());
            // Generate real waveform here (e.g., using FFmpeg or JAVE)
            // For now, dummy data
            Files.write(tempWaveformFile.toPath(), "{}".getBytes());

            // Upload waveform to R2
            cloudflareR2Service.uploadFile(waveformR2Path, tempWaveformFile);
            logger.info("Uploaded waveform JSON to R2: {}", waveformR2Path);

            return waveformR2Path;
        } finally {
            if (tempWaveformFile.exists()) {
                try {
                    Files.delete(tempWaveformFile.toPath());
                    logger.debug("Deleted temporary waveform file: {}", tempWaveformFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary waveform file: {}, error: {}", tempWaveformFile.getAbsolutePath(), e.getMessage());
                }
            }
            if (tempAudioDownload != null && tempAudioDownload.exists()) {
                try {
                    Files.delete(tempAudioDownload.toPath());
                    logger.debug("Deleted temporary downloaded audio: {}", tempAudioDownload.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary downloaded audio: {}", tempAudioDownload.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    private String buildSSMLText(String text, Map<String, String> ssmlConfig) {
        if (ssmlConfig == null || ssmlConfig.isEmpty()) {
            return text;
        }

        StringBuilder ssml = new StringBuilder("<speak>");

        // Build prosody tag attributes
        List<String> prosodyAttrs = new ArrayList<>();
        if (ssmlConfig.containsKey("rate")) {
            prosodyAttrs.add("rate=\"" + ssmlConfig.get("rate") + "\"");
        }
        if (ssmlConfig.containsKey("pitch")) {
            prosodyAttrs.add("pitch=\"" + ssmlConfig.get("pitch") + "\"");
        }
        if (ssmlConfig.containsKey("volume")) {
            prosodyAttrs.add("volume=\"" + ssmlConfig.get("volume") + "\"");
        }

        if (!prosodyAttrs.isEmpty()) {
            ssml.append("<prosody ").append(String.join(" ", prosodyAttrs)).append(">");
        }

        // Add emphasis if specified
        if (ssmlConfig.containsKey("emphasis")) {
            ssml.append("<emphasis level=\"").append(ssmlConfig.get("emphasis")).append("\">");
            ssml.append(text);
            ssml.append("</emphasis>");
        } else {
            ssml.append(text);
        }

        if (!prosodyAttrs.isEmpty()) {
            ssml.append("</prosody>");
        }

        ssml.append("</speak>");
        return ssml.toString();
    }
    public long getUserDailyTtsUsage(User user) {
        LocalDate today = LocalDate.now();
        return userDailyTtsUsageRepository.findByUserAndUsageDate(user, today)
                .map(UserDailyTtsUsage::getCharactersUsed)
                .orElse(0L);
    }

    private void updateUserDailyTtsUsage(User user, long characters) {
        LocalDate today = LocalDate.now();
        UserDailyTtsUsage usage = userDailyTtsUsageRepository.findByUserAndUsageDate(user, today)
                .orElseGet(() -> new UserDailyTtsUsage(user, today));
        usage.setCharactersUsed(usage.getCharactersUsed() + characters);
        userDailyTtsUsageRepository.save(usage);
    }

    private Map<String, String> applyEmotionPreset(String emotion, Map<String, String> customConfig) {
        Map<String, String> ssmlConfig = new HashMap<>();

        // Apply emotion preset with EXTREME values
        switch (emotion.toLowerCase()) {
            case "happy":
            case "excited":
                ssmlConfig.put("rate", "1.4");      // Much faster
                ssmlConfig.put("pitch", "+8st");    // Noticeably higher
                ssmlConfig.put("volume", "+4dB");   // Louder
                ssmlConfig.put("emphasis", "strong");
                break;
            case "calm":
            case "relaxed":
                ssmlConfig.put("rate", "0.75");     // Significantly slower
                ssmlConfig.put("pitch", "-4st");    // Lower
                ssmlConfig.put("volume", "-2dB");   // Softer
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "angry":
            case "intense":
                ssmlConfig.put("rate", "1.3");      // Fast and aggressive
                ssmlConfig.put("pitch", "+6st");    // Higher tension
                ssmlConfig.put("volume", "+6dB");   // Much louder
                ssmlConfig.put("emphasis", "strong");
                break;
            case "sad":
            case "somber":
                ssmlConfig.put("rate", "0.7");      // Very slow
                ssmlConfig.put("pitch", "-6st");    // Much lower
                ssmlConfig.put("volume", "-3dB");   // Quieter
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "announcer":
                ssmlConfig.put("rate", "1.0");
                ssmlConfig.put("pitch", "-2st");    // Authoritative
                ssmlConfig.put("volume", "+5dB");   // Clear and loud
                ssmlConfig.put("emphasis", "strong");
                break;
            case "meditation":
                ssmlConfig.put("rate", "0.6");      // Extremely slow
                ssmlConfig.put("pitch", "-5st");    // Deep and calming
                ssmlConfig.put("volume", "-1dB");
                ssmlConfig.put("emphasis", "reduced");
                break;
            case "enthusiastic":
                ssmlConfig.put("rate", "1.5");      // Very fast
                ssmlConfig.put("pitch", "+10st");   // Very high energy
                ssmlConfig.put("volume", "+5dB");   // Energetic volume
                ssmlConfig.put("emphasis", "strong");
                break;
            case "professional":
                ssmlConfig.put("rate", "0.95");
                ssmlConfig.put("pitch", "0st");     // Neutral
                ssmlConfig.put("volume", "+1dB");   // Clear
                // No emphasis for professional
                break;
            default:
                // Default/neutral - no modifications
                break;
        }

        // Override with custom config if provided
        if (customConfig != null && !customConfig.isEmpty()) {
            ssmlConfig.putAll(customConfig);
        }

        return ssmlConfig;
    }
}