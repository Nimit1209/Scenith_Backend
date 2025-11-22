package com.example.Scenith.service;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoSpeed;
import com.example.Scenith.repository.VideoSpeedRepository;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoSpeedService {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedService.class);

    private final VideoSpeedRepository videoSpeedRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final SqsService sqsService;
    private final ObjectMapper objectMapper;

    @Value("${app.ffmpeg-path}")
    private String ffmpegPath;

    @Value("${app.base-dir}")
    private String baseDir;

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    public VideoSpeed uploadVideo(User user, MultipartFile videoFile, Double speed) throws IOException {
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
        videoSpeedRepository.save(video);

        return video;
    }

    public VideoSpeed updateSpeed(Long id, User user, Double speed) {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));
        if (speed <= 0 || speed > 5.0) {
            throw new IllegalArgumentException("Speed must be between 0.1 and 5.0");
        }
        video.setSpeed(speed);
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);
        return video;
    }

    public VideoSpeed initiateExport(Long id, User user) throws IOException {
        VideoSpeed video = videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));

        // Allow export for UPLOADED, FAILED, or COMPLETED videos
        if ("PENDING".equals(video.getStatus()) || "PROCESSING".equals(video.getStatus())) {
            throw new IllegalStateException("Video is already being processed");
        }

        // Reset fields for re-export
        video.setStatus("PENDING");
        video.setProgress(10.0);
        video.setCdnUrl(null); // Clear previous CDN URL
        video.setOutputFilePath(null); // Clear previous output file path
        video.setLastModified(LocalDateTime.now());
        videoSpeedRepository.save(video);

        // Queue processing task
        Map<String, String> taskDetails = new HashMap<>();
        taskDetails.put("videoId", id.toString());
        taskDetails.put("originalFilePath", video.getOriginalFilePath());
        taskDetails.put("speed", String.valueOf(video.getSpeed()));
        String messageBody = objectMapper.writeValueAsString(taskDetails);
        sqsService.sendMessage(messageBody, videoExportQueueUrl);

        return video;
    }

    public void processSpeedTask(Map<String, String> taskDetails) throws IOException, InterruptedException {
        Long videoId = Long.parseLong(taskDetails.get("videoId"));
        String originalFilePath = taskDetails.get("originalFilePath");
        double speed = Double.parseDouble(taskDetails.get("speed"));

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
            // Download original video
            cloudflareR2Service.downloadFile(originalFilePath, tempInputPath.toString());

            // Process video with FFmpeg
            processVideoSpeed(tempInputPath.toString(), tempOutputPath.toString(), speed);

            // Upload processed video to R2
            cloudflareR2Service.uploadFile(tempOutputPath.toFile(), outputR2Path);

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(outputR2Path, 0); // No expiration for CDN URL

            // Update video
            video.setOutputFilePath(outputR2Path);
            video.setCdnUrl(cdnUrl);
            video.setStatus("COMPLETED");
            video.setProgress(100.0);
            video.setLastModified(LocalDateTime.now());
            videoSpeedRepository.save(video);
        } catch (Exception e) {
            logger.error("Failed to process video speed task: {}", e.getMessage(), e);
            video.setStatus("FAILED");
            video.setProgress(0.0);
            video.setLastModified(LocalDateTime.now());
            videoSpeedRepository.save(video);
            throw e;
        } finally {
            cleanUpTempFiles(tempDir);
        }
    }

    public VideoSpeed getVideoStatus(Long id, User user) {
        return videoSpeedRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Video not found or unauthorized: " + id));
    }

    public List<VideoSpeed> getUserVideos(User user) {
        return videoSpeedRepository.findByUser(user);
    }

    private void processVideoSpeed(String inputPath, String outputPath, double speed) throws IOException, InterruptedException {
        double speedFactor = 1.0 / speed; // Video speed factor (e.g., 2x speed -> 0.5 * PTS)
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputPath);
        command.add("-filter:v");
        command.add("setpts=" + String.format("%.6f", speedFactor) + "*PTS");
        command.add("-filter:a");
        command.add("atempo=" + String.format("%.6f", speed)); // Audio speed matches video speed
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("fast");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("320k");
        command.add("-y");
        command.add(outputPath);

        logger.info("Executing FFmpeg command for speed adjustment: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg process failed with exit code: " + exitCode);
        }
    }

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
        } catch (IOException e) {
            logger.warn("Failed to clean up temp directory: {}", tempDir, e);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "video_" + System.currentTimeMillis() + ".mp4";
        return filename.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}