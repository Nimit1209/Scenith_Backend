package com.example.Scenith.service;

import com.example.Scenith.dto.Keyframe;
import com.example.Scenith.dto.SubtitleDTO;
import com.example.Scenith.entity.SubtitleMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.SubtitleMediaRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SubtitleService {
  private static final Logger logger = LoggerFactory.getLogger(SubtitleService.class);

  private final JwtUtil jwtUtil;
  private final SubtitleMediaRepository subtitleMediaRepository;
  private final UserRepository userRepository;
  private final CloudflareR2Service cloudflareR2Service;
  private final ObjectMapper objectMapper;
  private final SqsClient sqsClient;
  private final EmailService emailService;
  private final SqsService sqsService;

  @Value("${app.base-dir:/temp}")
  private String baseDir;

  @Value("${python.path:/usr/local/bin/python3.11}")
  private String pythonPath;

  @Value("${app.subtitle-script-path:/app/scripts/whisper_subtitle.py}")
  private String subtitleScriptPath;

  @Value("${app.ffmpeg-path:/usr/local/bin/ffmpeg}")
  private String ffmpegPath;

  @Value("${sqs.queue.url}")
  private String videoExportQueueUrl;

  public SubtitleService(
          JwtUtil jwtUtil,
          SubtitleMediaRepository subtitleMediaRepository,
          UserRepository userRepository,
          CloudflareR2Service cloudflareR2Service,
          ObjectMapper objectMapper,
          SqsClient sqsClient, EmailService emailService, SqsService sqsService) {
    this.jwtUtil = jwtUtil;
    this.subtitleMediaRepository = subtitleMediaRepository;
    this.userRepository = userRepository;
    this.cloudflareR2Service = cloudflareR2Service;
    this.objectMapper = objectMapper;
    this.sqsClient = sqsClient;
      this.emailService = emailService;
      this.sqsService = sqsService;
  }

  public SubtitleMedia uploadMedia(User user, MultipartFile mediaFile) throws IOException {
    logger.info("Uploading media for user: {}", user.getId());

    if (mediaFile == null || mediaFile.isEmpty()) {
      logger.error("MultipartFile is null or empty for user: {}", user.getId());
      throw new IllegalArgumentException("Media file is null or empty");
    }

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    String tempFileName = "subtitle-" + System.currentTimeMillis() + "-" + mediaFile.getOriginalFilename();
    String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + tempFileName;

    File inputFile = null;
    try {
      inputFile = cloudflareR2Service.saveMultipartFileToTemp(mediaFile, tempInputPath);
      logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

      if (inputFile.length() == 0) {
        logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
        throw new IOException("Input file is empty");
      }

      String r2OriginalPath = String.format("subtitles/%s/original/%s", user.getId(), mediaFile.getOriginalFilename());
      cloudflareR2Service.uploadFile(r2OriginalPath, inputFile);
      logger.info("Uploaded original media to R2: {}", r2OriginalPath);

      Map<String, String> originalUrls = cloudflareR2Service.generateUrls(r2OriginalPath, 3600);

      SubtitleMedia subtitleMedia = new SubtitleMedia();
      subtitleMedia.setUser(user);
      subtitleMedia.setOriginalFileName(mediaFile.getOriginalFilename());
      subtitleMedia.setOriginalPath(r2OriginalPath);
      subtitleMedia.setOriginalCdnUrl(originalUrls.get("cdnUrl"));
      subtitleMedia.setStatus("UPLOADED");
      subtitleMediaRepository.save(subtitleMedia);

      logger.info("Saved metadata for user: {}, media: {}", user.getId(), mediaFile.getOriginalFilename());
      return subtitleMedia;

    } finally {
      if (inputFile != null && inputFile.exists()) {
        try {
          Files.delete(inputFile.toPath());
          logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary input file: {}", inputFile.getAbsolutePath(), e);
        }
      }
    }
  }

  public SubtitleMedia generateSubtitles(User user, Long mediaId, Map<String, String> styleParams) throws IOException, InterruptedException {
    logger.info("Generating subtitles for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to generate subtitles for media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to generate subtitles for this media");
    }

    // Execute synchronously - NO queuing
    return generateSubtitlesTask(mediaId, user.getId(), styleParams);
  }

  public void queueProcessSubtitles(User user, Long mediaId) throws IOException {
    logger.info("Queueing subtitle processing for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to process subtitles for media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to process subtitles for this media");
    }

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      logger.error("No subtitles available to process for mediaId: {}", mediaId);
      throw new IllegalStateException("No subtitles available to process");
    }

    subtitleMedia.setStatus("QUEUED");
    subtitleMedia.setProgress(0.0);
    subtitleMediaRepository.save(subtitleMedia);
    Map<String, String> taskDetails = new HashMap<>();
    taskDetails.put("taskType", "PROCESS_SUBTITLES");  // ← ALREADY HAS THIS ✓
    taskDetails.put("mediaId", mediaId.toString());
    taskDetails.put("userId", user.getId().toString());

    String messageBody = objectMapper.writeValueAsString(taskDetails);
    sqsService.sendMessage(messageBody, videoExportQueueUrl);  // ← UPDATE THIS LINE
    // Remove the old SendMessageRequest code below:
    // SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
    //         .queueUrl(videoExportQueueUrl)
    //         .messageBody(messageBody)
    //         .build();
    // sqsClient.sendMessage(sendMsgRequest);

    logger.info("Successfully queued subtitle processing for mediaId: {}", mediaId);
  }

  public SubtitleMedia updateSingleSubtitle(User user, Long mediaId, String subtitleId, SubtitleDTO updatedSubtitle) throws IOException {
    logger.info("Updating single subtitle for user: {}, mediaId: {}, subtitleId: {}", user.getId(), mediaId, subtitleId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to update subtitle for media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to update subtitles for this media");
    }

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      logger.error("No subtitles available for mediaId: {}", mediaId);
      throw new IllegalStateException("No subtitles available to update");
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    boolean subtitleFound = false;

    for (int i = 0; i < subtitles.size(); i++) {
      if (subtitles.get(i).getId().equals(subtitleId)) {
        SubtitleDTO existingSubtitle = subtitles.get(i);

        // Merge only non-null fields from updatedSubtitle
        if (updatedSubtitle.getText() != null) existingSubtitle.setText(updatedSubtitle.getText());
        if (updatedSubtitle.getTimelineStartTime() != null) existingSubtitle.setTimelineStartTime(updatedSubtitle.getTimelineStartTime());
        if (updatedSubtitle.getTimelineEndTime() != null) existingSubtitle.setTimelineEndTime(updatedSubtitle.getTimelineEndTime());
        if (updatedSubtitle.getFontFamily() != null) existingSubtitle.setFontFamily(updatedSubtitle.getFontFamily());
        if (updatedSubtitle.getFontColor() != null) existingSubtitle.setFontColor(updatedSubtitle.getFontColor());
        if (updatedSubtitle.getBackgroundColor() != null) existingSubtitle.setBackgroundColor(updatedSubtitle.getBackgroundColor());
        if (updatedSubtitle.getScale() != null) existingSubtitle.setScale(updatedSubtitle.getScale());
        if (updatedSubtitle.getBackgroundOpacity() != null) existingSubtitle.setBackgroundOpacity(updatedSubtitle.getBackgroundOpacity());
        if (updatedSubtitle.getPositionX() != null) existingSubtitle.setPositionX(updatedSubtitle.getPositionX());
        if (updatedSubtitle.getPositionY() != null) existingSubtitle.setPositionY(updatedSubtitle.getPositionY());
        if (updatedSubtitle.getAlignment() != null) existingSubtitle.setAlignment(updatedSubtitle.getAlignment());
        if (updatedSubtitle.getOpacity() != null) existingSubtitle.setOpacity(updatedSubtitle.getOpacity());
        if (updatedSubtitle.getRotation() != null) existingSubtitle.setRotation(updatedSubtitle.getRotation());
        if (updatedSubtitle.getBackgroundH() != null) existingSubtitle.setBackgroundH(updatedSubtitle.getBackgroundH());
        if (updatedSubtitle.getBackgroundW() != null) existingSubtitle.setBackgroundW(updatedSubtitle.getBackgroundW());
        if (updatedSubtitle.getBackgroundBorderRadius() != null) existingSubtitle.setBackgroundBorderRadius(updatedSubtitle.getBackgroundBorderRadius());
        if (updatedSubtitle.getBackgroundBorderWidth() != null) existingSubtitle.setBackgroundBorderWidth(updatedSubtitle.getBackgroundBorderWidth());
        if (updatedSubtitle.getBackgroundBorderColor() != null) existingSubtitle.setBackgroundBorderColor(updatedSubtitle.getBackgroundBorderColor());
        if (updatedSubtitle.getTextBorderColor() != null) existingSubtitle.setTextBorderColor(updatedSubtitle.getTextBorderColor());
        if (updatedSubtitle.getTextBorderWidth() != null) existingSubtitle.setTextBorderWidth(updatedSubtitle.getTextBorderWidth());
        if (updatedSubtitle.getTextBorderOpacity() != null) existingSubtitle.setTextBorderOpacity(updatedSubtitle.getTextBorderOpacity());
        if (updatedSubtitle.getLetterSpacing() != null) existingSubtitle.setLetterSpacing(updatedSubtitle.getLetterSpacing());
        if (updatedSubtitle.getLineSpacing() != null) existingSubtitle.setLineSpacing(updatedSubtitle.getLineSpacing());

        if (existingSubtitle.getTimelineEndTime() <= existingSubtitle.getTimelineStartTime()) {
          logger.error("Invalid subtitle timing for mediaId: {}, subtitleId: {}", mediaId, subtitleId);
          throw new IllegalArgumentException("End time must be greater than start time");
        }

        subtitles.set(i, existingSubtitle);
        subtitleFound = true;
        break;
      }
    }

    if (!subtitleFound) {
      logger.error("Subtitle with id {} not found for mediaId: {}", subtitleId, mediaId);
      throw new IllegalArgumentException("Subtitle with id " + subtitleId + " not found");
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);

    logger.info("Successfully updated subtitle for user: {}, mediaId: {}, subtitleId: {}", user.getId(), mediaId, subtitleId);
    return subtitleMedia;
  }

  public SubtitleMedia updateMultipleSubtitles(User user, Long mediaId, List<SubtitleDTO> updatedSubtitles) throws IOException {
    logger.info("Updating multiple subtitles for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to update subtitles for media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to update subtitles for this media");
    }

    if (updatedSubtitles == null || updatedSubtitles.isEmpty()) {
      logger.error("Updated subtitles list is null or empty for mediaId: {}", mediaId);
      throw new IllegalArgumentException("Subtitles list cannot be empty");
    }

    for (SubtitleDTO subtitle : updatedSubtitles) {
      if (subtitle.getId() == null || subtitle.getText() == null || subtitle.getText().trim().isEmpty()) {
        logger.error("Invalid subtitle data for mediaId: {}", mediaId);
        throw new IllegalArgumentException("Subtitle id and text cannot be empty");
      }
      if (subtitle.getTimelineEndTime() <= subtitle.getTimelineStartTime()) {
        logger.error("Invalid subtitle timing for mediaId: {}", mediaId);
        throw new IllegalArgumentException("End time must be greater than start time for subtitle id: " + subtitle.getId());
      }
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    Map<String, SubtitleDTO> updatedSubtitlesMap = new HashMap<>();
    for (SubtitleDTO updatedSubtitle : updatedSubtitles) {
      updatedSubtitlesMap.put(updatedSubtitle.getId(), updatedSubtitle);
    }

    for (int i = 0; i < subtitles.size(); i++) {
      SubtitleDTO existingSubtitle = subtitles.get(i);
      if (updatedSubtitlesMap.containsKey(existingSubtitle.getId())) {
        subtitles.set(i, updatedSubtitlesMap.get(existingSubtitle.getId()));
      }
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);

    logger.info("Successfully updated multiple subtitles for user: {}, mediaId: {}", user.getId(), mediaId);
    return subtitleMedia;
  }

  public SubtitleMedia updateAllSubtitles(User user, Long mediaId, Map<String, String> styleParams) throws IOException {
    logger.info("Updating all subtitles for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to update subtitles for media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to update subtitles for this media");
    }

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      logger.error("No subtitles available for mediaId: {}", mediaId);
      throw new IllegalStateException("No subtitles available to update");
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    for (SubtitleDTO subtitle : subtitles) {
      subtitle.setFontFamily(styleParams.getOrDefault("fontFamily", subtitle.getFontFamily()));
      subtitle.setFontColor(styleParams.getOrDefault("fontColor", subtitle.getFontColor()));
      subtitle.setBackgroundColor(styleParams.getOrDefault("backgroundColor", subtitle.getBackgroundColor()));
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);

    logger.info("Successfully updated all subtitles for user: {}, mediaId: {}", user.getId(), mediaId);
    return subtitleMedia;
  }

  public List<SubtitleMedia> getUserSubtitleMedia(User user) {
    return subtitleMediaRepository.findByUser(user);
  }

  public User getUserFromToken(String token) {
    String email = jwtUtil.extractEmail(token.substring(7));
    return userRepository.findByEmail(email)
            .orElseThrow(() -> {
              logger.error("User not found for email extracted from token");
              return new RuntimeException("User not found");
            });
  }

  // Internal methods for SQS worker to process tasks
  public SubtitleMedia generateSubtitlesTask(Long mediaId, Long userId, Map<String, String> styleParams) throws IOException, InterruptedException {
    logger.info("Processing subtitle generation task for mediaId: {}, userId: {}", mediaId, userId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(userId)) {
      logger.error("User {} not authorized to generate subtitles for media {}", userId, mediaId);
      throw new IllegalArgumentException("Not authorized to generate subtitles for this media");
    }

    subtitleMedia.setStatus("PROCESSING");
    subtitleMediaRepository.save(subtitleMedia);

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "input-" + System.currentTimeMillis() + "-" + subtitleMedia.getOriginalFileName();
    String r2AudioPath = String.format("subtitles/%s/temp/%s/audio_%s.mp3", userId, mediaId, System.currentTimeMillis());

    File inputFile = null;
    File audioFile = null;
    try {
      inputFile = cloudflareR2Service.downloadFileWithRetry(subtitleMedia.getOriginalPath(), tempInputPath, 3);
      logger.debug("Downloaded media to: {}", inputFile.getAbsolutePath());

      if (!inputFile.exists() || inputFile.length() == 0) {
        logger.error("Input file is missing or empty: {}", tempInputPath);
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("Input file is missing or empty");
      }

      audioFile = new File(absoluteBaseDir + File.separator + "videoeditor" + File.separator + "audio_" + System.currentTimeMillis() + ".mp3");
      extractAudio(inputFile, audioFile.getAbsolutePath());
      cloudflareR2Service.uploadFile(r2AudioPath, audioFile);

      double audioDuration = getAudioDuration(audioFile);
      if (audioDuration <= 0) {
        logger.error("Audio file has invalid duration: {}", audioFile.getAbsolutePath());
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("Audio file has invalid duration");
      }

      List<Map<String, Object>> rawSubtitles = runWhisperScript(audioFile);
      if (rawSubtitles.isEmpty()) {
        logger.warn("No subtitles generated for mediaId: {}", mediaId);
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("No subtitles generated");
      }

      List<SubtitleDTO> subtitles = new ArrayList<>();
      for (Map<String, Object> raw : rawSubtitles) {
        double startTime = ((Number) raw.get("start")).doubleValue();
        double endTime = ((Number) raw.get("end")).doubleValue();
        String text = (String) raw.get("text");

        startTime = Math.max(0, startTime);
        endTime = Math.min(audioDuration, endTime);

        if (endTime > startTime && text != null && !text.trim().isEmpty()) {
          SubtitleDTO subtitle = new SubtitleDTO();
          subtitle.setId(UUID.randomUUID().toString());
          subtitle.setTimelineStartTime(startTime);
          subtitle.setTimelineEndTime(endTime);
          subtitle.setText(text.trim());

          subtitle.setFontFamily(styleParams != null && styleParams.containsKey("fontFamily") ?
                  styleParams.get("fontFamily") : "Montserrat Alternates Black");
          subtitle.setFontColor(styleParams != null && styleParams.containsKey("fontColor") ?
                  styleParams.get("fontColor") : "black");
          subtitle.setBackgroundColor(styleParams != null && styleParams.containsKey("backgroundColor") ?
                  styleParams.get("backgroundColor") : "white");
          subtitle.setBackgroundOpacity(1.0);
          subtitle.setPositionX(0);
          subtitle.setPositionY(500);
          subtitle.setAlignment("center");
          subtitle.setScale(1.5);
          subtitle.setBackgroundH(20);
          subtitle.setBackgroundW(40);
          subtitle.setBackgroundBorderRadius(12);

          subtitles.add(subtitle);
        }
      }

      subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
      subtitleMedia.setStatus("SUCCESS");
      subtitleMediaRepository.save(subtitleMedia);

      logger.info("Successfully generated subtitles for mediaId: {}", mediaId);
      return subtitleMedia;

    } finally {
      if (audioFile != null && audioFile.exists()) {
        try {
          Files.delete(audioFile.toPath());
          logger.debug("Deleted temporary audio file: {}", audioFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary audio file: {}", audioFile.getAbsolutePath(), e);
        }
      }
      if (inputFile != null && inputFile.exists()) {
        try {
          Files.delete(inputFile.toPath());
          logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary input file: {}", inputFile.getAbsolutePath(), e);
        }
      }
      try {
        cloudflareR2Service.deleteFile(r2AudioPath);
        logger.debug("Deleted temporary audio file from R2: {}", r2AudioPath);
      } catch (IOException e) {
        logger.warn("Failed to delete temporary audio file from R2: {}", r2AudioPath, e);
      }
    }
  }

  public SubtitleMedia processSubtitlesTask(Long mediaId, Long userId) throws IOException, InterruptedException {
    logger.info("Processing subtitles task for mediaId: {}, userId: {}", mediaId, userId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(userId)) {
      logger.error("User {} not authorized to process subtitles for media {}", userId, mediaId);
      throw new IllegalArgumentException("Not authorized to process subtitles for this media");
    }

    subtitleMedia.setStatus("PROCESSING");
    subtitleMedia.setProgress(0.0);
    subtitleMediaRepository.save(subtitleMedia);

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "input-" + System.currentTimeMillis() + "-" + subtitleMedia.getOriginalFileName();
    String outputFileName = "subtitled_" + subtitleMedia.getOriginalFileName();
    String tempOutputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + outputFileName;
    String r2ProcessedPath = String.format("subtitles/%s/processed/%s", userId, outputFileName);

    File inputFile = null;
    File outputFile = null;
    try {
      inputFile = cloudflareR2Service.downloadFileWithRetry(subtitleMedia.getOriginalPath(), tempInputPath, 3);
      logger.debug("Downloaded media to: {}", inputFile.getAbsolutePath());

      if (!inputFile.exists() || inputFile.length() == 0) {
        logger.error("Input file is missing or empty: {}", tempInputPath);
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("Input file is missing or empty");
      }

      validateInputFile(inputFile);

      Map<String, Object> videoInfo = getVideoInfo(inputFile);
      int canvasWidth = (int) videoInfo.get("width");
      int canvasHeight = (int) videoInfo.get("height");
      float fps = (float) videoInfo.get("fps");

      List<SubtitleDTO> subtitles = objectMapper.readValue(subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
      if (subtitles.isEmpty()) {
        logger.error("No valid subtitles to process for mediaId: {}", mediaId);
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("No valid subtitles to process");
      }

      double totalDuration = getVideoDuration(inputFile);
      if (totalDuration <= 0) {
        logger.error("Invalid video duration: {}", totalDuration);
        subtitleMedia.setStatus("FAILED");
        subtitleMediaRepository.save(subtitleMedia);
        throw new IOException("Invalid video duration");
      }

      outputFile = new File(tempOutputPath);
      Files.createDirectories(outputFile.toPath().getParent());
      renderSubtitledVideo(inputFile, outputFile, subtitles, canvasWidth, canvasHeight, fps, mediaId, totalDuration,userId);

      cloudflareR2Service.uploadFile(r2ProcessedPath, outputFile);
      logger.info("Uploaded processed media to R2: {}", r2ProcessedPath);

      Map<String, String> processedUrls = cloudflareR2Service.generateUrls(r2ProcessedPath, 3600);
      subtitleMedia.setProcessedFileName(outputFileName);
      subtitleMedia.setProcessedPath(r2ProcessedPath);
      subtitleMedia.setProcessedCdnUrl(processedUrls.get("cdnUrl"));
      subtitleMedia.setStatus("SUCCESS");
      subtitleMedia.setProgress(100.0);
      subtitleMediaRepository.save(subtitleMedia);
// ---- NEW: SEND EMAIL ----
      try {
        User user = subtitleMedia.getUser();
        Map<String, String> vars = Map.of(
                "userName",         Optional.ofNullable(user.getName()).orElse("Creator"),
                "originalFileName", subtitleMedia.getOriginalFileName(),
                "downloadUrl",      subtitleMedia.getProcessedCdnUrl()
        );

        emailService.sendTemplateEmail(
                user.getEmail(),
                "ai-voice-generation-campaign",       // This file exists
                "subtitle-processing-complete",       // New template ID we just added
                vars
        );

        logger.info("Processing-complete email sent to {} for mediaId {}", user.getEmail(), mediaId);
      } catch (Exception e) {
        logger.error("Failed to send processing-complete email for mediaId {}", mediaId, e);
        // Email failure does NOT affect video processing
      }

      logger.info("Successfully processed subtitles for mediaId: {}", mediaId);
      return subtitleMedia;

    } catch (Exception e) {
      logger.error("Failed to process subtitles for mediaId {}: {}", mediaId, e.getMessage(), e);
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      throw e;
    } finally {
      if (inputFile != null && inputFile.exists()) {
        try {
          Files.delete(inputFile.toPath());
          logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary input file: {}", inputFile.getAbsolutePath(), e);
        }
      }
      if (outputFile != null && outputFile.exists()) {
        try {
          Files.delete(outputFile.toPath());
          logger.debug("Deleted temporary output file: {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary output file: {}", outputFile.getAbsolutePath(), e);
        }
      }
    }
  }

  private double getAudioDuration(File audioFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath.replace("ffmpeg", "ffprobe"),
            "-i", audioFile.getAbsolutePath(),
            "-show_entries", "format=duration",
            "-v", "quiet",
            "-of", "json"
    );

    logger.debug("Executing FFprobe command for audio duration: {}", String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
        logger.debug("FFprobe output: {}", line);
      }
    }
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      logger.error("FFprobe failed to get audio duration for {}: {}", audioFile.getAbsolutePath(), output.toString());
      throw new IOException("FFprobe failed to get audio duration: " + output.toString());
    }

    try {
      Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
      Map<String, Object> format = (Map<String, Object>) result.get("format");
      if (format == null || !format.containsKey("duration")) {
        logger.error("No duration found in FFprobe output for {}: {}", audioFile.getAbsolutePath(), output.toString());
        throw new IOException("No duration found in FFprobe output");
      }
      return Double.parseDouble(format.get("duration").toString());
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse FFprobe output for {}: {}", audioFile.getAbsolutePath(), output.toString());
      throw new IOException("Failed to parse FFprobe output: " + output.toString());
    }
  }

  private void extractAudio(File inputFile, String audioFilePath) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-vn", "-acodec", "mp3",
            "-y", audioFilePath
    );

    logger.debug("Executing FFmpeg command for audio extraction: {}", String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
        logger.debug("FFmpeg audio extraction output: {}", line);
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      logger.error("FFmpeg audio extraction failed with exit code {}: {}", exitCode, output.toString());
      throw new IOException("FFmpeg audio extraction failed: " + output.toString());
    }

    File audioFile = new File(audioFilePath);
    if (!audioFile.exists() || audioFile.length() == 0) {
      logger.error("Audio file not created or empty: {}", audioFilePath);
      throw new IOException("Audio file not created or empty: " + audioFilePath);
    }

    List<String> probeCommand = Arrays.asList(
            ffmpegPath.replace("ffmpeg", "ffprobe"),
            "-i", audioFilePath,
            "-show_streams",
            "-select_streams", "a",
            "-print_format", "json",
            "-v", "quiet"
    );
    ProcessBuilder probePb = new ProcessBuilder(probeCommand);
    probePb.redirectErrorStream(true);
    Process probeProcess = probePb.start();
    StringBuilder probeOutput = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(probeProcess.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        probeOutput.append(line);
      }
    }
    int probeExitCode = probeProcess.waitFor();
    if (probeExitCode != 0) {
      logger.error("FFprobe failed to verify audio stream for {}: {}", audioFilePath, probeOutput.toString());
      throw new IOException("FFprobe failed to verify audio stream: " + probeOutput.toString());
    }

    try {
      Map<String, Object> probeResult = objectMapper.readValue(probeOutput.toString(), new TypeReference<Map<String, Object>>() {});
      List<?> streams = (List<?>) probeResult.getOrDefault("streams", Collections.emptyList());
      if (streams.isEmpty()) {
        logger.error("No audio stream found in file: {}", audioFilePath);
        throw new IOException("No audio stream found in file: " + audioFilePath);
      }
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse FFprobe output for {}: {}", audioFilePath, probeOutput.toString());
      throw new IOException("Failed to parse FFprobe output: " + probeOutput.toString());
    }
  }

  private List<Map<String, Object>> runWhisperScript(File audioFile) throws IOException, InterruptedException {
    File scriptFile = new File(subtitleScriptPath);
    if (!scriptFile.exists()) {
      logger.error("Subtitle script not found: {}", scriptFile.getAbsolutePath());
      throw new IOException("Subtitle script not found: " + scriptFile.getAbsolutePath());
    }

    List<String> command = Arrays.asList(
            pythonPath,
            scriptFile.getAbsolutePath(),
            audioFile.getAbsolutePath()
    );

    logger.debug("Executing Whisper command: {}", String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    StringBuilder errorOutput = new StringBuilder();

    try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
         BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      String line;
      while ((line = stdoutReader.readLine()) != null) {
        output.append(line);
        logger.debug("Whisper stdout: {}", line);
      }
      while ((line = stderrReader.readLine()) != null) {
        errorOutput.append(line).append("\n");
        logger.debug("Whisper stderr: {}", line);
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      logger.error("Whisper script failed with exit code {}: {}", exitCode, errorOutput.toString());
      throw new IOException("Whisper transcription failed with exit code " + exitCode + ": " + errorOutput.toString());
    }

    String outputStr = output.toString().trim();
    if (outputStr.isEmpty()) {
      logger.warn("No output from Whisper script: {}", errorOutput.toString());
      throw new IOException("No output from Whisper script: " + errorOutput.toString());
    }

    try {
      List<Map<String, Object>> subtitles = objectMapper.readValue(outputStr, new TypeReference<List<Map<String, Object>>>() {});
      logger.debug("Parsed {} subtitles from Whisper output", subtitles.size());
      return subtitles;
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse Whisper output as JSON: {}. Output: {}. Stderr: {}", e.getMessage(), outputStr, errorOutput.toString());
      throw new IOException("Invalid JSON output from Whisper script: " + outputStr, e);
    }
  }

  private Map<String, Object> getVideoInfo(File inputFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath,
            "-i", inputFile.getAbsolutePath(),
            "-f", "null",
            "-"
    );

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("FFmpeg failed to get video info: " + output.toString());
    }

    Map<String, Object> info = new HashMap<>();
    String outputStr = output.toString();
    Pattern resolutionPattern = Pattern.compile("Stream.*Video:.* (\\d+)x(\\d+).*?([0-9.]+) fps");
    Matcher matcher = resolutionPattern.matcher(outputStr);
    if (matcher.find()) {
      info.put("width", Integer.parseInt(matcher.group(1)));
      info.put("height", Integer.parseInt(matcher.group(2)));
      info.put("fps", Float.parseFloat(matcher.group(3)));
    } else {
      throw new IOException("Could not parse video info from FFmpeg output");
    }

    return info;
  }

  private void validateInputFile(File inputFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath.replace("ffmpeg", "ffprobe"),
            "-i", inputFile.getAbsolutePath(),
            "-show_streams",
            "-show_format",
            "-print_format", "json",
            "-v", "quiet"
    );

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
      }
    }
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      logger.error("FFprobe failed to validate input file {}: {}", inputFile.getAbsolutePath(), output.toString());
      throw new IOException("FFprobe failed to validate input file: " + output.toString());
    }

    try {
      Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
      List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
      if (streams.isEmpty()) {
        logger.error("No streams found in input file: {}", inputFile.getAbsolutePath());
        throw new IOException("No streams found in input file");
      }
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse FFprobe output for {}: {}", inputFile.getAbsolutePath(), output.toString());
      throw new IOException("Failed to parse FFprobe output: " + output.toString());
    }
  }

  private void renderSubtitledVideo(File inputFile, File outputFile, List<SubtitleDTO> subtitles,
                                    int canvasWidth, int canvasHeight, float fps, Long mediaId,
                                    double totalDuration, Long userId) throws IOException, InterruptedException {
    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    File tempDir = new File(absoluteBaseDir, "videoeditor/temp/" + mediaId);
    if (!tempDir.exists() && !tempDir.mkdirs()) {
      throw new IOException("Failed to create temp directory: " + tempDir.getAbsolutePath());
    }

    double batchSize = 8.0;
    List<String> tempVideoFiles = new ArrayList<>(); // Local file paths

    try {
      int batchIndex = 0;
      for (double startTime = 0; startTime < totalDuration; startTime += batchSize) {
        double endTime = Math.min(startTime + batchSize, totalDuration);
        String tempOutput = new File(tempDir, "batch_" + startTime + ".mp4").getAbsolutePath();
        tempVideoFiles.add(tempOutput); // Keep local paths

        // This will create the batch file locally
        renderBatch(inputFile, new File(tempOutput), subtitles, canvasWidth, canvasHeight,
                fps, mediaId, startTime, endTime, totalDuration, batchIndex, new ArrayList<>(), userId);
        batchIndex++;
      }

      // Concatenate all local batch files
      concatenateBatches(tempVideoFiles, outputFile.getAbsolutePath(), fps);
    } finally {
      // Clean up local batch files
      for (String tempVideo : tempVideoFiles) {
        try {
          Files.deleteIfExists(new File(tempVideo).toPath());
          logger.debug("Deleted temporary batch video: {}", tempVideo);
        } catch (IOException ex) {
          logger.warn("Failed to delete temporary batch video: {}", tempVideo, ex);
        }
      }

      // Clean up temp directory
      try {
        Files.deleteIfExists(tempDir.toPath());
      } catch (IOException e) {
        logger.warn("Failed to delete temp directory: {}", tempDir.getAbsolutePath(), e);
      }
    }
  }

  private void renderBatch(File inputFile, File outputFile, List<SubtitleDTO> subtitles,
                           int canvasWidth, int canvasHeight, float fps, Long mediaId,
                           double batchStart, double batchEnd, double totalDuration,
                           int batchIndex, List<String> r2TempTextFiles, Long userId)
          throws IOException, InterruptedException {
    double batchDuration = batchEnd - batchStart;
    logger.debug("Rendering batch from {} to {} seconds for mediaId: {}", batchStart, batchEnd, mediaId);

    List<String> command = new ArrayList<>();
    command.add(ffmpegPath);
    command.add("-ss");
    command.add(String.format("%.6f", batchStart));
    command.add("-t");
    command.add(String.format("%.6f", batchDuration));
    command.add("-i");
    command.add(inputFile.getAbsolutePath());

    StringBuilder filterComplex = new StringBuilder();
    Map<String, String> textInputIndices = new HashMap<>();
    int inputCount = 1;

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    File tempDir = new File(absoluteBaseDir, "videoeditor/temp/" + mediaId);
    if (!tempDir.exists() && !tempDir.mkdirs()) {
      throw new IOException("Failed to create temp directory: " + tempDir.getAbsolutePath());
    }

    List<SubtitleDTO> relevantSubtitles = subtitles.stream()
            .filter(s -> s.getTimelineStartTime() < batchEnd && s.getTimelineEndTime() > batchStart)
            .collect(Collectors.toList());

    // CRITICAL FIX: Keep PNG files local, don't upload/download to R2
    List<File> localPngFiles = new ArrayList<>();
    for (SubtitleDTO subtitle : relevantSubtitles) {
      if (subtitle.getText() == null || subtitle.getText().trim().isEmpty()) {
        logger.warn("Skipping subtitle with empty text for mediaId: {}, id: {}", mediaId, subtitle.getId());
        continue;
      }
      if (subtitle.getTimelineEndTime() <= subtitle.getTimelineStartTime()) {
        logger.warn("Skipping subtitle with invalid timing for mediaId: {}, id: {}", mediaId, subtitle.getId());
        continue;
      }

      // Generate PNG locally
      String tempPngPath = generateTextPng(subtitle, tempDir, canvasWidth, canvasHeight);
      File tempPngFile = new File(tempPngPath);

      // Validate PNG file
      try {
        BufferedImage img = ImageIO.read(tempPngFile);
        if (img == null) {
          logger.error("Invalid or corrupted PNG file: {}", tempPngPath);
          throw new IOException("Invalid or corrupted PNG file: " + tempPngPath);
        }
        logger.debug("Validated PNG file: {} ({}x{})", tempPngPath, img.getWidth(), img.getHeight());
        img.flush();
      } catch (IOException e) {
        logger.error("Failed to validate PNG file: {}", tempPngPath, e);
        throw new IOException("Failed to validate PNG file: " + tempPngPath, e);
      }

      // Keep track for cleanup
      localPngFiles.add(tempPngFile);

      // Use local file directly in FFmpeg
      command.add("-loop");
      command.add("1");
      command.add("-i");
      command.add(tempPngPath);
      textInputIndices.put(subtitle.getId(), String.valueOf(inputCount++));
    }

    if (textInputIndices.isEmpty()) {
      logger.warn("No valid subtitle PNGs for batch from {} to {} seconds, copying input segment", batchStart, batchEnd);
      command.clear();
      command.add(ffmpegPath);
      command.add("-ss");
      command.add(String.format("%.6f", batchStart));
      command.add("-t");
      command.add(String.format("%.6f", batchDuration));
      command.add("-i");
      command.add(inputFile.getAbsolutePath());
      command.add("-c");
      command.add("copy");
      command.add("-y");
      command.add(outputFile.getAbsolutePath());
    } else {
      String lastOutput = "0:v";
      int overlayCount = 0;

      for (SubtitleDTO subtitle : relevantSubtitles) {
        String inputIdx = textInputIndices.get(subtitle.getId());
        if (inputIdx == null) {
          continue;
        }

        double segmentStart = Math.max(subtitle.getTimelineStartTime(), batchStart) - batchStart;
        double segmentEnd = Math.min(subtitle.getTimelineEndTime(), batchEnd) - batchStart;
        if (segmentStart >= segmentEnd) {
          logger.warn("Invalid segment timing for subtitle id: {}", subtitle.getId());
          continue;
        }
        String outputLabel = "ov" + overlayCount++;

        filterComplex.append("[").append(inputIdx).append(":v]");
        filterComplex.append("setpts=PTS-STARTPTS+").append(String.format("%.6f", segmentStart)).append("/TB,");

        double defaultScale = subtitle.getScale() != null ? subtitle.getScale() : 1.0;
        double resolutionMultiplier = canvasWidth >= 3840 ? 1.5 : 2.0;
        double baseScale = 1.0 / resolutionMultiplier;

        filterComplex.append("scale=w='iw*").append(String.format("%.6f", defaultScale * baseScale))
                .append("':h='ih*").append(String.format("%.6f", defaultScale * baseScale))
                .append("':flags=lanczos,");

        double opacity = subtitle.getOpacity() != null ? subtitle.getOpacity() : 1.0;
        if (opacity < 1.0) {
          filterComplex.append("colorchannelmixer=aa=").append(String.format("%.6f", opacity)).append(",");
        }

        String xExpr, yExpr;
        if (subtitle.getAlignment() != null && subtitle.getAlignment().equalsIgnoreCase("left")) {
          xExpr = String.format("%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        } else if (subtitle.getAlignment() != null && subtitle.getAlignment().equalsIgnoreCase("right")) {
          xExpr = String.format("W-w-%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        } else {
          xExpr = String.format("(W-w)/2+%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        }
        yExpr = String.format("(H-h)/2+%d", subtitle.getPositionY() != null ? subtitle.getPositionY() : 0);

        filterComplex.append("[").append(lastOutput).append("]");
        filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=auto");
        filterComplex.append(":enable='between(t,").append(String.format("%.6f", segmentStart)).append(",").append(String.format("%.6f", segmentEnd)).append(")'");
        filterComplex.append("[ov").append(outputLabel).append("];");
        lastOutput = "ov" + outputLabel;
      }

      filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout]");

      command.add("-filter_complex");
      command.add(filterComplex.toString());
      command.add("-map");
      command.add("[vout]");
      command.add("-map");
      command.add("0:a?");
      command.add("-c:v");
      command.add("libx264");
      command.add("-preset");
      command.add("medium");
      command.add("-crf");
      command.add("23");
      command.add("-pix_fmt");
      command.add("yuv420p");
      command.add("-c:a");
      command.add("aac");
      command.add("-b:a");
      command.add("192k");
      command.add("-t");
      command.add(String.format("%.6f", batchDuration));
      command.add("-r");
      command.add(String.format("%.2f", fps));
      command.add("-y");
      command.add(outputFile.getAbsolutePath());
    }

    logger.debug("FFmpeg command for batch: {}", String.join(" ", command));
    try {
      executeFFmpegCommand(command, mediaId, batchStart, batchDuration, totalDuration, batchIndex);
    } finally {
      // Clean up local PNG files after FFmpeg completes
      for (File localPngFile : localPngFiles) {
        try {
          Files.deleteIfExists(localPngFile.toPath());
          logger.debug("Deleted local PNG file: {}", localPngFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete local PNG file: {}", localPngFile.getAbsolutePath(), e);
        }
      }
    }
  }

  private void concatenateBatches(List<String> tempVideoFiles, String outputPath, float fps)
          throws IOException, InterruptedException {
    if (tempVideoFiles.isEmpty()) {
      throw new IllegalStateException("No batch files to concatenate");
    }
    if (tempVideoFiles.size() == 1) {
      // Single file, just move it
      Files.move(new File(tempVideoFiles.get(0)).toPath(),
              new File(outputPath).toPath(),
              StandardCopyOption.REPLACE_EXISTING);
      return;
    }

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    File concatListFile = new File(absoluteBaseDir + File.separator + "videoeditor", "concat_list.txt");

    try (PrintWriter writer = new PrintWriter(concatListFile, "UTF-8")) {
      for (String tempFile : tempVideoFiles) {
        // Use absolute paths in concat file
        String absolutePath = new File(tempFile).getAbsolutePath();
        writer.println("file '" + absolutePath.replace("\\", "\\\\") + "'");
      }
    }

    List<String> command = new ArrayList<>();
    command.add(ffmpegPath);
    command.add("-f");
    command.add("concat");
    command.add("-safe");
    command.add("0");
    command.add("-i");
    command.add(concatListFile.getAbsolutePath());
    command.add("-c");
    command.add("copy");
    command.add("-r");
    command.add(String.valueOf(fps));
    command.add("-y");
    command.add(outputPath);

    logger.debug("FFmpeg concatenation command: {}", String.join(" ", command));
    try {
      executeFFmpegCommand(command);
    } finally {
      if (concatListFile.exists()) {
        try {
          Files.delete(concatListFile.toPath());
          logger.debug("Deleted concat list file: {}", concatListFile.getAbsolutePath());
        } catch (IOException e) {
          logger.error("Failed to delete concat list file {}: {}",
                  concatListFile.getAbsolutePath(), e.getMessage());
        }
      }
    }
  }

  private void executeFFmpegCommand(List<String> command, Long mediaId, double batchStart, double batchDuration, double totalDuration, int batchIndex) throws IOException, InterruptedException {
    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

    List<String> updatedCommand = new ArrayList<>(command);
    updatedCommand.add("-progress");
    updatedCommand.add("pipe:");

    ProcessBuilder processBuilder = new ProcessBuilder(updatedCommand);
    processBuilder.redirectErrorStream(false); // Separate stdout and stderr

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    File tempDir = new File(absoluteBaseDir, "videoeditor/logs/" + mediaId);
    if (!tempDir.exists() && !tempDir.mkdirs()) {
      logger.error("Failed to create log directory: {}", tempDir.getAbsolutePath());
      throw new IOException("Failed to create log directory: " + tempDir.getAbsolutePath());
    }

    String commandLogFileName = "ffmpeg_command_" + mediaId + "_" + batchIndex + ".txt";
    String errorLogFileName = "ffmpeg_error_" + mediaId + "_" + batchIndex + ".txt";
    String stdoutLogFileName = "ffmpeg_stdout_" + mediaId + "_" + batchIndex + ".txt";
    File commandLogFile = new File(tempDir, commandLogFileName);
    File errorLogFile = new File(tempDir, errorLogFileName);
    File stdoutLogFile = new File(tempDir, stdoutLogFileName); // Declare outside try block

    // Log the command to file and R2
    try (PrintWriter writer = new PrintWriter(commandLogFile, "UTF-8")) {
      writer.println(String.join(" ", updatedCommand));
    }
    logger.debug("Executing FFmpeg command: {}", String.join(" ", updatedCommand));
    Process process = processBuilder.start();
    StringBuilder stdoutOutput = new StringBuilder();
    StringBuilder stderrOutput = new StringBuilder();
    final double[] lastProgress = {-1.0}; // Use array to allow modification in lambda

    // Read stdout and stderr concurrently
    try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
         BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      Thread stdoutThread = new Thread(() -> {
        try {
          String line;
          while ((line = stdoutReader.readLine()) != null) {
            synchronized (stdoutOutput) {
              stdoutOutput.append(line).append("\n");
              logger.debug("FFmpeg stdout: {}", line);
              if (line.startsWith("out_time_ms=") && !line.equals("out_time_ms=N/A")) {
                try {
                  long outTimeUs = Long.parseLong(line.replace("out_time_ms=", ""));
                  double currentBatchTime = outTimeUs / 1_000_000.0;

                  double batchProgress = Math.min(currentBatchTime / batchDuration, 1.0);
                  double batchContribution = batchDuration / totalDuration * 100.0;
                  double completedBatchesProgress = batchIndex * (batchDuration / totalDuration * 100.0);
                  double totalProgress = completedBatchesProgress + (batchProgress * batchContribution);
                  totalProgress = Math.min(totalProgress, 100.0);

                  int roundedProgress = (int) Math.round(totalProgress);
                  if (roundedProgress != (int) lastProgress[0] && roundedProgress >= 0 && roundedProgress <= 100 && roundedProgress % 10 == 0) {
                    subtitleMedia.setProgress((double) roundedProgress);
                    subtitleMedia.setStatus("PROCESSING");
                    subtitleMediaRepository.save(subtitleMedia);
                    logger.info("Progress updated: {}% for mediaId: {}", roundedProgress, mediaId);
                    lastProgress[0] = roundedProgress; // Update array element
                  }
                } catch (NumberFormatException e) {
                  logger.error("Failed to parse out_time_ms: {}", line);
                }
              }
            }
          }
        } catch (IOException e) {
          logger.error("Error reading FFmpeg stdout: {}", e.getMessage(), e);
        }
      });

      Thread stderrThread = new Thread(() -> {
        try {
          String line;
          while ((line = stderrReader.readLine()) != null) {
            synchronized (stderrOutput) {
              stderrOutput.append(line).append("\n");
              logger.error("FFmpeg stderr: {}", line);
            }
          }
        } catch (IOException e) {
          logger.error("Error reading FFmpeg stderr: {}", e.getMessage(), e);
        }
      });

      stdoutThread.start();
      stderrThread.start();
      stdoutThread.join();
      stderrThread.join();

      // Write outputs to files and upload to R2
      try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
        writer.println(stderrOutput.toString());
      }

      try (PrintWriter writer = new PrintWriter(stdoutLogFile, "UTF-8")) {
        writer.println(stdoutOutput.toString());
      }
    }

    boolean completed = process.waitFor(10, TimeUnit.MINUTES);
    if (!completed) {
      process.destroyForcibly();
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      throw new RuntimeException("FFmpeg process timed out after 10 minutes for mediaId: " + mediaId + ". Error log uploaded to: ");
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode + " for mediaId: " + mediaId + ". Error log uploaded to: ");
    }

    // Clean up local log files
    Files.deleteIfExists(commandLogFile.toPath());
    Files.deleteIfExists(errorLogFile.toPath());
    Files.deleteIfExists(stdoutLogFile.toPath());
  }

  private void executeFFmpegCommand(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(false);

    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
    File tempDir = new File(absoluteBaseDir, "videoeditor/logs");
    if (!tempDir.exists() && !tempDir.mkdirs()) {
      logger.error("Failed to create log directory: {}", tempDir.getAbsolutePath());
      throw new IOException("Failed to create log directory: " + tempDir.getAbsolutePath());
    }

    String commandLogFileName = "ffmpeg_concat_command.txt";
    String errorLogFileName = "ffmpeg_concat_error.txt";
    String stdoutLogFileName = "ffmpeg_concat_stdout.txt";
    File commandLogFile = new File(tempDir, commandLogFileName);
    File errorLogFile = new File(tempDir, errorLogFileName);
    File stdoutLogFile = new File(tempDir, stdoutLogFileName); // Declare outside try block

    // Log the command to file and R2
    try (PrintWriter writer = new PrintWriter(commandLogFile, "UTF-8")) {
      writer.println(String.join(" ", command));
    }
    logger.debug("Executing FFmpeg command: {}", String.join(" ", command));
    Process process = processBuilder.start();
    StringBuilder stdoutOutput = new StringBuilder();
    StringBuilder stderrOutput = new StringBuilder();

    // Read stdout and stderr concurrently
    try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
         BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      Thread stdoutThread = new Thread(() -> {
        try {
          String line;
          while ((line = stdoutReader.readLine()) != null) {
            synchronized (stdoutOutput) {
              stdoutOutput.append(line).append("\n");
              logger.debug("FFmpeg concat stdout: {}", line);
            }
          }
        } catch (IOException e) {
          logger.error("Error reading FFmpeg concat stdout: {}", e.getMessage(), e);
        }
      });

      Thread stderrThread = new Thread(() -> {
        try {
          String line;
          while ((line = stderrReader.readLine()) != null) {
            synchronized (stderrOutput) {
              stderrOutput.append(line).append("\n");
              logger.error("FFmpeg concat stderr: {}", line);
            }
          }
        } catch (IOException e) {
          logger.error("Error reading FFmpeg concat stderr: {}", e.getMessage(), e);
        }
      });

      stdoutThread.start();
      stderrThread.start();
      stdoutThread.join();
      stderrThread.join();

      // Write outputs to files and upload to R2
      try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
        writer.println(stderrOutput.toString());
      }
      try (PrintWriter writer = new PrintWriter(stdoutLogFile, "UTF-8")) {
        writer.println(stdoutOutput.toString());
      }
    }

    boolean completed = process.waitFor(5, TimeUnit.MINUTES);
    if (!completed) {
      process.destroyForcibly();
      throw new RuntimeException("FFmpeg concat process timed out after 5 minutes. Error log uploaded to: ");
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
      throw new RuntimeException("FFmpeg concat process failed with exit code: " + exitCode + ". Error log uploaded to: ");
    }

    // Clean up local log files
    Files.deleteIfExists(commandLogFile.toPath());
    Files.deleteIfExists(errorLogFile.toPath());
    Files.deleteIfExists(stdoutLogFile.toPath());
  }

  public String generateTextPng(SubtitleDTO ts, File tempDir, int canvasWidth, int canvasHeight) throws IOException {
    final double RESOLUTION_MULTIPLIER = canvasWidth >= 3840 ? 1.5 : 2.0;
    final double BORDER_SCALE_FACTOR = canvasWidth >= 3840 ? 1.5 : 2.0;

    double defaultScale = ts.getScale() != null ? ts.getScale() : 1.0;
    List<Keyframe> scaleKeyframes = ts.getKeyframes().getOrDefault("scale", new ArrayList<>());
    double maxScale = defaultScale;
    if (!scaleKeyframes.isEmpty()) {
      maxScale = Math.max(
              defaultScale,
              scaleKeyframes.stream()
                      .mapToDouble(kf -> ((Number) kf.getValue()).doubleValue())
                      .max()
                      .orElse(defaultScale)
      );
    }

    Color fontColor = parseColor(ts.getFontColor(), Color.WHITE, "font", ts.getId());
    Color bgColor = ts.getBackgroundColor() != null && !ts.getBackgroundColor().equals("transparent") ?
            parseColor(ts.getBackgroundColor(), null, "background", ts.getId()) : null;
    Color bgBorderColor = ts.getBackgroundBorderColor() != null && !ts.getBackgroundBorderColor().equals("transparent") ?
            parseColor(ts.getBackgroundBorderColor(), null, "border", ts.getId()) : null;
    Color textBorderColor = ts.getTextBorderColor() != null && !ts.getTextBorderColor().equals("transparent") ?
            parseColor(ts.getTextBorderColor(), null, "text border", ts.getId()) : null;

    double baseFontSize = 24.0 * maxScale * RESOLUTION_MULTIPLIER;
    Font font;
    try {
      font = Font.createFont(Font.TRUETYPE_FONT, new File(getFontPathByFamily(ts.getFontFamily())))
              .deriveFont((float) baseFontSize);
    } catch (Exception e) {
      logger.error("Failed to load font for subtitle {}: {}, using Arial", ts.getId(), ts.getFontFamily(), e);
      font = new Font("Arial", Font.PLAIN, (int) baseFontSize);
    }

    double letterSpacing = ts.getLetterSpacing() != null ? ts.getLetterSpacing() : 0.0;
    double scaledLetterSpacing = letterSpacing * maxScale * RESOLUTION_MULTIPLIER;
    double lineSpacing = ts.getLineSpacing() != null ? ts.getLineSpacing() : 1.2;
    double scaledLineSpacing = lineSpacing * baseFontSize;

    BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = tempImage.createGraphics();
    g2d.setFont(font);
    FontMetrics fm = g2d.getFontMetrics();
    String[] lines = ts.getText().split("\n");
    int lineHeight = (int) scaledLineSpacing;
    int totalTextHeight = lines.length > 1 ? (lines.length - 1) * lineHeight + fm.getAscent() + fm.getDescent() : fm.getAscent() + fm.getDescent();
    int maxTextWidth = 0;
    for (String line : lines) {
      int lineWidth = 0;
      for (int i = 0; i < line.length(); i++) {
        lineWidth += fm.charWidth(line.charAt(i));
        if (i < line.length() - 1) {
          lineWidth += (int) scaledLetterSpacing;
        }
      }
      maxTextWidth = Math.max(maxTextWidth, lineWidth);
    }
    int textBlockHeight = totalTextHeight;
    g2d.dispose();
    tempImage.flush();

    int bgHeight = (int) ((ts.getBackgroundH() != null ? ts.getBackgroundH() : 0) * maxScale * RESOLUTION_MULTIPLIER);
    int bgWidth = (int) ((ts.getBackgroundW() != null ? ts.getBackgroundW() : 0) * maxScale * RESOLUTION_MULTIPLIER);
    int bgBorderWidth = (int) ((ts.getBackgroundBorderWidth() != null ? ts.getBackgroundBorderWidth() : 0) * maxScale * BORDER_SCALE_FACTOR);
    int borderRadius = (int) ((ts.getBackgroundBorderRadius() != null ? ts.getBackgroundBorderRadius() : 0) * maxScale * RESOLUTION_MULTIPLIER);
    int textBorderWidth = (int) ((ts.getTextBorderWidth() != null ? ts.getTextBorderWidth() : 0) * maxScale * BORDER_SCALE_FACTOR);

    int contentWidth = maxTextWidth + bgWidth + 2 * textBorderWidth;
    int contentHeight = textBlockHeight + bgHeight + 2 * textBorderWidth;

    int maxDimension = (int) (Math.max(canvasWidth, canvasHeight) * RESOLUTION_MULTIPLIER * 1.5);
    double scaleDown = 1.0;
    if (contentWidth + 2 * bgBorderWidth + 2 * textBorderWidth > maxDimension ||
            contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth > maxDimension) {
      scaleDown = Math.min(
              maxDimension / (double) (contentWidth + 2 * bgBorderWidth + 2 * textBorderWidth),
              maxDimension / (double) (contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth)
      );
      scaleDown = Math.max(scaleDown, 0.5);
      bgWidth = (int) (bgWidth * scaleDown);
      bgHeight = (int) (bgHeight * scaleDown);
      bgBorderWidth = (int) (bgBorderWidth * scaleDown);
      borderRadius = (int) (borderRadius * scaleDown);
      textBorderWidth = (int) (textBorderWidth * scaleDown);
      contentWidth = maxTextWidth + bgWidth + 2 * textBorderWidth;
      contentHeight = textBlockHeight + bgHeight + 2 * textBorderWidth;
    }

    int totalWidth = contentWidth + 2 * bgBorderWidth + 2 * textBorderWidth;
    totalWidth = (totalWidth % 2 != 0) ? totalWidth + 1 : totalWidth;
    int totalHeight = contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth;
    totalHeight = (totalHeight % 2 != 0) ? totalHeight + 1 : totalHeight;

    BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
    g2d = image.createGraphics();
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g2d.setFont(font);
    fm = g2d.getFontMetrics();

    if (bgColor != null) {
      float bgOpacity = ts.getBackgroundOpacity() != null ? ts.getBackgroundOpacity().floatValue() : 1.0f;
      g2d.setColor(new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), (int) (bgOpacity * 255)));
      if (borderRadius > 0) {
        g2d.fillRoundRect(
                bgBorderWidth + textBorderWidth,
                bgBorderWidth + textBorderWidth,
                contentWidth,
                contentHeight,
                borderRadius,
                borderRadius
        );
      } else {
        g2d.fillRect(
                bgBorderWidth + textBorderWidth,
                bgBorderWidth + textBorderWidth,
                contentWidth,
                contentHeight
        );
      }
    }

    if (bgBorderColor != null && bgBorderWidth > 0) {
      g2d.setColor(bgBorderColor);
      g2d.setStroke(new BasicStroke((float) bgBorderWidth));
      if (borderRadius > 0) {
        g2d.drawRoundRect(
                bgBorderWidth / 2 + textBorderWidth,
                bgBorderWidth / 2 + textBorderWidth,
                contentWidth + bgBorderWidth,
                contentHeight + bgBorderWidth,
                borderRadius + bgBorderWidth,
                borderRadius + bgBorderWidth
        );
      } else {
        g2d.drawRect(
                bgBorderWidth / 2 + textBorderWidth,
                bgBorderWidth / 2 + textBorderWidth,
                contentWidth + bgBorderWidth,
                contentHeight + bgBorderWidth
        );
      }
    }

    String alignment = ts.getAlignment() != null ? ts.getAlignment().toLowerCase() : "center";
    int textYStart = bgBorderWidth + textBorderWidth + (contentHeight - textBlockHeight) / 2 + fm.getAscent();
    int y = textYStart;
    FontRenderContext frc = g2d.getFontRenderContext();

    for (String line : lines) {
      int lineWidth = 0;
      for (int i = 0; i < line.length(); i++) {
        lineWidth += fm.charWidth(line.charAt(i));
        if (i < line.length() - 1) {
          lineWidth += (int) scaledLetterSpacing;
        }
      }

      int x;
      if (alignment.equals("left")) {
        x = bgBorderWidth + textBorderWidth;
      } else if (alignment.equals("center")) {
        x = bgBorderWidth + textBorderWidth + (contentWidth - lineWidth) / 2;
      } else {
        x = bgBorderWidth + textBorderWidth + contentWidth - lineWidth;
      }

      if (textBorderColor != null && textBorderWidth > 0) {
        float textBorderOpacity = ts.getTextBorderOpacity() != null ? ts.getTextBorderOpacity().floatValue() : 1.0f;
        g2d.setColor(new Color(textBorderColor.getRed(), textBorderColor.getGreen(), textBorderColor.getBlue(), (int) (textBorderOpacity * 255)));
        g2d.setStroke(new BasicStroke((float) textBorderWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Area combinedArea = new Area();
        int currentX = x;
        for (int i = 0; i < line.length(); i++) {
          char c = line.charAt(i);
          TextLayout charLayout = new TextLayout(String.valueOf(c), font, frc);
          Shape charShape = charLayout.getOutline(AffineTransform.getTranslateInstance(currentX, y));
          combinedArea.add(new Area(charShape));
          currentX += fm.charWidth(c) + (int) scaledLetterSpacing;
        }
        g2d.draw(combinedArea);
      }

      g2d.setColor(fontColor);
      int currentX = x;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        g2d.drawString(String.valueOf(c), currentX, y);
        currentX += fm.charWidth(c) + (int) scaledLetterSpacing;
      }
      y += lineHeight;
    }

    g2d.dispose();

    String tempPngPath = new File(tempDir, "text_" + ts.getId() + ".png").getAbsolutePath();
    File outputFile = new File(tempPngPath);
    ImageIO.write(image, "PNG", outputFile);

    logger.info("Generated PNG with even dimensions: {}x{} for subtitle {}", totalWidth, totalHeight, ts.getId());
    return tempPngPath;
  }

  private Color parseColor(String colorStr, Color fallback, String type, String segmentId) {
    try {
      return Color.decode(colorStr);
    } catch (NumberFormatException e) {
      logger.warn("Invalid {} color for segment {}: {}, using {}", type, segmentId, colorStr, fallback != null ? "fallback" : "none");
      return fallback;
    }
  }

  private String getFontPathByFamily(String fontFamily) {
    final String FONTS_RESOURCE_PATH = "/fonts/";
    // Use system-specific temp directory with proper separators
    final String TEMP_FONT_DIR = System.getProperty("java.io.tmpdir") +
            File.separator + "scenith-fonts" + File.separator;

    // Create temp directory for fonts if it doesn't exist
    File tempDir = new File(TEMP_FONT_DIR);
    if (!tempDir.exists()) {
      tempDir.mkdirs();
    }

    // Default font path (fallback)
    String defaultFontPath = getFontFilePath("arial.ttf", FONTS_RESOURCE_PATH, TEMP_FONT_DIR);

    if (fontFamily == null || fontFamily.trim().isEmpty()) {
      System.out.println("Font family is null or empty. Using default font: arial.ttf");
      return defaultFontPath;
    }

    // Map font families to filenames in resources/fonts
    Map<String, String> fontMap = new HashMap<>();
    fontMap.put("Arial", "arial.ttf");
    fontMap.put("Times New Roman", "times.ttf");
    fontMap.put("Courier New", "cour.ttf");
    fontMap.put("Calibri", "calibri.ttf");
    fontMap.put("Verdana", "verdana.ttf");
    fontMap.put("Georgia", "georgia.ttf");
    fontMap.put("Comic Sans MS", "comic.ttf");
    fontMap.put("Impact", "impact.ttf");
    fontMap.put("Tahoma", "tahoma.ttf");

    // Arial variants
    fontMap.put("Arial Bold", "arialbd.ttf");
    fontMap.put("Arial Italic", "ariali.ttf");
    fontMap.put("Arial Bold Italic", "arialbi.ttf");
    fontMap.put("Arial Black", "ariblk.ttf");

    // Georgia variants
    fontMap.put("Georgia Bold", "georgiab.ttf");
    fontMap.put("Georgia Italic", "georgiai.ttf");
    fontMap.put("Georgia Bold Italic", "georgiaz.ttf");

    // Times New Roman variants
    fontMap.put("Times New Roman Bold", "timesbd.ttf");
    fontMap.put("Times New Roman Italic", "timesi.ttf");
    fontMap.put("Times New Roman Bold Italic", "timesbi.ttf");

    // Alumni Sans Pinstripe
    fontMap.put("Alumni Sans Pinstripe", "AlumniSansPinstripe-Regular.ttf");

    // Lexend Giga variants
    fontMap.put("Lexend Giga", "LexendGiga-Regular.ttf");
    fontMap.put("Lexend Giga Black", "LexendGiga-Black.ttf");
    fontMap.put("Lexend Giga Bold", "LexendGiga-Bold.ttf");


    // Montserrat Alternates variants
    fontMap.put("Montserrat Alternates", "MontserratAlternates-ExtraLight.ttf");
    fontMap.put("Montserrat Alternates Black", "MontserratAlternates-Black.ttf");
    fontMap.put("Montserrat Alternates Medium Italic", "MontserratAlternates-MediumItalic.ttf");

    // Noto Sans Mono variants
    fontMap.put("Noto Sans Mono", "NotoSansMono-Regular.ttf");
    fontMap.put("Noto Sans Mono Bold", "NotoSansMono-Bold.ttf");


    // Poiret One
    fontMap.put("Poiret One", "PoiretOne-Regular.ttf");

    // Arimo variants
    fontMap.put("Arimo", "Arimo-Regular.ttf");
    fontMap.put("Arimo Bold", "Arimo-Bold.ttf");
    fontMap.put("Arimo Bold Italic", "Arimo-BoldItalic.ttf");
    fontMap.put("Arimo Italic", "Arimo-Italic.ttf");


    // Carlito variants
    fontMap.put("Carlito", "Carlito-Regular.ttf");
    fontMap.put("Carlito Bold", "Carlito-Bold.ttf");
    fontMap.put("Carlito Bold Italic", "Carlito-BoldItalic.ttf");
    fontMap.put("Carlito Italic", "Carlito-Italic.ttf");

    // Comic Neue variants
    fontMap.put("Comic Neue", "ComicNeue-Regular.ttf");
    fontMap.put("Comic Neue Bold", "ComicNeue-Bold.ttf");
    fontMap.put("Comic Neue Bold Italic", "ComicNeue-BoldItalic.ttf");
    fontMap.put("Comic Neue Italic", "ComicNeue-Italic.ttf");


    // Courier Prime variants
    fontMap.put("Courier Prime", "CourierPrime-Regular.ttf");
    fontMap.put("Courier Prime Bold", "CourierPrime-Bold.ttf");
    fontMap.put("Courier Prime Bold Italic", "CourierPrime-BoldItalic.ttf");
    fontMap.put("Courier Prime Italic", "CourierPrime-Italic.ttf");

    // Gelasio variants
    fontMap.put("Gelasio", "Gelasio-Regular.ttf");
    fontMap.put("Gelasio Bold", "Gelasio-Bold.ttf");
    fontMap.put("Gelasio Bold Italic", "Gelasio-BoldItalic.ttf");
    fontMap.put("Gelasio Italic", "Gelasio-Italic.ttf");


    // Tinos variants
    fontMap.put("Tinos", "Tinos-Regular.ttf");
    fontMap.put("Tinos Bold", "Tinos-Bold.ttf");
    fontMap.put("Tinos Bold Italic", "Tinos-BoldItalic.ttf");
    fontMap.put("Tinos Italic", "Tinos-Italic.ttf");

    // Amatic SC variants
    fontMap.put("Amatic SC", "AmaticSC-Regular.ttf");
    fontMap.put("Amatic SC Bold", "AmaticSC-Bold.ttf");

// Barriecito
    fontMap.put("Barriecito", "Barriecito-Regular.ttf");

// Barrio
    fontMap.put("Barrio", "Barrio-Regular.ttf");

// Birthstone
    fontMap.put("Birthstone", "Birthstone-Regular.ttf");

// Bungee Hairline
    fontMap.put("Bungee Hairline", "BungeeHairline-Regular.ttf");

// Butcherman
    fontMap.put("Butcherman", "Butcherman-Regular.ttf");

// Doto variants
    fontMap.put("Doto Black", "Doto-Black.ttf");
    fontMap.put("Doto ExtraBold", "Doto-ExtraBold.ttf");
    fontMap.put("Doto Rounded Bold", "Doto_Rounded-Bold.ttf");

// Fascinate Inline
    fontMap.put("Fascinate Inline", "FascinateInline-Regular.ttf");

// Freckle Face
    fontMap.put("Freckle Face", "FreckleFace-Regular.ttf");

// Fredericka the Great
    fontMap.put("Fredericka the Great", "FrederickatheGreat-Regular.ttf");

// Imperial Script
    fontMap.put("Imperial Script", "ImperialScript-Regular.ttf");

// Kings
    fontMap.put("Kings", "Kings-Regular.ttf");

// Kirang Haerang
    fontMap.put("Kirang Haerang", "KirangHaerang-Regular.ttf");

// Lavishly Yours
    fontMap.put("Lavishly Yours", "LavishlyYours-Regular.ttf");

// Mountains of Christmas variants
    fontMap.put("Mountains of Christmas", "MountainsofChristmas-Regular.ttf");
    fontMap.put("Mountains of Christmas Bold", "MountainsofChristmas-Bold.ttf");

// Rampart One
    fontMap.put("Rampart One", "RampartOne-Regular.ttf");

// Rubik Wet Paint
    fontMap.put("Rubik Wet Paint", "RubikWetPaint-Regular.ttf");

// Tangerine variants
    fontMap.put("Tangerine", "Tangerine-Regular.ttf");
    fontMap.put("Tangerine Bold", "Tangerine-Bold.ttf");

// Yesteryear
    fontMap.put("Yesteryear", "Yesteryear-Regular.ttf");

    // Process the font family name
    String processedFontFamily = fontFamily.trim();

    // Try direct match
    if (fontMap.containsKey(processedFontFamily)) {
      String fontFileName = fontMap.get(processedFontFamily);
      String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
      System.out.println("Found exact font match for: " + processedFontFamily + " -> " + fontPath);
      return fontPath;
    }

    // Try case-insensitive match
    for (Map.Entry<String, String> entry : fontMap.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
        String fontFileName = entry.getValue();
        String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
        System.out.println("Found case-insensitive font match for: " + processedFontFamily + " -> " + fontPath);
        return fontPath;
      }
    }

    // Fallback to default font
    System.out.println("Warning: Font family '" + fontFamily + "' not found in font map. Using default: arial.ttf");
    return defaultFontPath;
  }

  private String getFontFilePath(String fontFileName, String fontsResourcePath, String tempFontDir) {
    try {
      // Check if font is already extracted in temp directory
      File tempFontFile = new File(tempFontDir + fontFileName);
      if (tempFontFile.exists()) {
        return tempFontFile.getAbsolutePath();
      }

      // Load font from classpath
      String resourcePath = fontsResourcePath + fontFileName;
      InputStream fontStream = getClass().getResourceAsStream(resourcePath);

      if (fontStream == null) {
        System.err.println("Font file not found in resources: " + resourcePath);
        throw new IOException("Font file not found: " + fontFileName);
      }

      // Copy font to temp directory
      Path tempPath = tempFontFile.toPath();
      Files.copy(fontStream, tempPath, StandardCopyOption.REPLACE_EXISTING);
      fontStream.close();

      System.out.println("Extracted font to: " + tempFontFile.getAbsolutePath());
      return tempFontFile.getAbsolutePath();

    } catch (IOException e) {
      System.err.println("Error accessing font file: " + fontFileName + ". Error: " + e.getMessage());

      // Try to find any available default font in temp directory
      String[] fallbackFonts = {"arial.ttf", "Arimo-Regular.ttf", "ComicNeue-Regular.ttf"};

      for (String fallbackFont : fallbackFonts) {
        File defaultFont = new File(tempFontDir + fallbackFont);
        if (defaultFont.exists()) {
          System.out.println("Using fallback font: " + defaultFont.getAbsolutePath());
          return defaultFont.getAbsolutePath();
        }
      }

      // Cross-platform system font fallbacks
      String os = System.getProperty("os.name").toLowerCase();
      if (os.contains("win")) {
        return "C:/Windows/Fonts/Arial.ttf";
      } else if (os.contains("mac")) {
        return "/System/Library/Fonts/Arial.ttf";
      } else {
        // Linux/Unix
        return "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
      }
    }
  }

  private double getVideoDuration(File videoFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath.replace("ffmpeg", "ffprobe"),
            "-i", videoFile.getAbsolutePath(),
            "-show_entries", "format=duration",
            "-v", "quiet",
            "-of", "json"
    );

    logger.debug("Executing FFprobe command for video duration: {}", String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
        logger.debug("FFprobe output: {}", line);
      }
    }
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      logger.error("FFprobe failed to get video duration for {}: {}", videoFile.getAbsolutePath(), output.toString());
      throw new IOException("FFprobe failed to get video duration: " + output.toString());
    }

    try {
      Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
      Map<String, Object> format = (Map<String, Object>) result.get("format");
      if (format == null || !format.containsKey("duration")) {
        logger.error("No duration found in FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
        throw new IOException("No duration found in FFprobe output");
      }
      return Double.parseDouble(format.get("duration").toString());
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
      throw new IOException("Failed to parse FFprobe output: " + output.toString());
    }
  }
}