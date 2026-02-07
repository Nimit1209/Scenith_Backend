package com.example.Scenith.service;

import com.example.Scenith.dto.SubtitleDTO;
import com.example.Scenith.entity.PodcastClipMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.PodcastClipMediaRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class PodcastClipService {

    private static final Logger logger = LoggerFactory.getLogger(PodcastClipService.class);

    private final JwtUtil jwtUtil;
    private final PodcastClipMediaRepository podcastClipMediaRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final SubtitleService subtitleService;
    private final CloudflareR2Service cloudflareR2Service;
    private final SqsService sqsService;

    @Value("${app.base-dir}")
    private String baseDir;

    @Value("${app.ffmpeg-path}")
    private String ffmpegPath;

    @Value("${app.yt-dlp-path:yt-dlp}")
    private String ytDlpPath;

    @Value("${python.path}")
    private String pythonPath;

    @Value("${app.whisper-script-path:/app/scripts/whisper_transcribe.py}")
    private String whisperScriptPath;

    @Value("${app.background-image-path:classpath:assets/podcast_background.png}")
    private String backgroundImagePath;

    @Value("${sqs.queue.url}")
    private String podcastClipQueueUrl;

    public PodcastClipService(
            JwtUtil jwtUtil,
            PodcastClipMediaRepository podcastClipMediaRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            SubtitleService subtitleService,
            CloudflareR2Service cloudflareR2Service,
            SqsService sqsService) {
        this.jwtUtil = jwtUtil;
        this.podcastClipMediaRepository = podcastClipMediaRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.subtitleService = subtitleService;
        this.cloudflareR2Service = cloudflareR2Service;
        this.sqsService = sqsService;
    }

    public PodcastClipMedia uploadMedia(User user, MultipartFile mediaFile, String youtubeUrl) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        PodcastClipMedia media = new PodcastClipMedia();
        media.setUser(user);
        media.setStatus("UPLOADED");

        Path tempDir = Paths.get(baseDir).resolve("temp/upload/" + user.getId()).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);

        try {
            if (youtubeUrl != null && !youtubeUrl.isEmpty()) {
                if (!youtubeUrl.matches("^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/.+")) {
                    logger.error("Invalid YouTube URL: {}", youtubeUrl);
                    throw new IllegalArgumentException("Invalid YouTube URL");
                }

                // Generate filename
                String originalFileName = "youtube_" + UUID.randomUUID().toString() + ".mp4";
                media.setSourceUrl(youtubeUrl);
                media.setOriginalFileName(originalFileName);

                // Create R2 path
                String r2Path = "podcast_clips/" + user.getId() + "/original/" + originalFileName;
                media.setOriginalR2Path(r2Path);

                // Download YouTube video to temp location FIRST
                Path tempVideoPath = tempDir.resolve(originalFileName);
                logger.info("Downloading YouTube video to temp: {}", tempVideoPath);

                downloadYouTubeVideo(youtubeUrl, tempVideoPath.toString());

                // After downloadYouTubeVideo call, add this verification:
                File downloadedFile = tempVideoPath.toFile();
                logger.info("Checking downloaded file: {} exists={}, size={}",
                        downloadedFile.getAbsolutePath(),
                        downloadedFile.exists(),
                        downloadedFile.exists() ? downloadedFile.length() : 0);

                if (!downloadedFile.exists()) {
                    logger.error("Downloaded file does not exist: {}", downloadedFile.getAbsolutePath());
                    logger.error("Directory contents: {}", Arrays.toString(tempVideoPath.getParent().toFile().list()));
                    throw new IOException("Downloaded file does not exist: " + downloadedFile.getAbsolutePath());
                }

                if (downloadedFile.length() == 0) {
                    logger.error("Downloaded file is empty: {}", downloadedFile.getAbsolutePath());
                    throw new IOException("Downloaded file is empty: " + downloadedFile.getAbsolutePath());
                }

// Validate with ffprobe BEFORE uploading
                try {
                    validateInputFile(downloadedFile);
                    logger.info("File validation passed successfully");
                } catch (Exception e) {
                    logger.error("File validation failed: {}", e.getMessage());
                    throw new IOException("File validation failed: " + e.getMessage(), e);
                }

// Upload to R2 with explicit logging
                logger.info("Uploading file to R2: {} (size: {} bytes)", r2Path, downloadedFile.length());
                cloudflareR2Service.uploadFile(downloadedFile, r2Path);
                logger.info("Upload to R2 completed successfully");

                // Verify upload by checking if file exists in R2
                if (!cloudflareR2Service.fileExists(r2Path)) {
                    logger.error("File was not found in R2 after upload: {}", r2Path);
                    throw new IOException("Upload verification failed - file not found in R2");
                }

                String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 0);
                media.setOriginalCdnUrl(cdnUrl);
                logger.info("✅ YouTube video successfully uploaded. CDN URL: {}", cdnUrl);
            } else if (mediaFile != null && !mediaFile.isEmpty()) {
                String originalFileName = sanitizeFilename(mediaFile.getOriginalFilename());
                String r2Path = "podcast_clips/" + user.getId() + "/original/" + originalFileName;

                // Save multipart file to temp first, then upload to R2
                Path tempFilePath = tempDir.resolve(originalFileName);
                mediaFile.transferTo(tempFilePath.toFile());

                File tempFile = tempFilePath.toFile();
                if (tempFile.length() == 0) {
                    throw new IOException("Uploaded file is empty");
                }

                // Upload to R2
                cloudflareR2Service.uploadFile(tempFile, r2Path);

                // Generate CDN URL
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 0);
                media.setOriginalFileName(originalFileName);
                media.setOriginalR2Path(r2Path);
                media.setOriginalCdnUrl(cdnUrl);

                logger.info("File uploaded successfully. CDN URL: {}", cdnUrl);

            } else {
                throw new IllegalArgumentException("Either a file or YouTube URL must be provided");
            }

            podcastClipMediaRepository.save(media);
            logger.info("Saved metadata for user: {}, media: {}", user.getId(), media.getOriginalFileName());
            return media;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // Cleanup temp files
            cleanUpTempFiles(tempDir);
        }
    }
    public PodcastClipMedia initiateProcessing(User user, Long mediaId) throws IOException {
        logger.info("Initiating podcast clip processing for user: {}, mediaId: {}", user.getId(), mediaId);

        PodcastClipMedia media = podcastClipMediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found"));

        if (!media.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not authorized to process clips for this media");
        }

        if ("PROCESSING".equals(media.getStatus()) || "SUCCESS".equals(media.getStatus())) {
            throw new IllegalStateException("Media is already processed or being processed");
        }

        // Verify file exists in R2
        if (media.getOriginalR2Path() != null && !cloudflareR2Service.fileExists(media.getOriginalR2Path())) {
            logger.error("Original file not found in R2: {}", media.getOriginalR2Path());
            throw new IllegalStateException("Original media file not found in storage");
        }

        media.setStatus("PENDING");
        media.setProgress(10.0);
        media.setLastModified(LocalDateTime.now());
        podcastClipMediaRepository.save(media);

        // Queue processing task with all necessary details
        Map<String, String> taskDetails = new HashMap<>();
        taskDetails.put("mediaId", mediaId.toString());
        taskDetails.put("taskType", "PODCAST_CLIP");
        taskDetails.put("userId", user.getId().toString());
        taskDetails.put("originalR2Path", media.getOriginalR2Path());
        taskDetails.put("sourceUrl", media.getSourceUrl() != null ? media.getSourceUrl() : "");
        taskDetails.put("originalCdnUrl", media.getOriginalCdnUrl() != null ? media.getOriginalCdnUrl() : "");

        String messageBody = objectMapper.writeValueAsString(taskDetails);
        sqsService.sendMessage(messageBody, podcastClipQueueUrl);

        logger.info("Queued processing task for mediaId: {}", mediaId);
        return media;
    }

    public void processClipsTask(Map<String, String> taskDetails) throws IOException, InterruptedException {
        Long mediaId = Long.parseLong(taskDetails.get("mediaId"));
        Long userId = Long.parseLong(taskDetails.get("userId"));
        String originalR2Path = taskDetails.get("originalR2Path");
        String sourceUrl = taskDetails.get("sourceUrl");

        logger.info("Processing podcast clips task for mediaId: {}", mediaId);

        PodcastClipMedia media = podcastClipMediaRepository.findById(mediaId)
                .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        media.setStatus("PROCESSING");
        media.setProgress(20.0);
        media.setLastModified(LocalDateTime.now());
        podcastClipMediaRepository.save(media);

        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDir = baseDirPath.resolve("temp/podcast_clips/" + mediaId).toAbsolutePath().normalize();
        Path processedDir = baseDirPath.resolve("temp/podcast_clips/" + mediaId + "/processed").toAbsolutePath().normalize();

        Files.createDirectories(tempDir);
        Files.createDirectories(processedDir);

        File backgroundImage = null;
        File inputFile = null;

        try {
            // Load background image
            Resource resource = resourceLoader.getResource(backgroundImagePath);
            if (!resource.exists()) {
                throw new IOException("Background image not found: " + backgroundImagePath);
            }
            backgroundImage = tempDir.resolve("background.png").toFile();
            Files.copy(resource.getInputStream(), backgroundImage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Download file from R2 (already uploaded during upload phase)
            String tempInputPathStr = tempDir.resolve(new File(originalR2Path).getName()).toString();
            logger.info("Downloading file from R2: {} to {}", originalR2Path, tempInputPathStr);
            inputFile = cloudflareR2Service.downloadFile(originalR2Path, tempInputPathStr);

            if (!inputFile.exists() || inputFile.length() == 0) {
                throw new IOException("Failed to download file from R2 or file is empty");
            }

            validateInputFile(inputFile);
            media.setProgress(30.0);
            podcastClipMediaRepository.save(media);

            // Continue with transcription and processing...
            List<Map<String, Object>> segments = transcribeAudio(inputFile, mediaId);
            media.setProgress(50.0);
            podcastClipMediaRepository.save(media);

            List<Map<String, Object>> selectedClips = selectViralClips(segments, mediaId);
            media.setProgress(60.0);
            podcastClipMediaRepository.save(media);

            List<Map<String, Object>> clipMetadata = generateClips(inputFile, selectedClips,
                    processedDir.toString(), userId, mediaId, backgroundImage, segments);

            media.setClipsJson(objectMapper.writeValueAsString(clipMetadata));
            media.setStatus("SUCCESS");
            media.setProgress(100.0);
            media.setLastModified(LocalDateTime.now());
            podcastClipMediaRepository.save(media);

            logger.info("Successfully processed clips for mediaId: {}", mediaId);

        } catch (Exception e) {
            logger.error("Failed to process podcast clips task for mediaId {}: {}", mediaId, e.getMessage(), e);
            media.setStatus("FAILED");
            media.setProgress(0.0);
            media.setLastModified(LocalDateTime.now());
            podcastClipMediaRepository.save(media);
            throw e;
        } finally {
            cleanUpTempFiles(tempDir);
            if (backgroundImage != null && backgroundImage.exists()) {
                Files.deleteIfExists(backgroundImage.toPath());
            }
        }
    }

    public List<PodcastClipMedia> getUserPodcastClipMedia(User user) {
        return podcastClipMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private void downloadYouTubeVideo(String youtubeUrl, String outputPath) throws IOException, InterruptedException {
        // Remove .mp4 extension from outputPath for yt-dlp template
        String baseOutputPath = outputPath.replace(".mp4", "");

        List<String> command = Arrays.asList(
                "yt-dlp",
                "-f", "best[height<=720]",
                "--no-playlist",
                "--restrict-filenames",
                "--merge-output-format", "mp4",  // Force MP4 output
                "-o", baseOutputPath + ".%(ext)s",
                youtubeUrl
        );

        logger.info("Executing yt-dlp command: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String currentPath = env.getOrDefault("PATH", "");
        env.put("PATH", "/usr/local/bin:/usr/bin:" + currentPath);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("yt-dlp: {}", line);
            }
        }

        boolean completed = process.waitFor(10, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("YouTube download timed out after 10 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.error("yt-dlp failed with exit code {}: {}", exitCode, output);
            throw new IOException("Failed to download YouTube video (exit code " + exitCode + "): " + output);
        }

        logger.info("yt-dlp completed successfully. Output: {}", output);

        // Find downloaded file with flexible matching
        File outputDir = new File(baseOutputPath).getParentFile();
        if (outputDir == null || !outputDir.exists()) {
            throw new IOException("Output directory does not exist: " + outputDir);
        }

        // Look for any file that starts with the base name
        File[] candidateFiles = outputDir.listFiles((dir, name) ->
                name.startsWith(new File(baseOutputPath).getName()) &&
                        (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
                                name.endsWith(".mp4.mp4")) // Handle double extension case
        );

        if (candidateFiles == null || candidateFiles.length == 0) {
            logger.error("No downloaded files found in: {}", outputDir.getAbsolutePath());
            logger.error("Directory contents: {}", Arrays.toString(outputDir.list()));
            throw new IOException("No downloaded file found. Directory contents: " +
                    Arrays.toString(outputDir.list()));
        }

        File downloadedFile = candidateFiles[0];
        String finalOutputPath = outputPath; // Expected: /path/youtube_xxx.mp4

        logger.info("Found downloaded file: {} (size: {} bytes)",
                downloadedFile.getAbsolutePath(), downloadedFile.length());

        // Handle double extension case
        if (downloadedFile.getName().endsWith(".mp4.mp4")) {
            String correctedPath = downloadedFile.getAbsolutePath().replace(".mp4.mp4", ".mp4");
            Files.move(downloadedFile.toPath(), Paths.get(correctedPath),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            downloadedFile = new File(correctedPath);
            logger.info("Fixed double extension. Renamed to: {}", downloadedFile.getAbsolutePath());
        }

        // If not at exact location, move it
        if (!downloadedFile.getAbsolutePath().equals(finalOutputPath)) {
            Files.move(downloadedFile.toPath(), Paths.get(finalOutputPath),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Moved file to final location: {}", finalOutputPath);
            downloadedFile = new File(finalOutputPath);
        }

        // Verify final file
        if (!downloadedFile.exists()) {
            throw new IOException("Final file does not exist after processing: " + finalOutputPath);
        }

        if (downloadedFile.length() == 0) {
            throw new IOException("Downloaded file is empty: " + finalOutputPath);
        }

        logger.info("YouTube video successfully processed. Final file: {} (size: {} bytes)",
                finalOutputPath, downloadedFile.length());
    }

    private void convertToMp4(File inputFile, String outputPath) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-c", "copy",  // Fast copy without re-encoding
                "-movflags", "+faststart",
                "-y", outputPath
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

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("FFmpeg conversion timed out");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("FFmpeg conversion failed: " + output);
        }
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
            logger.error("FFprobe failed to validate input file {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("FFprobe failed to validate input file: " + output);
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
            if (streams.isEmpty()) {
                logger.error("No streams found in input file: {}", inputFile.getAbsolutePath());
                throw new IOException("No streams found in input file");
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("Failed to parse FFprobe output: " + output);
        }
    }

    private List<Map<String, Object>> transcribeAudio(File inputFile, Long mediaId) throws IOException, InterruptedException {
        String audioPath = inputFile.getAbsolutePath().replace(".mp4", ".mp3");
        List<String> extractAudioCommand = Arrays.asList(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-vn",
                "-acodec", "mp3",
                "-y", audioPath
        );

        ProcessBuilder pb = new ProcessBuilder(extractAudioCommand);
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
            logger.error("FFmpeg failed to extract audio for {}: {}", inputFile.getAbsolutePath(), output);
            throw new IOException("FFmpeg failed to extract audio: " + output);
        }

        List<String> whisperCommand = Arrays.asList(
                pythonPath,
                whisperScriptPath,
                "--input", audioPath,
                "--output_format", "json"
        );

        pb = new ProcessBuilder(whisperCommand);
        pb.redirectErrorStream(true);
        process = pb.start();
        output = new StringBuilder();
        StringBuilder jsonOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean inJson = false;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (line.trim().startsWith("[")) {
                    inJson = true;
                }
                if (inJson) {
                    jsonOutput.append(line).append("\n");
                }
            }
            if (inJson && jsonOutput.toString().trim().endsWith("]")) {
                jsonOutput = new StringBuilder(jsonOutput.toString().trim());
            }
        }
        exitCode = process.waitFor();
        Files.deleteIfExists(Paths.get(audioPath));

        if (exitCode != 0) {
            logger.error("Whisper transcription failed for mediaId {}: {}", mediaId, output);
            throw new IOException("Whisper transcription failed: " + output);
        }

        if (jsonOutput.length() == 0) {
            logger.error("No JSON output from Whisper for mediaId {}: {}", mediaId, output);
            throw new IOException("No JSON output from Whisper");
        }

        try {
            return objectMapper.readValue(jsonOutput.toString(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse Whisper JSON output for mediaId {}: {}", mediaId, jsonOutput);
            throw new IOException("Failed to parse Whisper JSON output: " + jsonOutput, e);
        }
    }

    private List<Map<String, Object>> selectViralClips(List<Map<String, Object>> segments, Long mediaId) {
        List<Map<String, Object>> candidateClips = new ArrayList<>();
        double targetDuration = 35.0;
        int targetClipCount = 15;

        double currentStart = 0.0;
        StringBuilder currentText = new StringBuilder();
        double currentEnd = 0.0;
        int segmentIndex = 0;

        while (segmentIndex < segments.size()) {
            currentText.setLength(0);
            double duration = 0.0;
            int startIndex = segmentIndex;

            while (segmentIndex < segments.size() && duration < targetDuration) {
                Map<String, Object> segment = segments.get(segmentIndex);
                double start = (double) segment.get("start");
                double end = (double) segment.get("end");
                String text = (String) segment.get("text");

                if (segmentIndex == startIndex) {
                    currentStart = start;
                }
                currentEnd = end;
                currentText.append(text).append(" ");
                duration = currentEnd - currentStart;

                segmentIndex++;
                if (duration >= 30.0 && duration <= 40.0) {
                    break;
                }
            }

            if (duration >= 30.0 && duration <= 40.0) {
                double viralityScore = calculateViralityScore(currentText.toString());
                Map<String, Object> clip = new HashMap<>();
                clip.put("id", UUID.randomUUID().toString());
                clip.put("startTime", currentStart);
                clip.put("endTime", currentEnd);
                clip.put("text", currentText.toString().trim());
                clip.put("viralityScore", viralityScore);
                candidateClips.add(clip);
            }
        }

        candidateClips.sort((a, b) -> Double.compare((double) b.get("viralityScore"), (double) a.get("viralityScore")));
        return candidateClips.subList(0, Math.min(targetClipCount, candidateClips.size()));
    }

    private double calculateViralityScore(String text) {
        double score = 0.0;
        text = text.toLowerCase();

        String[] positiveKeywords = {"amazing", "shocking", "incredible", "secret", "why", "how"};
        String[] negativeKeywords = {"worst", "fail", "disaster", "controversial"};
        for (String keyword : positiveKeywords) {
            if (text.contains(keyword)) score += 10.0;
        }
        for (String keyword : negativeKeywords) {
            if (text.contains(keyword)) score += 15.0;
        }

        int sentenceCount = text.split("[.!?]").length;
        if (sentenceCount <= 3) score += 20.0;

        if (text.contains("?")) score += 15.0;

        return Math.min(score, 100.0);
    }

    private List<Map<String, Object>> generateClips(File inputFile, List<Map<String, Object>> clips, String processedDirPath, Long userId, Long mediaId, File backgroundImage, List<Map<String, Object>> segments) throws IOException, InterruptedException {
        List<Map<String, Object>> clipMetadata = new ArrayList<>();
        int clipIndex = 0;

        for (Map<String, Object> clip : clips) {
            String clipId = (String) clip.get("id");
            double startTime = (double) clip.get("startTime");
            double duration = (double) clip.get("endTime") - startTime;
            double viralityScore = (double) clip.get("viralityScore");
            String text = (String) clip.get("text");

            List<SubtitleDTO> clipSubtitles = generateSubtitlesForClip(segments, startTime, startTime + duration, mediaId, clipIndex);

            String outputFileName = "media_" + mediaId + "_clip_" + clipIndex + "_" + clipId.substring(0, 8) + ".mp4";
            String outputFilePath = processedDirPath + File.separator + outputFileName;

            generateClipWithSubtitles(inputFile, backgroundImage, clipSubtitles, startTime, duration, outputFilePath, mediaId, clipIndex);

            // Upload processed clip to R2
            String r2Path = "podcast_clips/" + userId + "/processed/media_" + mediaId + "/" + outputFileName;
            File processedFile = new File(outputFilePath);
            cloudflareR2Service.uploadFile(processedFile, r2Path);
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 0);

            clipIndex++;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("id", clipId);
            metadata.put("startTime", startTime);
            metadata.put("endTime", startTime + duration);
            metadata.put("viralityScore", viralityScore);
            metadata.put("text", text);
            metadata.put("processedPath", r2Path);
            metadata.put("processedCdnUrl", cdnUrl);
            clipMetadata.add(metadata);
        }

        return clipMetadata;
    }

    private List<SubtitleDTO> generateSubtitlesForClip(List<Map<String, Object>> allSegments, double clipStart, double clipEnd, Long mediaId, int clipIndex) {
        List<SubtitleDTO> clipSubtitles = new ArrayList<>();
        int maxWordsPerSubtitle = 4;

        for (Map<String, Object> segment : allSegments) {
            double segStart = (double) segment.get("start");
            double segEnd = (double) segment.get("end");
            String segText = (String) segment.get("text");

            if (segEnd > clipStart && segStart < clipEnd) {
                if (segText == null || segText.trim().isEmpty()) {
                    continue;
                }

                String[] words = segText.trim().split("\\s+");
                if (words.length == 0) {
                    continue;
                }

                double segmentDuration = segEnd - segStart;
                double timePerWord = segmentDuration / words.length;

                for (int i = 0; i < words.length; i += maxWordsPerSubtitle) {
                    int endIdx = Math.min(i + maxWordsPerSubtitle, words.length);
                    String chunkText = String.join(" ", Arrays.copyOfRange(words, i, endIdx));

                    double chunkStart = segStart + (i * timePerWord);
                    double chunkEnd = segStart + (endIdx * timePerWord);

                    double relativeStart = Math.max(0, chunkStart - clipStart);
                    double relativeEnd = Math.min(clipEnd - clipStart, chunkEnd - clipStart);

                    if (relativeEnd > relativeStart && !chunkText.trim().isEmpty()) {
                        SubtitleDTO subtitle = new SubtitleDTO();
                        subtitle.setId(UUID.randomUUID().toString());
                        subtitle.setTimelineStartTime(relativeStart);
                        subtitle.setTimelineEndTime(relativeEnd);
                        subtitle.setText(chunkText.trim());
                        subtitle.setFontFamily("Arial");
                        subtitle.setFontColor("#FFFFFF");
                        subtitle.setBackgroundColor("#000000");
                        subtitle.setBackgroundOpacity(0.85);
                        subtitle.setPositionX(0);
                        subtitle.setPositionY(450);
                        subtitle.setAlignment("center");
                        subtitle.setScale(1.2);
                        subtitle.setBackgroundH(40);
                        subtitle.setBackgroundW(40);
                        subtitle.setBackgroundBorderRadius(15);
                        subtitle.setTextBorderColor("#000000");
                        subtitle.setTextBorderWidth(2);
                        subtitle.setTextBorderOpacity(1.0);
                        clipSubtitles.add(subtitle);
                    }
                }
            }
        }

        logger.debug("Generated {} subtitles for clip {} ({}s to {}s)", clipSubtitles.size(), clipIndex, clipStart, clipEnd);
        return clipSubtitles;
    }

    private void generateClipWithSubtitles(File inputFile, File backgroundImage, List<SubtitleDTO> subtitles, double startTime, double duration, String outputPath, Long mediaId, int clipIndex) throws IOException, InterruptedException {
        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDirPath = baseDirPath.resolve("temp/podcast_clips/" + mediaId + "/subtitle_temp_" + clipIndex);
        Files.createDirectories(tempDirPath);

        String baseVideoPath = tempDirPath.resolve("base_" + clipIndex + ".mp4").toString();
        List<String> baseCommand = new ArrayList<>();
        baseCommand.add(ffmpegPath);
        baseCommand.add("-loop");
        baseCommand.add("1");
        baseCommand.add("-i");
        baseCommand.add(backgroundImage.getAbsolutePath());
        baseCommand.add("-ss");
        baseCommand.add(String.valueOf(startTime));
        baseCommand.add("-t");
        baseCommand.add(String.valueOf(duration));
        baseCommand.add("-i");
        baseCommand.add(inputFile.getAbsolutePath());
        baseCommand.add("-filter_complex");
        baseCommand.add("[0:v]scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,trim=duration=" + duration + ",setpts=PTS-STARTPTS[bg];" +
                "[1:v]setpts=PTS-STARTPTS,scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black@0[fg];" +
                "[bg][fg]overlay=(W-w)/2:(H-h)/2[vout];" +
                "[1:a]atrim=start=0:end=" + duration + ",asetpts=PTS-STARTPTS[aout]");
        baseCommand.add("-map");
        baseCommand.add("[vout]");
        baseCommand.add("-map");
        baseCommand.add("[aout]");
        baseCommand.add("-c:v");
        baseCommand.add("libx264");
        baseCommand.add("-preset");
        baseCommand.add("ultrafast");
        baseCommand.add("-crf");
        baseCommand.add("23");
        baseCommand.add("-c:a");
        baseCommand.add("aac");
        baseCommand.add("-b:a");
        baseCommand.add("192k");
        baseCommand.add("-y");
        baseCommand.add(baseVideoPath);

        logger.debug("Creating base video without subtitles: {}", String.join(" ", baseCommand));
        executeSimpleFFmpegCommand(baseCommand);

        if (!subtitles.isEmpty()) {
            File currentVideo = new File(baseVideoPath);
            int batchSize = 3;

            for (int i = 0; i < subtitles.size(); i += batchSize) {
                int endIdx = Math.min(i + batchSize, subtitles.size());
                List<SubtitleDTO> batch = subtitles.subList(i, endIdx);

                String nextVideoPath = tempDirPath.resolve("sub_batch_" + clipIndex + "_" + i + ".mp4").toString();
                overlaySubtitleBatch(currentVideo, batch, nextVideoPath, duration, mediaId, clipIndex, tempDirPath.toFile());

                if (!currentVideo.getAbsolutePath().equals(baseVideoPath)) {
                    Files.deleteIfExists(currentVideo.toPath());
                }

                currentVideo = new File(nextVideoPath);
            }

            Files.move(currentVideo.toPath(), Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(Paths.get(baseVideoPath));
        } else {
            Files.move(Paths.get(baseVideoPath), Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        cleanUpTempFiles(tempDirPath);
    }

    private void overlaySubtitleBatch(File inputVideo, List<SubtitleDTO> subtitles, String outputPath, double duration, Long mediaId, int clipIndex, File tempDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputVideo.getAbsolutePath());

        List<File> subtitlePngs = new ArrayList<>();
        StringBuilder filterComplex = new StringBuilder();

        for (int i = 0; i < subtitles.size(); i++) {
            SubtitleDTO subtitle = subtitles.get(i);

            File subtitlePng = new File(tempDir, "sub_" + clipIndex + "_" + subtitle.getId() + ".png");
            subtitleService.generateTextPng(subtitle, subtitlePng, 1080, 1920);
            subtitlePngs.add(subtitlePng);

            command.add("-loop");
            command.add("1");
            command.add("-i");
            command.add(subtitlePng.getAbsolutePath());
        }

        String lastOutput = "0:v";
        for (int i = 0; i < subtitles.size(); i++) {
            SubtitleDTO subtitle = subtitles.get(i);
            int inputIdx = i + 1;

            double segStart = subtitle.getTimelineStartTime();
            double segEnd = subtitle.getTimelineEndTime();

            String xExpr = String.format("(W-w)/2+%d", subtitle.getPositionX() != null ? subtitle.getPositionX() : 0);
            String yExpr = String.format("(H-h)/2+%d", subtitle.getPositionY() != null ? subtitle.getPositionY() : 0);

            if (i == subtitles.size() - 1) {
                filterComplex.append("[").append(lastOutput).append("][").append(inputIdx).append(":v]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("'");
                filterComplex.append(":enable='between(t,").append(String.format("%.6f", segStart)).append(",").append(String.format("%.6f", segEnd)).append(")'");
            } else {
                filterComplex.append("[").append(lastOutput).append("][").append(inputIdx).append(":v]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("'");
                filterComplex.append(":enable='between(t,").append(String.format("%.6f", segStart)).append(",").append(String.format("%.6f", segEnd)).append(")'");
                filterComplex.append("[tmp").append(i).append("];");
                lastOutput = "tmp" + i;
            }
        }

        command.add("-filter_complex");
        command.add(filterComplex.toString());
        command.add("-map");
        command.add("0:a");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        command.add("-crf");
        command.add("23");
        command.add("-c:a");
        command.add("copy");
        command.add("-t");
        command.add(String.valueOf(duration));
        command.add("-y");
        command.add(outputPath);

        logger.debug("Overlaying subtitle batch: {}", String.join(" ", command));
        executeSimpleFFmpegCommand(command);

        for (File png : subtitlePngs) {
            Files.deleteIfExists(png.toPath());
        }
    }

    private void executeSimpleFFmpegCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);
            }
        }

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg process timed out: " + output.toString());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg failed with exit code " + exitCode + ": " + output.toString());
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