package com.example.Scenith.service;

import com.example.Scenith.dto.Keyframe;
import com.example.Scenith.dto.SubtitleDTO;
import com.example.Scenith.entity.SubtitleMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserProcessingUsage;
import com.example.Scenith.repository.SubtitleMediaRepository;
import com.example.Scenith.repository.UserProcessingUsageRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
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
  private final ObjectMapper objectMapper;
  private final UserRepository userRepository;
  private final UserProcessingUsageRepository userProcessingUsageRepository;
  private final PlanLimitsService planLimitsService;
  private final CloudflareR2Service cloudflareR2Service;
  private final ProcessingEmailHelper emailHelper;
  private final software.amazon.awssdk.services.sqs.SqsClient sqsClient;

  // ── Paths from application-prod.properties / environment ──────────────────
  @Value("${app.base-dir:/mnt/scenith-temp}")
  private String baseDir;

  @Value("${python.path:/usr/local/bin/python3.11}")
  private String pythonPath;

  @Value("${whisper.script.path:/app/scripts/whisper_subtitle.py}")
  private String subtitleScriptPath;

  @Value("${app.ffmpeg-path:/usr/local/bin/ffmpeg}")
  private String ffmpegPath;

  @Value("${sqs.queue.url}")
  private String sqsQueueUrl;
  // ──────────────────────────────────────────────────────────────────────────

  public SubtitleService(
          JwtUtil jwtUtil,
          SubtitleMediaRepository subtitleMediaRepository,
          ObjectMapper objectMapper,
          UserRepository userRepository,
          UserProcessingUsageRepository userProcessingUsageRepository,
          PlanLimitsService planLimitsService,
          CloudflareR2Service cloudflareR2Service,
          ProcessingEmailHelper emailHelper, software.amazon.awssdk.services.sqs.SqsClient sqsClient) {
    this.jwtUtil = jwtUtil;
    this.subtitleMediaRepository = subtitleMediaRepository;
    this.objectMapper = objectMapper;
    this.userRepository = userRepository;
    this.userProcessingUsageRepository = userProcessingUsageRepository;
    this.planLimitsService = planLimitsService;
    this.cloudflareR2Service = cloudflareR2Service;
    this.emailHelper = emailHelper;
      this.sqsClient = sqsClient;
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  UPLOAD  – save to R2 (mirrors VideoSpeedService.uploadVideo)
  // ══════════════════════════════════════════════════════════════════════════

  public SubtitleMedia uploadMedia(User user, MultipartFile mediaFile) throws IOException {
    logger.info("Uploading media for user: {}", user.getId());

    if (mediaFile == null || mediaFile.isEmpty()) {
      throw new IllegalArgumentException("Media file is null or empty");
    }

    // ── Save to a temp file so ffprobe can measure duration ───────────────
    Path tempDir = Paths.get(baseDir).resolve("temp/subtitle-upload").toAbsolutePath().normalize();
    Files.createDirectories(tempDir);

    String tempFileName = System.currentTimeMillis() + "_" + sanitizeFilename(mediaFile.getOriginalFilename());
    Path tempFilePath = tempDir.resolve(tempFileName);

    try {
      mediaFile.transferTo(tempFilePath.toFile());

      if (!Files.exists(tempFilePath) || Files.size(tempFilePath) == 0) {
        throw new IOException("Temp file is empty after transfer");
      }

      // ── Validate duration before anything else ─────────────────────────
      double videoDuration;
      try {
        videoDuration = getVideoDuration(tempFilePath.toFile());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Failed to validate video duration: " + e.getMessage());
      }

      int maxMinutes = planLimitsService.getMaxVideoLengthMinutes(user);
      if (maxMinutes > 0 && videoDuration > maxMinutes * 60) {
        throw new IllegalArgumentException(
                "Video length (" + (int) (videoDuration / 60) + " min) exceeds maximum allowed ("
                        + maxMinutes + " minutes). Upgrade your plan.");
      }

      // ── Upload to R2 ────────────────────────────────────────────────────
      String originalFileName = System.currentTimeMillis() + "_" + sanitizeFilename(mediaFile.getOriginalFilename());
      String r2Path = "subtitles/" + user.getId() + "/original/" + originalFileName;
      cloudflareR2Service.uploadFile(tempFilePath.toFile(), r2Path);

      // ── Persist metadata ───────────────────────────────────────────────
      SubtitleMedia subtitleMedia = new SubtitleMedia();
      subtitleMedia.setUser(user);
      subtitleMedia.setOriginalFileName(originalFileName);
      subtitleMedia.setOriginalPath(r2Path);
      // CDN URL via Cloudflare public access
      subtitleMedia.setOriginalCdnUrl(cloudflareR2Service.generateDownloadUrl(r2Path, 0));
      subtitleMedia.setStatus("UPLOADED");
      subtitleMediaRepository.save(subtitleMedia);

      logger.info("Uploaded media to R2 for user: {}, path: {}", user.getId(), r2Path);
      return subtitleMedia;

    } finally {
      try { Files.deleteIfExists(tempFilePath); } catch (IOException ignored) {}
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  GENERATE SUBTITLES  – download from R2, run Whisper, store JSON
  // ══════════════════════════════════════════════════════════════════════════

  public SubtitleMedia generateSubtitles(User user, Long mediaId, Map<String, String> styleParams)
          throws IOException, InterruptedException {
    logger.info("Generating subtitles for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found"));

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized to generate subtitles for this media");
    }

    subtitleMedia.setStatus("PROCESSING");
    subtitleMediaRepository.save(subtitleMedia);

    // ── Download original from R2 to a local temp file ────────────────────
    Path tempDir = Paths.get(baseDir).resolve("temp/subtitle-generate/" + mediaId).toAbsolutePath().normalize();
    Files.createDirectories(tempDir);

    Path tempInputPath = tempDir.resolve("input_" + System.currentTimeMillis() + getExtension(subtitleMedia.getOriginalFileName()));
    File audioFile = null;

    try {
      logger.info("Downloading original file from R2: {}", subtitleMedia.getOriginalPath());
      cloudflareR2Service.downloadFile(subtitleMedia.getOriginalPath(), tempInputPath.toString());

      if (!Files.exists(tempInputPath) || Files.size(tempInputPath) == 0) {
        throw new IOException("Downloaded input file is empty: " + tempInputPath);
      }

      // ── Extract audio ─────────────────────────────────────────────────
      String audioFilePath = extractAudio(tempInputPath.toFile(), mediaId);
      audioFile = new File(audioFilePath);

      double audioDuration = getAudioDuration(audioFile);
      if (audioDuration <= 0) {
        throw new IOException("Audio file has invalid duration: " + audioDuration);
      }

      // ── Whisper transcription ─────────────────────────────────────────
      List<Map<String, Object>> rawSubtitles = runWhisperScript(audioFile);
      if (rawSubtitles.isEmpty()) {
        throw new IOException("No subtitles generated by Whisper");
      }

      // ── Build SubtitleDTO list ────────────────────────────────────────
      List<SubtitleDTO> subtitles = new ArrayList<>();
      for (Map<String, Object> raw : rawSubtitles) {
        double startTime = Math.max(0, ((Number) raw.get("start")).doubleValue());
        double endTime = Math.min(audioDuration, ((Number) raw.get("end")).doubleValue());
        String text = (String) raw.get("text");

        if (endTime <= startTime || text == null || text.trim().isEmpty()) continue;

        SubtitleDTO subtitle = new SubtitleDTO();
        subtitle.setId(UUID.randomUUID().toString());
        subtitle.setTimelineStartTime(startTime);
        subtitle.setTimelineEndTime(endTime);
        subtitle.setText(text.trim());

        // Word-level timestamps
        if (raw.containsKey("words")) {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> wordsRaw = (List<Map<String, Object>>) raw.get("words");
          List<SubtitleDTO.WordTimestamp> wordTimestamps = new ArrayList<>();
          for (Map<String, Object> wordData : wordsRaw) {
            wordTimestamps.add(new SubtitleDTO.WordTimestamp(
                    (String) wordData.get("word"),
                    ((Number) wordData.get("start")).doubleValue(),
                    ((Number) wordData.get("end")).doubleValue()
            ));
          }
          subtitle.setWords(wordTimestamps);
        }

        // Styles
        subtitle.setFontFamily(styleParams != null && styleParams.containsKey("fontFamily")
                ? styleParams.get("fontFamily") : "Montserrat Alternates Black");
        subtitle.setFontColor(styleParams != null && styleParams.containsKey("fontColor")
                ? styleParams.get("fontColor") : "black");
        subtitle.setBackgroundColor(styleParams != null && styleParams.containsKey("backgroundColor")
                ? styleParams.get("backgroundColor") : "white");
        subtitle.setBackgroundOpacity(1.0);
        subtitle.setPositionX(0);
        subtitle.setPositionY(350);
        subtitle.setAlignment("center");
        subtitle.setScale(1.5);
        subtitle.setBackgroundH(50);
        subtitle.setBackgroundW(50);
        subtitle.setBackgroundBorderRadius(15);

        subtitles.add(subtitle);
      }

      subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
      subtitleMedia.setStatus("SUCCESS");
      subtitleMediaRepository.save(subtitleMedia);

      logger.info("Successfully generated {} subtitles for mediaId: {}", subtitles.size(), mediaId);
      return subtitleMedia;

    } catch (Exception e) {
      logger.error("Exception during subtitle generation for mediaId {}: {}", mediaId, e.getMessage(), e);
      subtitleMedia.setStatus("FAILED");
      subtitleMediaRepository.save(subtitleMedia);

      if (e instanceof IOException) throw (IOException) e;
      if (e instanceof InterruptedException) throw (InterruptedException) e;
      throw new IOException("Subtitle generation failed: " + e.getMessage(), e);

    } finally {
      if (audioFile != null && audioFile.exists()) {
        try { Files.delete(audioFile.toPath()); } catch (IOException ignored) {}
      }
      cleanUpTempDir(tempDir);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PROCESS SUBTITLES  – called by SQS worker (queued by controller)
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Called by the controller – validates, marks QUEUED, then pushes to SQS.
   * Mirrors VideoSpeedService.queueVideoProcessing().
   */
  public SubtitleMedia queueProcessSubtitles(User user, Long mediaId, String quality) throws IOException {
    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized to process this media");
    }

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      throw new IllegalStateException("No subtitles available. Generate subtitles first.");
    }

    if ("PROCESSING".equals(subtitleMedia.getStatus()) || "QUEUED".equals(subtitleMedia.getStatus())) {
      throw new IllegalStateException("Media is already being processed.");
    }

    String finalQuality = quality != null ? quality : "720p";

    subtitleMedia.setStatus("QUEUED");
    subtitleMedia.setProgress(0.0);
    subtitleMedia.setQuality(finalQuality);
    subtitleMediaRepository.save(subtitleMedia);

    // Push to SQS – UnifiedTaskWorker will call processSubtitlesTask()
    Map<String, String> taskPayload = new HashMap<>();
    taskPayload.put("taskType",  "PROCESS_SUBTITLES");
    taskPayload.put("mediaId",   String.valueOf(mediaId));
    taskPayload.put("userId",    String.valueOf(user.getId()));
    taskPayload.put("quality",   finalQuality);

    try {
      String messageBody = objectMapper.writeValueAsString(taskPayload);
      sqsClient.sendMessage(b -> b
              .queueUrl(sqsQueueUrl)
              .messageBody(messageBody)
              .build());
      logger.info("Queued PROCESS_SUBTITLES task for mediaId: {}, userId: {}", mediaId, user.getId());
    } catch (Exception e) {
      // Roll back status so user can retry
      subtitleMedia.setStatus("FAILED");
      subtitleMediaRepository.save(subtitleMedia);
      throw new IOException("Failed to queue subtitle processing: " + e.getMessage(), e);
    }

    return subtitleMedia;
  }

  /**
   * Entry point called from UnifiedTaskWorker for PROCESS_SUBTITLES tasks.
   */
  public void processSubtitlesTask(Long mediaId, Long userId, String quality)
          throws IOException, InterruptedException {

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));

    User user = subtitleMedia.getUser();
    if (!user.getId().equals(userId)) {
      throw new IllegalArgumentException("User mismatch for mediaId: " + mediaId);
    }

    processSubtitles(user, mediaId, quality);
  }

  /**
   * Core processing – download from R2, render subtitled video, upload result to R2.
   */
  public SubtitleMedia processSubtitles(User user, Long mediaId, String quality)
          throws IOException, InterruptedException {
    logger.info("Processing subtitles for user: {}, mediaId: {}, quality: {}", user.getId(), mediaId, quality);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found"));

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized to process subtitles for this media");
    }

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      throw new IllegalStateException("No subtitles available to process");
    }

    // ── Download original from R2 ────────────────────────────────────────
    Path tempDir = Paths.get(baseDir)
            .resolve("temp/subtitle-process/" + mediaId).toAbsolutePath().normalize();
    Files.createDirectories(tempDir);

    Path tempInputPath = tempDir.resolve("input_" + getExtension(subtitleMedia.getOriginalFileName()));

    try {
      logger.info("Downloading original file from R2: {}", subtitleMedia.getOriginalPath());
      cloudflareR2Service.downloadFile(subtitleMedia.getOriginalPath(), tempInputPath.toString());

      if (!Files.exists(tempInputPath) || Files.size(tempInputPath) == 0) {
        throw new IOException("Downloaded input file is empty");
      }

      double totalDuration = getVideoDuration(tempInputPath.toFile());

      // ── Plan-limit validation ──────────────────────────────────────────
      validateProcessingLimits(user, quality, totalDuration);

      String finalQuality = quality != null ? quality : "720p";
      subtitleMedia.setQuality(finalQuality);
      subtitleMedia.setStatus("PROCESSING");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);

      validateInputFile(tempInputPath.toFile());

      Map<String, Object> videoInfo = getVideoInfo(tempInputPath.toFile());
      int canvasWidth  = (int) videoInfo.get("width");
      int canvasHeight = (int) videoInfo.get("height");
      float fps        = (float) videoInfo.get("fps");

      List<SubtitleDTO> subtitles = objectMapper.readValue(
              subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});

      if (subtitles.isEmpty()) {
        throw new IOException("No valid subtitles to process");
      }

      // ── Render locally ─────────────────────────────────────────────────
      String outputFileName = "subtitled_" + subtitleMedia.getOriginalFileName();
      Path tempOutputPath   = tempDir.resolve(outputFileName);

      renderSubtitledVideo(tempInputPath.toFile(), tempOutputPath.toFile(), subtitles,
              canvasWidth, canvasHeight, fps, mediaId, totalDuration, finalQuality);

      // ── Upload processed video to R2 ───────────────────────────────────
      String r2OutputPath = "subtitles/" + user.getId() + "/processed/" + outputFileName;
      cloudflareR2Service.uploadFile(tempOutputPath.toFile(), r2OutputPath);

      String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2OutputPath, 0);

      subtitleMedia.setProcessedFileName(outputFileName);
      subtitleMedia.setProcessedPath(r2OutputPath);
      subtitleMedia.setProcessedCdnUrl(cdnUrl);
      subtitleMedia.setStatus("SUCCESS");
      subtitleMedia.setProgress(100.0);
      subtitleMediaRepository.save(subtitleMedia);

      incrementUsageCount(user);

      // ── Send completion email ──────────────────────────────────────────
      // NOTE: Add SUBTITLE to ProcessingEmailHelper.ServiceType enum if not present.
      // Wrapped defensively so an enum/mail failure never rolls back a successful job.
      try {
        ProcessingEmailHelper.ServiceType serviceType =
                ProcessingEmailHelper.ServiceType.valueOf("SUBTITLE");
        emailHelper.sendProcessingCompleteEmail(user, serviceType, outputFileName, cdnUrl, mediaId);
      } catch (IllegalArgumentException enumEx) {
        logger.warn("ProcessingEmailHelper.ServiceType.SUBTITLE not defined — add it to enable emails.");
      } catch (Exception emailEx) {
        logger.warn("Failed to send completion email for mediaId {}: {}", mediaId, emailEx.getMessage());
      }

      logger.info("Successfully processed subtitles for user: {}, mediaId: {}", user.getId(), mediaId);
      return subtitleMedia;

    } catch (Exception e) {
      logger.error("Failed to process subtitles for mediaId {}: {}", mediaId, e.getMessage(), e);
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      if (e instanceof IOException) throw (IOException) e;
      if (e instanceof InterruptedException) throw (InterruptedException) e;
      throw new IOException("Subtitle processing failed: " + e.getMessage(), e);

    } finally {
      cleanUpTempDir(tempDir);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SUBTITLE EDIT METHODS  (unchanged business logic, kept intact)
  // ══════════════════════════════════════════════════════════════════════════

  public SubtitleMedia updateSingleSubtitle(User user, Long mediaId, String subtitleId,
                                            SubtitleDTO updatedSubtitle) throws IOException {
    SubtitleMedia subtitleMedia = getAuthorizedMedia(user, mediaId);

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      throw new IllegalStateException("No subtitles available to update");
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(
            subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    boolean found = false;

    for (int i = 0; i < subtitles.size(); i++) {
      if (subtitles.get(i).getId().equals(subtitleId)) {
        SubtitleDTO existing = subtitles.get(i);
        if (updatedSubtitle.getText()                  != null) existing.setText(updatedSubtitle.getText());
        if (updatedSubtitle.getTimelineStartTime()     != null) existing.setTimelineStartTime(updatedSubtitle.getTimelineStartTime());
        if (updatedSubtitle.getTimelineEndTime()       != null) existing.setTimelineEndTime(updatedSubtitle.getTimelineEndTime());
        if (updatedSubtitle.getFontFamily()            != null) existing.setFontFamily(updatedSubtitle.getFontFamily());
        if (updatedSubtitle.getFontColor()             != null) existing.setFontColor(updatedSubtitle.getFontColor());
        if (updatedSubtitle.getBackgroundColor()       != null) existing.setBackgroundColor(updatedSubtitle.getBackgroundColor());
        if (updatedSubtitle.getScale()                 != null) existing.setScale(updatedSubtitle.getScale());
        if (updatedSubtitle.getBackgroundOpacity()     != null) existing.setBackgroundOpacity(updatedSubtitle.getBackgroundOpacity());
        if (updatedSubtitle.getPositionX()             != null) existing.setPositionX(updatedSubtitle.getPositionX());
        if (updatedSubtitle.getPositionY()             != null) existing.setPositionY(updatedSubtitle.getPositionY());
        if (updatedSubtitle.getAlignment()             != null) existing.setAlignment(updatedSubtitle.getAlignment());
        if (updatedSubtitle.getOpacity()               != null) existing.setOpacity(updatedSubtitle.getOpacity());
        if (updatedSubtitle.getRotation()              != null) existing.setRotation(updatedSubtitle.getRotation());
        if (updatedSubtitle.getBackgroundH()           != null) existing.setBackgroundH(updatedSubtitle.getBackgroundH());
        if (updatedSubtitle.getBackgroundW()           != null) existing.setBackgroundW(updatedSubtitle.getBackgroundW());
        if (updatedSubtitle.getBackgroundBorderRadius()!= null) existing.setBackgroundBorderRadius(updatedSubtitle.getBackgroundBorderRadius());
        if (updatedSubtitle.getBackgroundBorderWidth() != null) existing.setBackgroundBorderWidth(updatedSubtitle.getBackgroundBorderWidth());
        if (updatedSubtitle.getBackgroundBorderColor() != null) existing.setBackgroundBorderColor(updatedSubtitle.getBackgroundBorderColor());
        if (updatedSubtitle.getTextBorderColor()       != null) existing.setTextBorderColor(updatedSubtitle.getTextBorderColor());
        if (updatedSubtitle.getTextBorderWidth()       != null) existing.setTextBorderWidth(updatedSubtitle.getTextBorderWidth());
        if (updatedSubtitle.getTextBorderOpacity()     != null) existing.setTextBorderOpacity(updatedSubtitle.getTextBorderOpacity());
        if (updatedSubtitle.getLetterSpacing()         != null) existing.setLetterSpacing(updatedSubtitle.getLetterSpacing());
        if (updatedSubtitle.getLineSpacing()           != null) existing.setLineSpacing(updatedSubtitle.getLineSpacing());

        if (existing.getTimelineEndTime() <= existing.getTimelineStartTime()) {
          throw new IllegalArgumentException("End time must be greater than start time");
        }
        subtitles.set(i, existing);
        found = true;
        break;
      }
    }

    if (!found) throw new IllegalArgumentException("Subtitle with id " + subtitleId + " not found");

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);
    return subtitleMedia;
  }

  public SubtitleMedia updateMultipleSubtitles(User user, Long mediaId,
                                               List<SubtitleDTO> updatedSubtitles) throws IOException {
    SubtitleMedia subtitleMedia = getAuthorizedMedia(user, mediaId);

    if (updatedSubtitles == null || updatedSubtitles.isEmpty()) {
      throw new IllegalArgumentException("Subtitles list cannot be empty");
    }
    for (SubtitleDTO s : updatedSubtitles) {
      if (s.getId() == null || s.getText() == null || s.getText().trim().isEmpty()) {
        throw new IllegalArgumentException("Subtitle id and text cannot be empty");
      }
      if (s.getTimelineEndTime() <= s.getTimelineStartTime()) {
        throw new IllegalArgumentException("End time must be > start time for subtitle id: " + s.getId());
      }
    }

    List<SubtitleDTO> existing = objectMapper.readValue(
            subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    Map<String, SubtitleDTO> updateMap = new HashMap<>();
    updatedSubtitles.forEach(s -> updateMap.put(s.getId(), s));
    for (int i = 0; i < existing.size(); i++) {
      if (updateMap.containsKey(existing.get(i).getId())) {
        existing.set(i, updateMap.get(existing.get(i).getId()));
      }
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(existing));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);
    return subtitleMedia;
  }

  public SubtitleMedia updateAllSubtitles(User user, Long mediaId,
                                          Map<String, String> styleParams) throws IOException {
    SubtitleMedia subtitleMedia = getAuthorizedMedia(user, mediaId);

    if (subtitleMedia.getSubtitlesJson() == null || subtitleMedia.getSubtitlesJson().isEmpty()) {
      throw new IllegalStateException("No subtitles available to update");
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(
            subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});
    for (SubtitleDTO s : subtitles) {
      s.setFontFamily(styleParams.getOrDefault("fontFamily", s.getFontFamily()));
      s.setFontColor(styleParams.getOrDefault("fontColor", s.getFontColor()));
      s.setBackgroundColor(styleParams.getOrDefault("backgroundColor", s.getBackgroundColor()));
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMedia.setStatus("SUCCESS");
    subtitleMediaRepository.save(subtitleMedia);
    return subtitleMedia;
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  QUERY HELPERS
  // ══════════════════════════════════════════════════════════════════════════

  public List<SubtitleMedia> getUserSubtitleMedia(User user) {
    return subtitleMediaRepository.findByUser(user);
  }

  public User getUserFromToken(String token) {
    String email = jwtUtil.extractEmail(token.substring(7));
    return userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  AUDIO / VIDEO UTILITIES
  // ══════════════════════════════════════════════════════════════════════════

  public String extractAudio(File inputFile, Long mediaId) throws IOException, InterruptedException {
    Path audioDir = Paths.get(baseDir).resolve("temp/subtitle-audio/" + mediaId).toAbsolutePath();
    Files.createDirectories(audioDir);

    String audioFilePath = audioDir.resolve("audio_" + System.currentTimeMillis() + ".mp3").toString();
    List<String> command = Arrays.asList(
            ffmpegPath, "-i", inputFile.getAbsolutePath(),
            "-vn", "-acodec", "mp3", "-y", audioFilePath);

    runProcess(command, "FFmpeg audio extraction");

    File audioFile = new File(audioFilePath);
    if (!audioFile.exists() || audioFile.length() == 0) {
      throw new IOException("Audio file not created or empty: " + audioFilePath);
    }

    // Verify audio stream
    List<String> probeCmd = Arrays.asList(
            getFfprobePath(), "-i", audioFilePath,
            "-show_streams", "-select_streams", "a",
            "-print_format", "json", "-v", "quiet");

    String probeOut = runProcessGetOutput(probeCmd, "FFprobe audio verify");
    Map<String, Object> probeResult = objectMapper.readValue(probeOut, new TypeReference<Map<String, Object>>() {});
    List<?> streams = (List<?>) probeResult.getOrDefault("streams", Collections.emptyList());
    if (streams.isEmpty()) {
      throw new IOException("No audio stream found in file: " + audioFilePath);
    }
    return audioFilePath;
  }

  public double getAudioDuration(File audioFile) throws IOException, InterruptedException {
    return parseDurationFromFFprobe(audioFile.getAbsolutePath());
  }

  private double getVideoDuration(File videoFile) throws IOException, InterruptedException {
    return parseDurationFromFFprobe(videoFile.getAbsolutePath());
  }

  private double parseDurationFromFFprobe(String filePath) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            getFfprobePath(), "-i", filePath,
            "-show_entries", "format=duration",
            "-v", "quiet", "-of", "json");

    String output = runProcessGetOutput(command, "FFprobe duration");

    Map<String, Object> result = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> format = (Map<String, Object>) result.get("format");
    if (format == null || !format.containsKey("duration")) {
      throw new IOException("No duration found in FFprobe output for: " + filePath);
    }
    return Double.parseDouble(format.get("duration").toString());
  }

  private void validateInputFile(File inputFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            getFfprobePath(), "-i", inputFile.getAbsolutePath(),
            "-show_streams", "-show_format",
            "-print_format", "json", "-v", "quiet");

    String output = runProcessGetOutput(command, "FFprobe validate input");
    Map<String, Object> result = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});
    List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
    if (streams.isEmpty()) {
      throw new IOException("No streams found in input file: " + inputFile.getAbsolutePath());
    }
  }

  private Map<String, Object> getVideoInfo(File inputFile) throws IOException, InterruptedException {
    List<String> command = Arrays.asList(
            ffmpegPath, "-i", inputFile.getAbsolutePath(), "-f", "null", "-");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) output.append(line).append("\n");
    }
    process.waitFor();   // non-zero exit is expected for "-f null"

    String outputStr = output.toString();
    Pattern resolutionPattern = Pattern.compile("Stream.*Video:.* (\\d+)x(\\d+).*?([0-9.]+) fps");
    Matcher matcher = resolutionPattern.matcher(outputStr);
    if (!matcher.find()) {
      throw new IOException("Could not parse video info from FFmpeg output");
    }
    Map<String, Object> info = new HashMap<>();
    info.put("width",  Integer.parseInt(matcher.group(1)));
    info.put("height", Integer.parseInt(matcher.group(2)));
    info.put("fps",    Float.parseFloat(matcher.group(3)));
    return info;
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  WHISPER
  // ══════════════════════════════════════════════════════════════════════════

  public List<Map<String, Object>> runWhisperScript(File audioFile) throws IOException, InterruptedException {
    File scriptFile = new File(subtitleScriptPath);
    if (!scriptFile.exists()) {
      throw new IOException("Subtitle script not found: " + scriptFile.getAbsolutePath());
    }

    List<String> command = Arrays.asList(pythonPath, scriptFile.getAbsolutePath(), audioFile.getAbsolutePath());
    logger.debug("Executing Whisper command: {}", String.join(" ", command));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    Process process = pb.start();

    StringBuilder output      = new StringBuilder();
    StringBuilder errorOutput = new StringBuilder();

    Thread stdoutThread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (output) { output.append(line); }
        }
      } catch (IOException e) { logger.error("Error reading Whisper stdout: {}", e.getMessage()); }
    });

    Thread stderrThread = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (errorOutput) { errorOutput.append(line).append("\n"); }
          logger.debug("Whisper stderr: {}", line);
        }
      } catch (IOException e) { logger.error("Error reading Whisper stderr: {}", e.getMessage()); }
    });

    stdoutThread.start();
    stderrThread.start();

    boolean finished = process.waitFor(15, TimeUnit.MINUTES);
    stdoutThread.join(5000);
    stderrThread.join(5000);

    if (!finished) {
      process.destroyForcibly();
      throw new IOException("Whisper transcription timed out after 15 minutes");
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
      throw new IOException("Whisper failed (exit " + exitCode + "): " + errorOutput);
    }

    String outputStr = output.toString().trim();
    if (outputStr.isEmpty()) {
      throw new IOException("No output from Whisper script. Stderr: " + errorOutput);
    }

    try {
      return objectMapper.readValue(outputStr, new TypeReference<List<Map<String, Object>>>() {});
    } catch (JsonProcessingException e) {
      throw new IOException("Invalid JSON from Whisper: " + outputStr, e);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  VIDEO RENDERING  (batch + concat – same algorithm as local version)
  // ══════════════════════════════════════════════════════════════════════════

  private void renderSubtitledVideo(File inputFile, File outputFile, List<SubtitleDTO> subtitles,
                                    int canvasWidth, int canvasHeight, float fps, Long mediaId,
                                    double totalDuration, String quality) throws IOException, InterruptedException {
    Path tempDir = Paths.get(baseDir).resolve("temp/subtitle-render/" + mediaId).toAbsolutePath();
    Files.createDirectories(tempDir);

    double batchSize = 8.0;
    List<String> tempVideoFiles = new ArrayList<>();
    List<File>   tempTextFiles  = new ArrayList<>();

    try {
      int batchIndex = 0;
      for (double startTime = 0; startTime < totalDuration; startTime += batchSize) {
        double endTime       = Math.min(startTime + batchSize, totalDuration);
        String safeBatchName = "batch_" + batchIndex + ".mp4";
        String tempOutput    = tempDir.resolve(safeBatchName).toString();
        tempVideoFiles.add(tempOutput);
        Map<String, String> qualitySettings = getFFmpegQualitySettings(quality);
        renderBatch(inputFile, new File(tempOutput), subtitles, canvasWidth, canvasHeight, fps,
                mediaId, startTime, endTime, totalDuration, batchIndex, tempTextFiles, qualitySettings);
        batchIndex++;
      }
      concatenateBatches(tempVideoFiles, outputFile.getAbsolutePath(), fps, tempDir.toFile());
    } finally {
      for (File f : tempTextFiles)   { try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {} }
      for (String v : tempVideoFiles) { try { Files.deleteIfExists(Paths.get(v)); } catch (IOException ignored) {} }
    }
  }

  private void renderBatch(File inputFile, File outputFile, List<SubtitleDTO> subtitles,
                           int canvasWidth, int canvasHeight, float fps, Long mediaId,
                           double batchStart, double batchEnd, double totalDuration, int batchIndex,
                           List<File> tempTextFiles, Map<String, String> qualitySettings)
          throws IOException, InterruptedException {

    double batchDuration = batchEnd - batchStart;
    Path batchTempDir    = Paths.get(baseDir).resolve("temp/subtitle-render/" + mediaId).toAbsolutePath();
    Files.createDirectories(batchTempDir);

    List<String> command = new ArrayList<>();
    command.add(ffmpegPath);
    command.add("-ss"); command.add(String.format("%.6f", batchStart));
    command.add("-t");  command.add(String.format("%.6f", batchDuration));
    command.add("-i");  command.add(inputFile.getAbsolutePath());

    StringBuilder filterComplex  = new StringBuilder();
    Map<String, String> textInputIndices = new HashMap<>();
    int inputCount = 1;

    List<SubtitleDTO> relevantSubtitles = subtitles.stream()
            .filter(s -> s.getTimelineStartTime() < batchEnd && s.getTimelineEndTime() > batchStart)
            .collect(Collectors.toList());

    for (SubtitleDTO subtitle : relevantSubtitles) {
      if (subtitle.getText() == null || subtitle.getText().trim().isEmpty()) continue;
      if (subtitle.getTimelineEndTime() <= subtitle.getTimelineStartTime()) continue;

      String textPngPath = generateTextPng(subtitle, batchTempDir.toFile(), canvasWidth, canvasHeight);
      File textPngFile   = new File(textPngPath);
      if (!textPngFile.exists() || textPngFile.length() == 0) continue;

      tempTextFiles.add(textPngFile);
      command.add("-loop"); command.add("1");
      command.add("-i");    command.add(textPngPath);
      textInputIndices.put(subtitle.getId(), String.valueOf(inputCount++));
    }

    if (textInputIndices.isEmpty()) {
      // No subtitles in this batch – just copy + scale
      command.clear();
      command.add(ffmpegPath);
      command.add("-ss"); command.add(String.format("%.6f", batchStart));
      command.add("-t");  command.add(String.format("%.6f", batchDuration));
      command.add("-i");  command.add(inputFile.getAbsolutePath());
      command.add("-vf"); command.add("scale=" + qualitySettings.get("scale"));
      command.add("-c:v"); command.add("libx264");
      command.add("-preset"); command.add(qualitySettings.get("preset"));
      command.add("-crf");    command.add(qualitySettings.get("crf"));
      command.add("-pix_fmt"); command.add("yuv420p");
      command.add("-c:a");    command.add("copy");
      command.add("-y");      command.add(outputFile.getAbsolutePath());
    } else {
      String lastOutput  = "0:v";
      int    overlayCount = 0;

      for (SubtitleDTO subtitle : relevantSubtitles) {
        String inputIdx = textInputIndices.get(subtitle.getId());
        if (inputIdx == null) continue;

        double segmentStart = Math.max(subtitle.getTimelineStartTime(), batchStart) - batchStart;
        double segmentEnd   = Math.min(subtitle.getTimelineEndTime(),   batchEnd)   - batchStart;
        if (segmentStart >= segmentEnd) continue;

        String outputLabel = "ov" + overlayCount++;
        double defaultScale = subtitle.getScale() != null ? subtitle.getScale() : 1.0;
        double resolutionMultiplier = canvasWidth >= 3840 ? 1.5 : 2.0;
        double baseScale = 1.0 / resolutionMultiplier;

        filterComplex.append("[").append(inputIdx).append(":v]");
        filterComplex.append("setpts=PTS-STARTPTS+")
                .append(String.format("%.6f", segmentStart)).append("/TB,");
        filterComplex.append("scale=w='2*trunc((iw*").append(baseScale).append("*")
                .append(String.format("%.6f", defaultScale))
                .append(")/2)':h='2*trunc((ih*").append(baseScale).append("*")
                .append(String.format("%.6f", defaultScale))
                .append(")/2)':flags=lanczos:eval=frame,");

        double rotation = subtitle.getRotation() != null ? subtitle.getRotation() : 0.0;
        if (Math.abs(rotation) > 0.01) {
          filterComplex.append("rotate=a=").append(String.format("%.6f", Math.toRadians(rotation)))
                  .append(":ow='2*trunc(hypot(iw,ih)/2)'")
                  .append(":oh='2*trunc(hypot(iw,ih)/2)'")
                  .append(":c=0x00000000,");
        }
        filterComplex.append("format=rgba,");

        double opacity = subtitle.getOpacity() != null ? subtitle.getOpacity() : 1.0;
        if (opacity < 1.0) {
          filterComplex.append("colorchannelmixer=aa=").append(String.format("%.6f", opacity)).append(",");
        }

        String xExpr, yExpr;
        if ("left".equalsIgnoreCase(subtitle.getAlignment())) {
          xExpr = String.format("%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        } else if ("right".equalsIgnoreCase(subtitle.getAlignment())) {
          xExpr = String.format("W-w-%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        } else {
          xExpr = String.format("(W-w)/2+%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
        }
        yExpr = String.format("(H-h)/2+%d", subtitle.getPositionY() != null ? subtitle.getPositionY() : 0);

        filterComplex.append("[").append(lastOutput).append("]");
        filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr)
                .append("':format=auto")
                .append(":enable='between(t,").append(String.format("%.6f", segmentStart))
                .append(",").append(String.format("%.6f", segmentEnd)).append(")'");
        filterComplex.append("[ov").append(outputLabel).append("];");
        lastOutput = "ov" + outputLabel;
      }

      // Scale at the end of the filter chain
      filterComplex.append("[").append(lastOutput).append("]")
              .append("scale=").append(qualitySettings.get("scale"))
              .append(",setpts=PTS-STARTPTS[vout]");

      command.add("-filter_complex"); command.add(filterComplex.toString());
      command.add("-map");    command.add("[vout]");
      command.add("-map");    command.add("0:a?");
      command.add("-c:v");    command.add("libx264");
      command.add("-preset"); command.add(qualitySettings.get("preset"));
      command.add("-crf");    command.add(qualitySettings.get("crf"));
      command.add("-pix_fmt"); command.add("yuv420p");
      command.add("-c:a");    command.add("aac");
      command.add("-b:a");    command.add("192k");
      command.add("-t");      command.add(String.format("%.6f", batchDuration));
      command.add("-r");      command.add(String.format("%.2f", fps));
      command.add("-y");      command.add(outputFile.getAbsolutePath());
    }

    logger.debug("FFmpeg batch command: {}", String.join(" ", command));
    executeFFmpegWithProgress(command, mediaId, batchStart, batchDuration, totalDuration, batchIndex);
  }

  private void concatenateBatches(List<String> tempVideoFiles, String outputPath,
                                  float fps, File tempDir) throws IOException, InterruptedException {
    if (tempVideoFiles.isEmpty()) throw new IllegalStateException("No batch files to concatenate");

    if (tempVideoFiles.size() == 1) {
      Files.move(Paths.get(tempVideoFiles.get(0)), Paths.get(outputPath),
              StandardCopyOption.REPLACE_EXISTING);
      return;
    }

    File concatListFile = new File(tempDir, "concat_list.txt");
    try (PrintWriter writer = new PrintWriter(concatListFile, "UTF-8")) {
      for (String f : tempVideoFiles) {
        writer.println("file '" + f.replace("\\", "\\\\") + "'");
      }
    }

    List<String> command = Arrays.asList(
            ffmpegPath,
            "-f", "concat", "-safe", "0",
            "-i", concatListFile.getAbsolutePath(),
            "-c", "copy",
            "-r", String.valueOf(fps),
            "-y", outputPath);

    try {
      runProcess(command, "FFmpeg concatenate");
    } finally {
      try { Files.deleteIfExists(concatListFile.toPath()); } catch (IOException ignored) {}
    }
  }

  private void executeFFmpegWithProgress(List<String> command, Long mediaId,
                                         double batchStart, double batchDuration,
                                         double totalDuration, int batchIndex)
          throws IOException, InterruptedException {

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

    List<String> cmd = new ArrayList<>(command);
    if (!cmd.contains("-progress")) { cmd.add("-progress"); cmd.add("pipe:"); }

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    double lastProgress = -1.0;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("out_time_ms=") && !line.contains("N/A")) {
          try {
            long   outTimeUs          = Long.parseLong(line.replace("out_time_ms=", ""));
            double currentBatchTime   = outTimeUs / 1_000_000.0;
            double batchProgress      = Math.min(currentBatchTime / batchDuration, 1.0);
            double batchContribution  = batchDuration / totalDuration * 100.0;
            double completedPrevious  = batchIndex * (batchDuration / totalDuration * 100.0);
            double totalProgress      = Math.min(completedPrevious + batchProgress * batchContribution, 100.0);
            int    rounded            = (int) Math.round(totalProgress);
            if (rounded != (int) lastProgress && rounded >= 0 && rounded <= 100 && rounded % 10 == 0) {
              subtitleMedia.setProgress((double) rounded);
              subtitleMedia.setStatus("PROCESSING");
              subtitleMediaRepository.save(subtitleMedia);
              logger.info("Progress: {}% for mediaId: {}", rounded, mediaId);
              lastProgress = rounded;
            }
          } catch (NumberFormatException ignored) {}
        }
      }
    }

    boolean completed = process.waitFor(10, TimeUnit.MINUTES);
    if (!completed) {
      process.destroyForcibly();
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      throw new RuntimeException("FFmpeg timed out after 10 minutes for mediaId: " + mediaId);
    }

    int exitCode = process.exitValue();
    if (exitCode != 0) {
      subtitleMedia.setStatus("FAILED");
      subtitleMedia.setProgress(0.0);
      subtitleMediaRepository.save(subtitleMedia);
      throw new RuntimeException("FFmpeg failed (exit " + exitCode + ") for mediaId: " + mediaId);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PNG GENERATION  (unchanged – runs locally inside Docker)
  // ══════════════════════════════════════════════════════════════════════════

  public String generateTextPng(SubtitleDTO ts, File outputDir, int canvasWidth, int canvasHeight)
          throws IOException {
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      throw new IOException("Failed to create PNG output dir: " + outputDir.getAbsolutePath());
    }
    String     fileName   = "subtitle_" + ts.getId() + "_" + System.nanoTime() + ".png";
    File       outputFile = new File(outputDir, fileName);

    final double RESOLUTION_MULTIPLIER = canvasWidth >= 3840 ? 1.5 : 2.0;
    final double BORDER_SCALE_FACTOR   = canvasWidth >= 3840 ? 1.5 : 2.0;

    double defaultScale = ts.getScale() != null ? ts.getScale() : 1.0;
    List<Keyframe> scaleKeyframes = ts.getKeyframes().getOrDefault("scale", new ArrayList<>());
    double maxScale = defaultScale;
    if (!scaleKeyframes.isEmpty()) {
      maxScale = Math.max(defaultScale, scaleKeyframes.stream()
              .mapToDouble(kf -> ((Number) kf.getValue()).doubleValue()).max().orElse(defaultScale));
    }

    Color fontColor     = parseColor(ts.getFontColor(), Color.WHITE, "font", ts.getId());
    Color bgColor       = ts.getBackgroundColor() != null && !ts.getBackgroundColor().equals("transparent")
            ? parseColor(ts.getBackgroundColor(), null, "background", ts.getId()) : null;
    Color bgBorderColor = ts.getBackgroundBorderColor() != null && !ts.getBackgroundBorderColor().equals("transparent")
            ? parseColor(ts.getBackgroundBorderColor(), null, "border", ts.getId()) : null;
    Color textBorderColor = ts.getTextBorderColor() != null && !ts.getTextBorderColor().equals("transparent")
            ? parseColor(ts.getTextBorderColor(), null, "text border", ts.getId()) : null;

    double baseFontSize = 24.0 * maxScale * RESOLUTION_MULTIPLIER;
    Font font;
    try {
      String fontPath = containsHindiCharacters(ts.getText())
              ? getFontFilePath("NotoSansDevanagari-Regular.ttf", "/fonts/",
              System.getProperty("java.io.tmpdir") + "/scenith-fonts/")
              : getFontPathByFamily(ts.getFontFamily());
      font = Font.createFont(Font.TRUETYPE_FONT, new File(fontPath)).deriveFont((float) baseFontSize);
    } catch (Exception e) {
      try {
        String hindiFontPath = getFontFilePath("NotoSansDevanagari-Regular.ttf", "/fonts/",
                System.getProperty("java.io.tmpdir") + "/scenith-fonts/");
        font = Font.createFont(Font.TRUETYPE_FONT, new File(hindiFontPath)).deriveFont((float) baseFontSize);
      } catch (Exception ex) {
        font = new Font("Arial", Font.PLAIN, (int) baseFontSize);
      }
    }

    double letterSpacing       = ts.getLetterSpacing()  != null ? ts.getLetterSpacing()  : 0.0;
    double scaledLetterSpacing = letterSpacing * maxScale * RESOLUTION_MULTIPLIER;
    double lineSpacing         = ts.getLineSpacing()     != null ? ts.getLineSpacing()     : 1.2;
    double scaledLineSpacing   = lineSpacing * baseFontSize;

    // Measure text
    BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = tempImage.createGraphics();
    g2d.setFont(font);
    FontMetrics fm = g2d.getFontMetrics();
    String[] lines = ts.getText().split("\n");
    int lineHeight      = (int) scaledLineSpacing;
    int totalTextHeight = lines.length > 1
            ? (lines.length - 1) * lineHeight + fm.getAscent() + fm.getDescent()
            : fm.getAscent() + fm.getDescent();
    int maxTextWidth = 0;
    for (String line : lines) {
      int lw = 0;
      for (int i = 0; i < line.length(); i++) {
        lw += fm.charWidth(line.charAt(i));
        if (i < line.length() - 1) lw += (int) scaledLetterSpacing;
      }
      maxTextWidth = Math.max(maxTextWidth, lw);
    }
    g2d.dispose();
    tempImage.flush();

    int bgHeight       = (int) ((ts.getBackgroundH()           != null ? ts.getBackgroundH()           : 0) * maxScale * RESOLUTION_MULTIPLIER);
    int bgWidth        = (int) ((ts.getBackgroundW()           != null ? ts.getBackgroundW()           : 0) * maxScale * RESOLUTION_MULTIPLIER);
    int bgBorderWidth  = (int) ((ts.getBackgroundBorderWidth() != null ? ts.getBackgroundBorderWidth() : 0) * maxScale * BORDER_SCALE_FACTOR);
    int borderRadius   = (int) ((ts.getBackgroundBorderRadius()!= null ? ts.getBackgroundBorderRadius(): 0) * maxScale * RESOLUTION_MULTIPLIER);
    int textBorderWidth= (int) ((ts.getTextBorderWidth()       != null ? ts.getTextBorderWidth()       : 0) * maxScale * BORDER_SCALE_FACTOR);

    int contentWidth   = maxTextWidth + bgWidth + 2 * textBorderWidth;
    int contentHeight  = totalTextHeight + bgHeight + 2 * textBorderWidth;

    int maxDimension = (int) (Math.max(canvasWidth, canvasHeight) * RESOLUTION_MULTIPLIER * 1.5);
    if (contentWidth + 2 * bgBorderWidth + 2 * textBorderWidth > maxDimension
            || contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth > maxDimension) {
      double scaleDown = Math.max(0.5, Math.min(
              maxDimension / (double) (contentWidth  + 2 * bgBorderWidth + 2 * textBorderWidth),
              maxDimension / (double) (contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth)));
      bgWidth        = (int) (bgWidth        * scaleDown);
      bgHeight       = (int) (bgHeight       * scaleDown);
      bgBorderWidth  = (int) (bgBorderWidth  * scaleDown);
      borderRadius   = (int) (borderRadius   * scaleDown);
      textBorderWidth= (int) (textBorderWidth* scaleDown);
      contentWidth   = maxTextWidth + bgWidth + 2 * textBorderWidth;
      contentHeight  = totalTextHeight + bgHeight + 2 * textBorderWidth;
    }

    int totalWidth  = contentWidth  + 2 * bgBorderWidth + 2 * textBorderWidth;
    int totalHeight = contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth;
    totalWidth  = totalWidth  % 2 != 0 ? totalWidth  + 1 : totalWidth;
    totalHeight = totalHeight % 2 != 0 ? totalHeight + 1 : totalHeight;

    BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
    g2d = image.createGraphics();
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,       RenderingHints.VALUE_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,  RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g2d.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);
    g2d.setFont(font);
    fm = g2d.getFontMetrics();

    // Background
    if (bgColor != null) {
      float bgOpacity = ts.getBackgroundOpacity() != null ? ts.getBackgroundOpacity().floatValue() : 1.0f;
      g2d.setColor(new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), (int)(bgOpacity*255)));
      if (borderRadius > 0) {
        g2d.fillRoundRect(bgBorderWidth + textBorderWidth, bgBorderWidth + textBorderWidth,
                contentWidth, contentHeight, borderRadius, borderRadius);
      } else {
        g2d.fillRect(bgBorderWidth + textBorderWidth, bgBorderWidth + textBorderWidth,
                contentWidth, contentHeight);
      }
    }

    // Background border
    if (bgBorderColor != null && bgBorderWidth > 0) {
      g2d.setColor(bgBorderColor);
      g2d.setStroke(new BasicStroke((float) bgBorderWidth));
      if (borderRadius > 0) {
        g2d.drawRoundRect(bgBorderWidth/2+textBorderWidth, bgBorderWidth/2+textBorderWidth,
                contentWidth+bgBorderWidth, contentHeight+bgBorderWidth,
                borderRadius+bgBorderWidth, borderRadius+bgBorderWidth);
      } else {
        g2d.drawRect(bgBorderWidth/2+textBorderWidth, bgBorderWidth/2+textBorderWidth,
                contentWidth+bgBorderWidth, contentHeight+bgBorderWidth);
      }
    }

    // Text
    String alignment  = ts.getAlignment() != null ? ts.getAlignment().toLowerCase() : "center";
    int    textYStart = bgBorderWidth + textBorderWidth + (contentHeight - totalTextHeight) / 2 + fm.getAscent();
    int    y          = textYStart;
    FontRenderContext frc = g2d.getFontRenderContext();

    for (String line : lines) {
      int lineWidth = 0;
      for (int i = 0; i < line.length(); i++) {
        lineWidth += fm.charWidth(line.charAt(i));
        if (i < line.length() - 1) lineWidth += (int) scaledLetterSpacing;
      }
      int x;
      if      (alignment.equals("left"))  x = bgBorderWidth + textBorderWidth;
      else if (alignment.equals("center")) x = bgBorderWidth + textBorderWidth + (contentWidth - lineWidth) / 2;
      else                                  x = bgBorderWidth + textBorderWidth + contentWidth - lineWidth;

      if (textBorderColor != null && textBorderWidth > 0) {
        float textBorderOpacity = ts.getTextBorderOpacity() != null ? ts.getTextBorderOpacity().floatValue() : 1.0f;
        g2d.setColor(new Color(textBorderColor.getRed(), textBorderColor.getGreen(),
                textBorderColor.getBlue(), (int)(textBorderOpacity*255)));
        g2d.setStroke(new BasicStroke((float)textBorderWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Area combinedArea = new Area();
        int cx = x;
        for (int i = 0; i < line.length(); i++) {
          char c = line.charAt(i);
          TextLayout charLayout = new TextLayout(String.valueOf(c), font, frc);
          Shape charShape = charLayout.getOutline(AffineTransform.getTranslateInstance(cx, y));
          combinedArea.add(new Area(charShape));
          cx += fm.charWidth(c) + (int) scaledLetterSpacing;
        }
        g2d.draw(combinedArea);
      }

      g2d.setColor(fontColor);
      int cx = x;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        g2d.drawString(String.valueOf(c), cx, y);
        cx += fm.charWidth(c) + (int) scaledLetterSpacing;
      }
      y += lineHeight;
    }

    g2d.dispose();
    ImageIO.write(image, "PNG", outputFile);
    return outputFile.getAbsolutePath();
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FONT HELPERS  (identical to local – fonts are bundled in the Docker image)
  // ══════════════════════════════════════════════════════════════════════════

  private String getFontPathByFamily(String fontFamily) {
    final String FONTS_RESOURCE_PATH = "/fonts/";
    final String TEMP_FONT_DIR       = System.getProperty("java.io.tmpdir") + "/scenith-fonts/";

    File tempDir = new File(TEMP_FONT_DIR);
    if (!tempDir.exists()) tempDir.mkdirs();

    String defaultFontPath = getFontFilePath("arial.ttf", FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
    if (fontFamily == null || fontFamily.trim().isEmpty()) return defaultFontPath;

    Map<String, String> fontMap = new LinkedHashMap<>();
    // System fonts
    fontMap.put("Arial", "arial.ttf");
    fontMap.put("Arial Bold", "arialbd.ttf");
    fontMap.put("Arial Italic", "ariali.ttf");
    fontMap.put("Arial Bold Italic", "arialbi.ttf");
    fontMap.put("Arial Black", "ariblk.ttf");
    fontMap.put("Times New Roman", "times.ttf");
    fontMap.put("Times New Roman Bold", "timesbd.ttf");
    fontMap.put("Times New Roman Italic", "timesi.ttf");
    fontMap.put("Times New Roman Bold Italic", "timesbi.ttf");
    fontMap.put("Courier New", "cour.ttf");
    fontMap.put("Calibri", "calibri.ttf");
    fontMap.put("Verdana", "verdana.ttf");
    fontMap.put("Georgia", "georgia.ttf");
    fontMap.put("Georgia Bold", "georgiab.ttf");
    fontMap.put("Georgia Italic", "georgiai.ttf");
    fontMap.put("Georgia Bold Italic", "georgiaz.ttf");
    fontMap.put("Comic Sans MS", "comic.ttf");
    fontMap.put("Impact", "impact.ttf");
    fontMap.put("Tahoma", "tahoma.ttf");
    // Google / custom fonts
    fontMap.put("Alumni Sans Pinstripe", "AlumniSansPinstripe-Regular.ttf");
    fontMap.put("Lexend Giga", "LexendGiga-Regular.ttf");
    fontMap.put("Lexend Giga Black", "LexendGiga-Black.ttf");
    fontMap.put("Lexend Giga Bold", "LexendGiga-Bold.ttf");
    fontMap.put("Montserrat Alternates", "MontserratAlternates-ExtraLight.ttf");
    fontMap.put("Montserrat Alternates Black", "MontserratAlternates-Black.ttf");
    fontMap.put("Montserrat Alternates Medium Italic", "MontserratAlternates-MediumItalic.ttf");
    fontMap.put("Noto Sans Mono", "NotoSansMono-Regular.ttf");
    fontMap.put("Noto Sans Mono Bold", "NotoSansMono-Bold.ttf");
    fontMap.put("Noto Sans Devanagari", "NotoSansDevanagari-Regular.ttf");
    fontMap.put("Poiret One", "PoiretOne-Regular.ttf");
    fontMap.put("Arimo", "Arimo-Regular.ttf");
    fontMap.put("Arimo Bold", "Arimo-Bold.ttf");
    fontMap.put("Arimo Bold Italic", "Arimo-BoldItalic.ttf");
    fontMap.put("Arimo Italic", "Arimo-Italic.ttf");
    fontMap.put("Carlito", "Carlito-Regular.ttf");
    fontMap.put("Carlito Bold", "Carlito-Bold.ttf");
    fontMap.put("Carlito Bold Italic", "Carlito-BoldItalic.ttf");
    fontMap.put("Carlito Italic", "Carlito-Italic.ttf");
    fontMap.put("Comic Neue", "ComicNeue-Regular.ttf");
    fontMap.put("Comic Neue Bold", "ComicNeue-Bold.ttf");
    fontMap.put("Comic Neue Bold Italic", "ComicNeue-BoldItalic.ttf");
    fontMap.put("Comic Neue Italic", "ComicNeue-Italic.ttf");
    fontMap.put("Courier Prime", "CourierPrime-Regular.ttf");
    fontMap.put("Courier Prime Bold", "CourierPrime-Bold.ttf");
    fontMap.put("Courier Prime Bold Italic", "CourierPrime-BoldItalic.ttf");
    fontMap.put("Courier Prime Italic", "CourierPrime-Italic.ttf");
    fontMap.put("Gelasio", "Gelasio-Regular.ttf");
    fontMap.put("Gelasio Bold", "Gelasio-Bold.ttf");
    fontMap.put("Gelasio Bold Italic", "Gelasio-BoldItalic.ttf");
    fontMap.put("Gelasio Italic", "Gelasio-Italic.ttf");
    fontMap.put("Tinos", "Tinos-Regular.ttf");
    fontMap.put("Tinos Bold", "Tinos-Bold.ttf");
    fontMap.put("Tinos Bold Italic", "Tinos-BoldItalic.ttf");
    fontMap.put("Tinos Italic", "Tinos-Italic.ttf");
    fontMap.put("Amatic SC", "AmaticSC-Regular.ttf");
    fontMap.put("Amatic SC Bold", "AmaticSC-Bold.ttf");
    fontMap.put("Barriecito", "Barriecito-Regular.ttf");
    fontMap.put("Barrio", "Barrio-Regular.ttf");
    fontMap.put("Birthstone", "Birthstone-Regular.ttf");
    fontMap.put("Bungee Hairline", "BungeeHairline-Regular.ttf");
    fontMap.put("Butcherman", "Butcherman-Regular.ttf");
    fontMap.put("Doto Black", "Doto-Black.ttf");
    fontMap.put("Doto ExtraBold", "Doto-ExtraBold.ttf");
    fontMap.put("Doto Rounded Bold", "Doto_Rounded-Bold.ttf");
    fontMap.put("Fascinate Inline", "FascinateInline-Regular.ttf");
    fontMap.put("Freckle Face", "FreckleFace-Regular.ttf");
    fontMap.put("Fredericka the Great", "FrederickatheGreat-Regular.ttf");
    fontMap.put("Imperial Script", "ImperialScript-Regular.ttf");
    fontMap.put("Kings", "Kings-Regular.ttf");
    fontMap.put("Kirang Haerang", "KirangHaerang-Regular.ttf");
    fontMap.put("Lavishly Yours", "LavishlyYours-Regular.ttf");
    fontMap.put("Mountains of Christmas", "MountainsofChristmas-Regular.ttf");
    fontMap.put("Mountains of Christmas Bold", "MountainsofChristmas-Bold.ttf");
    fontMap.put("Rampart One", "RampartOne-Regular.ttf");
    fontMap.put("Rubik Wet Paint", "RubikWetPaint-Regular.ttf");
    fontMap.put("Tangerine", "Tangerine-Regular.ttf");
    fontMap.put("Tangerine Bold", "Tangerine-Bold.ttf");
    fontMap.put("Yesteryear", "Yesteryear-Regular.ttf");

    String key = fontFamily.trim();
    if (fontMap.containsKey(key)) {
      return getFontFilePath(fontMap.get(key), FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
    }
    for (Map.Entry<String, String> entry : fontMap.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) {
        return getFontFilePath(entry.getValue(), FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
      }
    }
    logger.warn("Font family '{}' not found, using Arial", fontFamily);
    return defaultFontPath;
  }

  private String getFontFilePath(String fontFileName, String fontsResourcePath, String tempFontDir) {
    try {
      File tempFontFile = new File(tempFontDir + fontFileName);
      if (tempFontFile.exists()) return tempFontFile.getAbsolutePath();

      InputStream fontStream = getClass().getResourceAsStream(fontsResourcePath + fontFileName);
      if (fontStream == null) {
        logger.error("Font not found in resources: {}{}", fontsResourcePath, fontFileName);
        return "/usr/share/fonts/liberation/LiberationSans-Regular.ttf"; // Linux fallback
      }
      Files.copy(fontStream, tempFontFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      fontStream.close();
      return tempFontFile.getAbsolutePath();
    } catch (IOException e) {
      logger.error("Error loading font {}: {}", fontFileName, e.getMessage());
      return "/usr/share/fonts/liberation/LiberationSans-Regular.ttf";
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PLAN-LIMITS & USAGE TRACKING
  // ══════════════════════════════════════════════════════════════════════════

  private void validateProcessingLimits(User user, String quality, double videoDuration) {
    if (quality != null && !planLimitsService.isQualityAllowed(user, quality)) {
      throw new IllegalArgumentException("Quality " + quality + " not allowed. Maximum: "
              + planLimitsService.getMaxAllowedQuality(user));
    }

    int maxPerMonth = planLimitsService.getMaxVideoProcessingPerMonth(user);
    if (maxPerMonth > 0) {
      String currentYearMonth = YearMonth.now().toString();
      Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository
              .findByUserAndServiceTypeAndYearMonth(user, "SUBTITLE", currentYearMonth);
      int currentCount = usageOpt.map(UserProcessingUsage::getProcessCount).orElse(0);
      if (currentCount >= maxPerMonth) {
        throw new IllegalArgumentException(
                "Monthly processing limit reached (" + maxPerMonth + "). Upgrade your plan.");
      }
    }

    int maxMinutes = planLimitsService.getMaxVideoLengthMinutes(user);
    if (maxMinutes > 0 && videoDuration > maxMinutes * 60) {
      throw new IllegalArgumentException(
              "Video length exceeds maximum allowed (" + maxMinutes + " minutes). Upgrade your plan.");
    }
  }

  private void incrementUsageCount(User user) {
    String currentYearMonth = YearMonth.now().toString();
    Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository
            .findByUserAndServiceTypeAndYearMonth(user, "SUBTITLE", currentYearMonth);
    UserProcessingUsage usage;
    if (usageOpt.isPresent()) {
      usage = usageOpt.get();
      usage.setProcessCount(usage.getProcessCount() + 1);
    } else {
      usage = new UserProcessingUsage();
      usage.setUser(user);
      usage.setServiceType("SUBTITLE");
      usage.setYearMonth(currentYearMonth);
      usage.setProcessCount(1);
    }
    userProcessingUsageRepository.save(usage);
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  QUALITY SETTINGS
  // ══════════════════════════════════════════════════════════════════════════

  private Map<String, String> getFFmpegQualitySettings(String quality) {
    Map<String, String> s = new HashMap<>();
    switch (quality != null ? quality.toLowerCase() : "720p") {
      case "144p":  s.put("scale","-2:144");  s.put("crf","28"); s.put("preset","veryfast"); break;
      case "240p":  s.put("scale","-2:240");  s.put("crf","27"); s.put("preset","veryfast"); break;
      case "360p":  s.put("scale","-2:360");  s.put("crf","26"); s.put("preset","fast");     break;
      case "480p":  s.put("scale","-2:480");  s.put("crf","25"); s.put("preset","fast");     break;
      case "1080p": s.put("scale","-2:1080"); s.put("crf","22"); s.put("preset","medium");   break;
      case "1440p":
      case "2k":    s.put("scale","-2:1440"); s.put("crf","20"); s.put("preset","slow");     break;
      case "4k":    s.put("scale","-2:2160"); s.put("crf","18"); s.put("preset","slow");     break;
      default:      s.put("scale","-2:720");  s.put("crf","23"); s.put("preset","medium");   break;
    }
    return s;
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SMALL UTILITIES
  // ══════════════════════════════════════════════════════════════════════════

  private SubtitleMedia getAuthorizedMedia(User user, Long mediaId) {
    SubtitleMedia media = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));
    if (!media.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized to access media: " + mediaId);
    }
    return media;
  }

  private String getFfprobePath() {
    // On Linux the binaries sit next to each other; on Windows they share the same dir
    return ffmpegPath.replace("ffmpeg", "ffprobe");
  }

  /** Run a process, capture stdout+stderr, throw on non-zero exit. Has a 5-minute timeout. */
  private void runProcess(List<String> command, String label) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line).append("\n");
    }
    boolean completed = process.waitFor(5, TimeUnit.MINUTES);
    if (!completed) {
      process.destroyForcibly();
      throw new IOException(label + " timed out after 5 minutes. Partial output: " + sb);
    }
    int exit = process.exitValue();
    if (exit != 0) throw new IOException(label + " failed (exit " + exit + "): " + sb);
  }

  /** Run a process and return stdout as a String; throws on non-zero exit. */
  private String runProcessGetOutput(List<String> command, String label) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    Process process = pb.start();

    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();

    Thread t1 = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String l; while ((l = r.readLine()) != null) stdout.append(l).append("\n");
      } catch (IOException ignored) {}
    });
    Thread t2 = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String l; while ((l = r.readLine()) != null) stderr.append(l).append("\n");
      } catch (IOException ignored) {}
    });
    t1.start(); t2.start();
    int exit = process.waitFor();
    t1.join(3000); t2.join(3000);

    if (exit != 0) throw new IOException(label + " failed (exit " + exit + "): " + stderr);
    return stdout.toString().trim();
  }

  private void cleanUpTempDir(Path dir) {
    try {
      if (!Files.exists(dir)) return;
      Files.walk(dir)
              .sorted(Comparator.reverseOrder())
              .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    } catch (IOException e) {
      logger.warn("Failed to clean up temp dir {}: {}", dir, e.getMessage());
    }
  }

  private String getExtension(String filename) {
    if (filename == null) return ".mp4";
    int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(dot) : ".mp4";
  }

  private String sanitizeFilename(String filename) {
    if (filename == null) return "media_" + System.currentTimeMillis() + ".mp4";
    return filename.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
  }

  private boolean containsHindiCharacters(String text) {
    if (text == null) return false;
    for (char c : text.toCharArray()) if (c >= 0x0900 && c <= 0x097F) return true;
    return false;
  }

  private Color parseColor(String colorStr, Color fallback, String type, String segmentId) {
    try {
      return Color.decode(colorStr);
    } catch (Exception e) {
      logger.warn("Invalid {} color for segment {}: {}", type, segmentId, colorStr);
      return fallback;
    }
  }
  @Transactional
  public void deleteMedia(User user, Long mediaId) throws IOException {
    logger.info("Deleting subtitle media for user: {}, mediaId: {}", user.getId(), mediaId);

    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> {
              logger.error("Media not found for id: {}", mediaId);
              return new IllegalArgumentException("Media not found");
            });

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      logger.error("User {} not authorized to delete media {}", user.getId(), mediaId);
      throw new IllegalArgumentException("Not authorized to delete this media");
    }

    // Don't allow deletion if media is being processed
    if ("PROCESSING".equals(subtitleMedia.getStatus()) || "QUEUED".equals(subtitleMedia.getStatus())) {
      throw new IllegalStateException("Cannot delete media while it is being processed");
    }

    // Delete files from R2
    try {
      if (subtitleMedia.getOriginalPath() != null) {
        cloudflareR2Service.deleteFile(subtitleMedia.getOriginalPath());
        logger.info("Deleted original file from R2: {}", subtitleMedia.getOriginalPath());
      }

      if (subtitleMedia.getProcessedPath() != null) {
        cloudflareR2Service.deleteFile(subtitleMedia.getProcessedPath());
        logger.info("Deleted processed file from R2: {}", subtitleMedia.getProcessedPath());
      }
    } catch (IOException e) {
      logger.warn("Failed to delete some files from R2 for mediaId: {}", mediaId, e);
      // Continue with DB deletion even if R2 deletion fails
    }

    // Delete from database
    subtitleMediaRepository.delete(subtitleMedia);
    logger.info("Successfully deleted subtitle media for user: {}, mediaId: {}", user.getId(), mediaId);
  }

  public SubtitleMedia deleteSingleSubtitle(User user, Long mediaId, String subtitleId) throws IOException {
    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found"));

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized");
    }

    List<SubtitleDTO> subtitles = objectMapper.readValue(
            subtitleMedia.getSubtitlesJson(), new TypeReference<List<SubtitleDTO>>() {});

    boolean removed = subtitles.removeIf(s -> s.getId().equals(subtitleId));
    if (!removed) {
      throw new IllegalArgumentException("Subtitle not found: " + subtitleId);
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMediaRepository.save(subtitleMedia);
    return subtitleMedia;
  }

  public SubtitleMedia replaceAllSubtitles(User user, Long mediaId, List<SubtitleDTO> subtitles) throws IOException {
    SubtitleMedia subtitleMedia = subtitleMediaRepository.findById(mediaId)
            .orElseThrow(() -> new IllegalArgumentException("Media not found"));

    if (!subtitleMedia.getUser().getId().equals(user.getId())) {
      throw new IllegalArgumentException("Not authorized");
    }

    subtitleMedia.setSubtitlesJson(objectMapper.writeValueAsString(subtitles));
    subtitleMediaRepository.save(subtitleMedia);
    return subtitleMedia;
  }
}