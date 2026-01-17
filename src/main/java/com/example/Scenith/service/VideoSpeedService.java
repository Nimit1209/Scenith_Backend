package com.example.Scenith.service;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserProcessingUsage;
import com.example.Scenith.entity.VideoSpeed;
import com.example.Scenith.repository.UserProcessingUsageRepository;
import com.example.Scenith.repository.VideoSpeedRepository;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VideoSpeedService {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedService.class);

    private final VideoSpeedRepository videoSpeedRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final SqsService sqsService;
    private final ObjectMapper objectMapper;
    private final ProcessingEmailHelper emailHelper;
    private final UserProcessingUsageRepository userProcessingUsageRepository;

    @Value("${app.ffmpeg-path}")
    private String ffmpegPath;

    @Value("${app.base-dir}")
    private String baseDir;

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    // Supported video formats
    private static final Set<String> SUPPORTED_VIDEO_FORMATS = new HashSet<>(Arrays.asList(
            "video/mp4",
            "video/quicktime",      // .mov
            "video/x-msvideo",      // .avi
            "video/x-matroska",     // .mkv
            "video/webm",
            "video/mpeg",
            "video/x-flv",
            "application/octet-stream" // Some browsers send this for video files
    ));

    private static final Set<String> SUPPORTED_VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".mov", ".avi", ".mkv", ".webm", ".mpeg", ".mpg", ".flv"
    ));

    /**
     * Validate uploaded video file
     */
    private void validateVideoFile(MultipartFile videoFile) {
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Video file is required");
        }

        String originalFilename = videoFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // Validate file extension
        String fileExtension = originalFilename.toLowerCase();
        int lastDotIndex = fileExtension.lastIndexOf('.');
        if (lastDotIndex > 0) {
            fileExtension = fileExtension.substring(lastDotIndex);
        } else {
            throw new IllegalArgumentException("File must have a valid extension");
        }

        if (!SUPPORTED_VIDEO_EXTENSIONS.contains(fileExtension)) {
            throw new IllegalArgumentException(
                    "Unsupported video format. Supported formats: " +
                            String.join(", ", SUPPORTED_VIDEO_EXTENSIONS)
            );
        }

        // Validate MIME type (soft check)
        String contentType = videoFile.getContentType();
        if (contentType != null && !SUPPORTED_VIDEO_FORMATS.contains(contentType.toLowerCase())) {
            logger.warn("Unrecognized MIME type: {} for file: {}", contentType, originalFilename);
        }

        logger.info("Video file validation passed: {} ({}MB, {})",
                originalFilename,
                videoFile.getSize() / (1024.0 * 1024.0),
                contentType);
    }

    /**
     * Upload video to R2 storage
     */
    @Transactional
    public VideoSpeed uploadVideo(User user, MultipartFile videoFile, Double speed) throws IOException {
        validateVideoFile(videoFile);

        // Validate speed
        if (speed != null && (speed < 0.5 || speed > 15.0)) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 15.0");
        }

        String originalFileName = System.currentTimeMillis() + "_" + sanitizeFilename(videoFile.getOriginalFilename());
        String r2Path = "speed-videos/" + user.getId() + "/" + originalFileName;

        // Upload original video to R2
        cloudflareR2Service.uploadFile(videoFile, r2Path);

        // Create VideoSpeed entity
        VideoSpeed video = new VideoSpeed();
        video.setUser(user);
        video.setOriginalFilePath(r2Path);
        video.setSpeed(speed != null ? speed : 1.0);
        video.setStatus("UPLOADED");
        video.setProgress(0.0);
        video.setCreatedAt(LocalDateTime.now());
        video.setLastModified(LocalDateTime.now());

        return videoSpeedRepository.save(video);
    }

    /**
     * Update speed for an uploaded video
     */
    @Transactional
    public VideoSpeed updateSpeed(Long id, User user, Double speed) {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));

        if (speed == null || speed < 0.5 || speed > 15.0) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 15.0");
        }

        if ("PROCESSING".equals(video.getStatus()) || "PENDING".equals(video.getStatus())) {
            throw new IllegalStateException("Cannot update speed while video is being processed");
        }

        video.setSpeed(speed);
        video.setLastModified(LocalDateTime.now());
        return videoSpeedRepository.save(video);
    }

    /**
     * Initiate export by queueing processing task to SQS
     */
    @Transactional
    public VideoSpeed initiateExport(Long id, User user, String quality) throws IOException {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));

        // Allow export for UPLOADED, FAILED, or COMPLETED videos
        if ("PENDING".equals(video.getStatus()) || "PROCESSING".equals(video.getStatus())) {
            throw new IllegalStateException("Video is already being processed");
        }

        // Validate processing limits (using video duration from metadata if available)
        // Note: For exact duration validation, you'd need to download and probe the video first
        // For now, we'll do basic validation
        validateProcessingLimits(user, quality);

        // Set quality
        String finalQuality = quality != null ? quality : "720p";
        video.setQuality(finalQuality);

        // Reset fields for export
        video.setStatus("PENDING");
        video.setProgress(10.0);
        video.setCdnUrl(null);
        video.setOutputFilePath(null);
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);

        // Queue processing task to SQS
        Map<String, String> taskDetails = new HashMap<>();
        taskDetails.put("videoId", id.toString());
        taskDetails.put("taskType", "VIDEO_SPEED");
        taskDetails.put("userId", user.getId().toString());
        taskDetails.put("originalFilePath", video.getOriginalFilePath());
        taskDetails.put("speed", String.valueOf(video.getSpeed()));
        taskDetails.put("quality", finalQuality);

        String messageBody = objectMapper.writeValueAsString(taskDetails);
        sqsService.sendMessage(messageBody, videoExportQueueUrl);

        logger.info("Queued video speed processing task for videoId={}, userId={}", id, user.getId());

        return video;
    }

    /**
     * Process speed task (called by SQS consumer)
     */
    public void processSpeedTask(Map<String, String> taskDetails) throws IOException, InterruptedException {
        Long videoId = Long.parseLong(taskDetails.get("videoId"));
        String originalFilePath = taskDetails.get("originalFilePath");
        double speed = Double.parseDouble(taskDetails.get("speed"));
        String quality = taskDetails.getOrDefault("quality", "720p");

        VideoSpeed video = videoSpeedRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));

        video.setStatus("PROCESSING");
        video.setProgress(20.0);
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);

        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDir = baseDirPath.resolve("temp/speed-videos/" + videoId).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);

        String outputFileName = "speed_" + System.currentTimeMillis() + ".mp4";
        String outputR2Path = "speed-videos/" + video.getUser().getId() + "/" + outputFileName;
        Path tempOutputPath = tempDir.resolve(outputFileName);
        Path tempInputPath = tempDir.resolve(new File(originalFilePath).getName());

        try {
            // Download original video from R2
            logger.info("Downloading video from R2: {}", originalFilePath);
            cloudflareR2Service.downloadFile(originalFilePath, tempInputPath.toString());

            video.setProgress(30.0);
            videoSpeedRepository.save(video);

            // Get video duration for validation and usage tracking
            double videoDuration = getVideoDuration(tempInputPath.toString());
            logger.info("Video duration: {} seconds", videoDuration);

            // Validate duration against user limits
            validateVideoDuration(video.getUser(), videoDuration);

            video.setProgress(40.0);
            videoSpeedRepository.save(video);

            // Process video with FFmpeg
            logger.info("Processing video with FFmpeg: speed={}, quality={}", speed, quality);
            processVideoWithFFmpeg(tempInputPath.toString(), tempOutputPath.toString(), speed, quality, video);

            video.setProgress(80.0);
            videoSpeedRepository.save(video);

            // Upload processed video to R2
            logger.info("Uploading processed video to R2: {}", outputR2Path);
            cloudflareR2Service.uploadFile(tempOutputPath.toFile(), outputR2Path);

            video.setProgress(90.0);
            videoSpeedRepository.save(video);

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(outputR2Path, 0); // No expiration

            // Update video entity
            video.setOutputFilePath(outputR2Path);
            video.setCdnUrl(cdnUrl);
            video.setStatus("COMPLETED");
            video.setProgress(100.0);
            video.setLastModified(LocalDateTime.now());
            videoSpeedRepository.save(video);

            // Increment usage count
            incrementUsageCount(video.getUser());

            // Send completion email
            emailHelper.sendProcessingCompleteEmail(
                    video.getUser(),
                    ProcessingEmailHelper.ServiceType.VIDEO_SPEED,
                    outputFileName,
                    cdnUrl,
                    videoId
            );

            logger.info("Video processing completed successfully: videoId={}", videoId);

        } catch (Exception e) {
            logger.error("Failed to process video speed task for videoId={}: {}", videoId, e.getMessage(), e);
            video.setStatus("FAILED");
            video.setProgress(0.0);
            video.setLastModified(LocalDateTime.now());
            videoSpeedRepository.save(video);
            throw e;
        } finally {
            cleanUpTempFiles(tempDir);
        }
    }

    /**
     * Get video status
     */
    public VideoSpeed getVideoStatus(Long id, User user) {
        return videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));
    }

    /**
     * Get all videos for a user
     */
    public List<VideoSpeed> getUserVideos(User user) {
        return videoSpeedRepository.findByUser(user);
    }

    /**
     * Process video with FFmpeg (updated with progress tracking)
     */
    private void processVideoWithFFmpeg(String inputPath, String outputPath, double speed,
                                        String quality, VideoSpeed video) throws IOException, InterruptedException {
        File ffmpegFile = new File(ffmpegPath);
        if (!ffmpegFile.exists() || !ffmpegFile.canExecute()) {
            throw new IOException("FFmpeg executable not found or not executable: " + ffmpegPath);
        }

        Map<String, String> qualitySettings = getFFmpegQualitySettings(quality);

        // Build FFmpeg command
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputPath);
        command.add("-filter:v");
        command.add(String.format("setpts=%f*PTS,scale=%s", 1.0 / speed, qualitySettings.get("scale")));
        command.add("-filter:a");
        command.add(String.format("atempo=%f", speed));
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(qualitySettings.get("preset"));
        command.add("-crf");
        command.add(qualitySettings.get("crf"));
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("320k");
        command.add("-y");
        command.add(outputPath);

        logger.info("Executing FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Monitor FFmpeg output
        StringBuilder ffmpegOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegOutput.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);

                // Update progress based on FFmpeg output (optional)
                updateProgressFromFFmpegOutput(line, video);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFmpeg failed with exit code {}: {}", exitCode, ffmpegOutput.toString());
            throw new IOException("FFmpeg process failed with exit code: " + exitCode);
        }

        // Verify output file exists
        if (!Files.exists(Paths.get(outputPath))) {
            throw new IOException("FFmpeg completed but output file not found: " + outputPath);
        }

        logger.info("FFmpeg processing completed successfully");
    }

    /**
     * Update progress based on FFmpeg output (optional enhancement)
     */
    private void updateProgressFromFFmpegOutput(String line, VideoSpeed video) {
        // Parse FFmpeg progress output if needed
        // Example: time=00:01:30.00 -> update progress accordingly
        // This is optional and can be implemented for more granular progress tracking
    }

    /**
     * Get video duration using ffprobe
     */
    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String ffprobePath = ffmpegPath.replace("ffmpeg", "ffprobe");

        List<String> command = Arrays.asList(
                ffprobePath,
                "-i", videoPath,
                "-show_entries", "format=duration",
                "-v", "quiet",
                "-of", "json"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        process.waitFor();

        Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);
        Map<String, Object> format = (Map<String, Object>) result.get("format");

        return Double.parseDouble(format.get("duration").toString());
    }

    /**
     * Validate processing limits (basic version without duration)
     */
    private void validateProcessingLimits(User user, String quality) {
        // Validate quality
        if (quality != null && !user.isQualityAllowed(quality)) {
            throw new IllegalArgumentException(
                    "Quality " + quality + " not allowed. Maximum allowed: " + user.getMaxAllowedQuality()
            );
        }

        // Check monthly processing limit
        int maxPerMonth = user.getMaxVideoProcessingPerMonth();
        if (maxPerMonth > 0) {
            String currentYearMonth = YearMonth.now().toString();
            Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository
                    .findByUserAndServiceTypeAndYearMonth(user, "VIDEO_SPEED", currentYearMonth);

            int currentCount = usageOpt.map(UserProcessingUsage::getProcessCount).orElse(0);
            if (currentCount >= maxPerMonth) {
                throw new IllegalArgumentException(
                        "Monthly processing limit reached (" + maxPerMonth + "). Upgrade your plan for more."
                );
            }
        }
    }

    /**
     * Validate video duration against user limits
     */
    private void validateVideoDuration(User user, double videoDuration) {
        int maxMinutes = user.getMaxVideoLengthMinutes();
        if (maxMinutes > 0 && videoDuration > maxMinutes * 60) {
            throw new IllegalArgumentException(
                    "Video length exceeds maximum allowed (" + maxMinutes + " minutes). Upgrade your plan."
            );
        }
    }

    /**
     * Increment usage count for the user
     */
    private void incrementUsageCount(User user) {
        String currentYearMonth = YearMonth.now().toString();
        Optional<UserProcessingUsage> usageOpt = userProcessingUsageRepository
                .findByUserAndServiceTypeAndYearMonth(user, "VIDEO_SPEED", currentYearMonth);

        UserProcessingUsage usage;
        if (usageOpt.isPresent()) {
            usage = usageOpt.get();
            usage.setProcessCount(usage.getProcessCount() + 1);
        } else {
            usage = new UserProcessingUsage();
            usage.setUser(user);
            usage.setServiceType("VIDEO_SPEED");
            usage.setYearMonth(currentYearMonth);
            usage.setProcessCount(1);
        }

        userProcessingUsageRepository.save(usage);
        logger.info("Incremented usage count for user={}, service=VIDEO_SPEED, yearMonth={}",
                user.getId(), currentYearMonth);
    }

    /**
     * Get FFmpeg quality settings based on resolution
     */
    private Map<String, String> getFFmpegQualitySettings(String quality) {
        Map<String, String> settings = new HashMap<>();

        switch (quality != null ? quality.toLowerCase() : "720p") {
            case "144p":
                settings.put("scale", "-2:144");
                settings.put("crf", "28");
                settings.put("preset", "veryfast");
                break;
            case "240p":
                settings.put("scale", "-2:240");
                settings.put("crf", "27");
                settings.put("preset", "veryfast");
                break;
            case "360p":
                settings.put("scale", "-2:360");
                settings.put("crf", "26");
                settings.put("preset", "fast");
                break;
            case "480p":
                settings.put("scale", "-2:480");
                settings.put("crf", "25");
                settings.put("preset", "fast");
                break;
            case "720p":
                settings.put("scale", "-2:720");
                settings.put("crf", "23");
                settings.put("preset", "medium");
                break;
            case "1080p":
                settings.put("scale", "-2:1080");
                settings.put("crf", "22");
                settings.put("preset", "medium");
                break;
            case "1440p":
            case "2k":
                settings.put("scale", "-2:1440");
                settings.put("crf", "20");
                settings.put("preset", "slow");
                break;
            case "4k":
                settings.put("scale", "-2:2160");
                settings.put("crf", "18");
                settings.put("preset", "slow");
                break;
            default:
                settings.put("scale", "-2:720");
                settings.put("crf", "23");
                settings.put("preset", "medium");
        }

        return settings;
    }

    /**
     * Clean up temporary files
     */
    private void cleanUpTempFiles(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete temp file: {}", p, e);
                        }
                    });
            logger.info("Cleaned up temp directory: {}", tempDir);
        } catch (IOException e) {
            logger.warn("Failed to clean up temp directory: {}", tempDir, e);
        }
    }

    /**
     * Sanitize filename to prevent security issues
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) return "video_" + System.currentTimeMillis() + ".mp4";
        return filename.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}