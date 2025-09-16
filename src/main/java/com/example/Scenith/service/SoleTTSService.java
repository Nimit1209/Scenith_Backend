package com.example.Scenith.service;

import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserTtsUsage;
import com.example.Scenith.repository.SoleTTSRepository;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SoleTTSService {

    private static final Logger logger = LoggerFactory.getLogger(SoleTTSService.class);

    private final SoleTTSRepository soleTTSRepository;
    private final UserTtsUsageRepository userTtsUsageRepository;
    private final CloudflareR2Service cloudflareR2Service;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${google.credentials.path}")
    private String credentialsPath;

    public SoleTTS generateTTS(
            User user,
            String text,
            String voiceName,
            String languageCode) throws IOException, InterruptedException {
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

        // Enforce 15-minute limit (~13,500 characters/user/month)
        if (text.length() > 13_500) {
            throw new IllegalArgumentException("Text exceeds 15-minute limit (~13,500 characters)");
        }

        // Check user TTS usage
        long userUsage = getUserTtsUsage(user);
        if (userUsage + text.length() > 13_500) {
            throw new IllegalStateException("User exceeded monthly TTS limit (13,500 characters)");
        }

        // Generate audio using Google Cloud TTS
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

        // Ensure parent directory exists
        Path tempDir = tempAudioFile.toPath().getParent();
        Files.createDirectories(tempDir);
        if (!Files.isWritable(tempDir)) {
            logger.error("Directory is not writable: {}", tempDir);
            throw new IOException("Cannot write to directory: " + tempDir);
        }

        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create(settings)) {
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(languageCode)
                    .setName(voiceName)
                    .build();
            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .build();
            SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
            ByteString audioContent = response.getAudioContent();

            // Save audio to temporary file
            try (FileOutputStream out = new FileOutputStream(tempAudioFile)) {
                out.write(audioContent.toByteArray());
            }
            logger.info("Saved TTS audio to temporary file: {}", tempAudioFile.getAbsolutePath());

            // Verify file exists
            if (!tempAudioFile.exists()) {
                logger.error("Temporary audio file not created: {}", tempAudioFile.getAbsolutePath());
                throw new IOException("Failed to create temporary audio file: " + tempAudioFile.getAbsolutePath());
            }

            // Define R2 paths
            String userR2Dir = "audio/sole_tts/" + user.getId();
            String audioFileName = "tts_" + System.currentTimeMillis() + ".mp3";
            String audioR2Path = userR2Dir + "/" + audioFileName;

            // Upload audio to R2
            cloudflareR2Service.uploadFile(audioR2Path, tempAudioFile);
            logger.info("Uploaded TTS audio to R2: {}", audioR2Path);

            String audioPath = audioR2Path;

            // Generate waveform JSON
            String waveformJsonPath = generateAndSaveWaveformJson(audioPath, user.getId());

            // Set SoleTTS fields
            soleTTS.setAudioPath(audioPath);
            soleTTS.setCreatedAt(LocalDateTime.now());

            // Save to database
            soleTTSRepository.save(soleTTS);

            // Update TTS usage
            updateUserTtsUsage(user, text.length());

            // Generate and set URLs
            Map<String, String> urls = cloudflareR2Service.generateUrls(audioR2Path, 3600); // 1 hour expiration for presigned
            soleTTS.setCdnUrl(urls.get("cdnUrl"));
            soleTTS.setPresignedUrl(urls.get("presignedUrl"));

            return soleTTS;
        } finally {
            // Clean up temporary file
            if (tempAudioFile.exists()) {
                try {
                    Files.delete(tempAudioFile.toPath());
                    logger.debug("Deleted temporary audio file: {}", tempAudioFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary audio file: {}, error: {}", tempAudioFile.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    private long getUserTtsUsage(User user) {
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
}