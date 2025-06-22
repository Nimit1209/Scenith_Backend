package com.example.Scenith.service;

import com.example.Scenith.developer.entity.GlobalElement;
import com.example.Scenith.developer.repository.GlobalElementRepository;
import com.example.Scenith.dto.*;
import com.example.Scenith.entity.Element;
import com.example.Scenith.entity.Project;
import com.example.Scenith.entity.User;
import com.example.Scenith.exception.SessionNotFoundException;
import com.example.Scenith.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class VideoEditingService {
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final GlobalElementRepository globalElementRepository;
    private final CloudflareR2Service cloudflareR2Service; // Updated to CloudflareR2Service

    private static final Logger logger = LoggerFactory.getLogger(VideoEditingService.class);

    @Value("${ffmpeg.path:/usr/local/bin/ffmpeg}")
    private String ffmpegPath;
    @Value("${app.base-dir:/tmp}")
    private String baseDir;
    @Value("${app.global-elements-dir:elements/}")
    private String globalElementsDirectory;

    @Value("${ffprobe.path:/usr/bin/ffprobe}")
    private String ffprobePath;

    public VideoEditingService(
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            GlobalElementRepository globalElementRepository,
            CloudflareR2Service cloudflareR2Service // Updated to CloudflareR2Service
    ) {
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.globalElementRepository = globalElementRepository;
        this.cloudflareR2Service = cloudflareR2Service; // Updated
    }

        // NEW: Helper method to round doubles to three decimal places
        private double roundToThreeDecimals(Double value) {
            if (value == null) return 0.0;
            DecimalFormat df = new DecimalFormat("#.###");
            return Double.parseDouble(df.format(value));
        }

        // METHODS TO ADD THE AUDIO, VIDEO AND IMAGE
        // Moved from Project entity: Video handling methods
        public List<Map<String, String>> getVideos(Project project) throws JsonProcessingException {
            if (project.getVideosJson() == null || project.getVideosJson().isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(project.getVideosJson(), new TypeReference<List<Map<String, String>>>() {
            });
        }

        // Updated to include audioPath for tracking extracted audio
        public void addVideo(Project project, String videoPath, String videoFileName, String audioPath) throws JsonProcessingException {
            List<Map<String, String>> videos = getVideos(project);
            Map<String, String> videoData = new HashMap<>();
            videoData.put("videoPath", videoPath);
            videoData.put("videoFileName", videoFileName);
            if (audioPath != null) {
                videoData.put("audioPath", audioPath); // Store the audio path if provided
            }
            videos.add(videoData);
            project.setVideosJson(objectMapper.writeValueAsString(videos));
        }

        // Overloaded method to maintain compatibility with existing calls
        public void addVideo(Project project, String videoPath, String videoFileName) throws JsonProcessingException {
            addVideo(project, videoPath, videoFileName, null); // Call with null audioPath for backward compatibility
        }

        // Moved from Project entity: Image handling methods
        public List<Map<String, String>> getImages(Project project) throws JsonProcessingException {
            if (project.getImagesJson() == null || project.getImagesJson().isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(project.getImagesJson(), new TypeReference<List<Map<String, String>>>() {
            });
        }

        public void addImage(Project project, String imagePath, String imageFileName) throws JsonProcessingException {
            List<Map<String, String>> images = getImages(project);
            Map<String, String> imageData = new HashMap<>();
            imageData.put("imagePath", imagePath);
            imageData.put("imageFileName", imageFileName);
            images.add(imageData);
            project.setImagesJson(objectMapper.writeValueAsString(images));
        }

        // Moved from Project entity: Audio handling methods
        public List<Map<String, String>> getAudio(Project project) throws JsonProcessingException {
            if (project.getAudioJson() == null || project.getAudioJson().isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(project.getAudioJson(), new TypeReference<List<Map<String, String>>>() {
            });
        }

        public void addAudio(Project project, String audioPath, String audioFileName, String waveformJsonPath) throws JsonProcessingException {
            List<Map<String, String>> audioFiles = getAudio(project);
            Map<String, String> audioData = new HashMap<>();
            audioData.put("audioPath", audioPath);
            audioData.put("audioFileName", audioFileName);
            if (waveformJsonPath != null) {
                audioData.put("waveformJsonPath", waveformJsonPath);
            }
            audioFiles.add(audioData);
            project.setAudioJson(objectMapper.writeValueAsString(audioFiles));
        }

        public void addAudio(Project project, String audioPath, String audioFileName) throws JsonProcessingException {
            addAudio(project, audioPath, audioFileName, null);
        }

        // Get extracted audio metadata from project
        public List<Map<String, String>> getExtractedAudio(Project project) throws JsonProcessingException {
            if (project.getExtractedAudioJson() == null || project.getExtractedAudioJson().isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(project.getExtractedAudioJson(), new TypeReference<List<Map<String, String>>>() {});
        }

        public void addExtractedAudio(Project project, String audioPath, String audioFileName, String sourceVideoPath, String waveformJsonPath) throws JsonProcessingException {
            List<Map<String, String>> extractedAudio = getExtractedAudio(project);
            Map<String, String> audioData = new HashMap<>();
            audioData.put("audioPath", audioPath);
            audioData.put("audioFileName", audioFileName);
            audioData.put("sourceVideoPath", sourceVideoPath);
            if (waveformJsonPath != null) {
                audioData.put("waveformJsonPath", waveformJsonPath);
            }
            extractedAudio.add(audioData);
            project.setExtractedAudioJson(objectMapper.writeValueAsString(extractedAudio));
        }

        public void addExtractedAudio(Project project, String audioPath, String audioFileName, String sourceVideoPath) throws JsonProcessingException {
            addExtractedAudio(project, audioPath, audioFileName, sourceVideoPath, null);
        }

        public Project createProject(User user, String name, Integer width, Integer height, Float fps) throws JsonProcessingException {
            Project project = new Project();
            project.setUser(user);
            project.setName(name);
            project.setStatus("DRAFT");
            project.setLastModified(LocalDateTime.now());
            project.setWidth(width != null ? width : 1920); // Default: 1920
            project.setHeight(height != null ? height : 1080); // Default: 1080
            project.setFps(fps != null ? fps : 25.0f);
            project.setTimelineState(objectMapper.writeValueAsString(new TimelineState()));
            return projectRepository.save(project);
        }

    public String startEditingSession(User user, Long projectId) throws JsonProcessingException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String sessionId = UUID.randomUUID().toString();
        TimelineState timelineState;

        if (project.getTimelineState() != null && !project.getTimelineState().isEmpty()) {
            timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        } else {
            timelineState = new TimelineState();
            timelineState.setCanvasWidth(project.getWidth() != null ? project.getWidth() : 1920);
            timelineState.setCanvasHeight(project.getHeight() != null ? project.getHeight() : 1080);
            project.setTimelineState(objectMapper.writeValueAsString(timelineState));
        }

        project.setEditSession(sessionId);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);

        logger.info("Started editing session for projectId: {}, sessionId: {}", projectId, sessionId);
        return sessionId;
    }
    public void saveProject(String sessionId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
        logger.info("Project saved for sessionId: {}", sessionId);
    }

    public void saveForUndoRedo(Long projectId, String sessionId, String timelineStateJson) {
        Project project = getProjectBySession(sessionId);
        if (!project.getId().equals(projectId)) {
            throw new IllegalArgumentException("Session does not match project ID: " + projectId);
        }
        project.setTimelineState(timelineStateJson);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
        logger.info("Saved timeline state for undo/redo, projectId: {}, sessionId: {}", projectId, sessionId);
    }

    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupExpiredSessions() {
        LocalDateTime expiryTime = LocalDateTime.now().minusHours(1);
        projectRepository.findAll().stream()
                .filter(project -> project.getEditSession() != null && project.getLastModified().isBefore(expiryTime))
                .forEach(project -> {
                    project.setEditSession(null);
                    projectRepository.save(project);
                    logger.info("Cleared expired session for projectId: {}", project.getId());
                });
        logger.info("Cleaned up expired sessions before: {}", expiryTime);
    }

    private Project getProjectBySession(String sessionId) {
        return projectRepository.findByEditSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Edit session not found: " + sessionId));
    }

    public Project uploadVideoToProject(User user, Long projectId, MultipartFile[] videoFiles, String[] videoFileNames) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> videoList = getVideos(project);

        for (int i = 0; i < videoFiles.length; i++) {
            MultipartFile videoFile = videoFiles[i];
            if (videoFile.isEmpty()) {
                logger.error("Empty video file uploaded for projectId={}", projectId);
                throw new IOException("Uploaded video file is empty");
            }

            String originalFileName = videoFile.getOriginalFilename();
            String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
            String uniqueFileName = (videoFileNames != null && i < videoFileNames.length && videoFileNames[i] != null)
                    ? videoFileNames[i]
                    : sanitizedFileName;

            String r2Path = "videos/projects/" + projectId + "/" + uniqueFileName;
            logger.info("Uploading video to R2: r2Path={}, projectId={}", r2Path, projectId);

            String uploadedPath = cloudflareR2Service.uploadFile(videoFile, r2Path);

            // Wait for file to be available in R2
            int maxRetries = 15; // Increase retries for robustness
            int attempt = 0;
            boolean fileExists = false;
            boolean cdnAvailable = false;
            String cdnUrl = null;

            while (attempt < maxRetries) {
                // Check if file exists in R2
                if (cloudflareR2Service.fileExists(r2Path)) {
                    fileExists = true;
                    // Generate URLs and check CDN availability
                    Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);
                    cdnUrl = urls.get("cdnUrl");
                    if (cloudflareR2Service.isCdnUrlAvailable(cdnUrl)) {
                        cdnAvailable = true;
                        break;
                    }
                }
                attempt++;
                logger.warn("Video not found or CDN not available on attempt {}/{}: r2Path={}, cdnUrl={}, projectId={}", attempt, maxRetries, r2Path, cdnUrl, projectId);
                try {
                    Thread.sleep(1000 * (long) Math.pow(2, attempt)); // Exponential backoff
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for R2 file: " + r2Path, e);
                }
            }

            if (!fileExists) {
                logger.error("Uploaded video not found in R2 after {} retries: r2Path={}, projectId={}", maxRetries, r2Path, projectId);
                throw new IOException("Failed to verify uploaded video in R2: " + r2Path);
            }
            if (!cdnAvailable) {
                logger.error("CDN URL not available after {} retries: r2Path={}, cdnUrl={}, projectId={}", maxRetries, r2Path, cdnUrl, projectId);
                throw new IOException("Failed to verify CDN availability for video: " + r2Path);
            }

            Map<String, String> videoData = new HashMap<>();
            videoData.put("videoPath", r2Path);
            videoData.put("videoFileName", uniqueFileName);
            videoData.put("originalFileName", originalFileName);

            // Include URLs in metadata
            Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);
            videoData.put("cdnUrl", urls.get("cdnUrl"));
            videoData.put("presignedUrl", urls.get("presignedUrl"));

            videoList.add(videoData);
        }

        String videosJsonStr = objectMapper.writeValueAsString(videoList);
        project.setVideosJson(videosJsonStr);
        project.setLastModified(LocalDateTime.now());
        Project savedProject = projectRepository.saveAndFlush(project);

        logger.info("Saved videosJson for projectId={}: {}", projectId, videosJsonStr);
        return savedProject;
    }
    public void addVideoToTimeline(
            String sessionId,
            String videoPath,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Double startTime,
            Double endTime,
            boolean createAudioSegment,
            Double speed,
            Double rotation
    ) throws IOException, InterruptedException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        // Normalize and resolve videoPath
        String filename = videoPath.contains("/") ? videoPath.substring(videoPath.lastIndexOf("/") + 1) : videoPath;
        String normalizedVideoPath = filename.toLowerCase().replaceAll("[^a-zA-Z0-9.-]", "_");
        logger.debug("Extracted filename={} from videoPath={}, normalized to={}", filename, videoPath, normalizedVideoPath);

        String r2Path = null;
        String accessUrl = null;

        // Enhanced metadata resolution with retry logic
        int maxMetadataRetries = 15;
        int metadataAttempt = 0;
        while (metadataAttempt < maxMetadataRetries) {
            final int currentAttempt = metadataAttempt;
            try {
                if (videoPath.startsWith("videos/projects/")) {
                    r2Path = videoPath;
                } else {
                    // Refresh project data to get latest video metadata
                    project = projectRepository.findById(project.getId())
                            .orElseThrow(() -> new RuntimeException("Project not found"));

                    List<Map<String, String>> videos = getVideos(project);
                    logger.debug("Available videos in project {}: {}", project.getId(),
                            videos.stream().map(v -> v.get("videoFileName")).collect(Collectors.toList()));

                    Map<String, String> targetVideo = videos.stream()
                            .filter(video -> {
                                String videoFileName = video.get("videoFileName").toLowerCase();
                                String videoPathLower = video.get("videoPath").toLowerCase();
                                String cdnUrl = video.get("cdnUrl") != null ? video.get("cdnUrl").toLowerCase() : "";
                                String presignedUrl = video.get("presignedUrl") != null ? video.get("presignedUrl").toLowerCase() : "";
                                String originalFileName = video.get("originalFileName") != null ? video.get("originalFileName").toLowerCase() : "";
                                return videoFileName.equals(normalizedVideoPath) ||
                                        videoPathLower.endsWith("/" + normalizedVideoPath) ||
                                        cdnUrl.contains(normalizedVideoPath) ||
                                        presignedUrl.contains(normalizedVideoPath) ||
                                        videoFileName.equals(filename) ||
                                        videoPathLower.contains(filename.toLowerCase()) ||
                                        originalFileName.equals(filename.toLowerCase());
                            })
                            .findFirst()
                            .orElseThrow(() -> new IOException("Video file not found in project metadata: " + videoPath + ", attempt=" + (currentAttempt + 1)));
                    r2Path = targetVideo.get("videoPath");
                    accessUrl = targetVideo.get("presignedUrl");
                }
                break;
            } catch (IOException e) {
                metadataAttempt++;
                if (metadataAttempt >= maxMetadataRetries) {
                    logger.error("Failed to find video metadata after {} retries: videoPath={}, normalizedVideoPath={}, filename={}, projectId={}",
                            maxMetadataRetries, videoPath, normalizedVideoPath, filename, project.getId());
                    throw e;
                }
                logger.warn("Video metadata not found on attempt {}/{}: videoPath={}. Retrying...", metadataAttempt, maxMetadataRetries, videoPath);
                Thread.sleep(Math.min(1000 * (long) Math.pow(2, metadataAttempt), 5000));
            }
        }

        if (r2Path == null) {
            logger.error("Failed to resolve R2 path for videoPath={}, projectId={}", videoPath, project.getId());
            throw new IOException("Unable to resolve video path: " + videoPath);
        }

        logger.info("Resolved videoPath={} to R2 path={} for projectId={}", videoPath, r2Path, project.getId());

        // Enhanced file availability check
        logger.info("Checking file availability in R2: {}", r2Path);
        if (!cloudflareR2Service.waitForFileAvailability(r2Path, 20, 500)) {
            logger.error("Video file not available in R2 after waiting: r2Path={}, projectId={}", r2Path, project.getId());
            throw new IOException("Video file not available in R2: " + r2Path);
        }

        // Generate fresh URLs if not available
        if (accessUrl == null) {
            try {
                Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);
                accessUrl = urls.get("presignedUrl");
                logger.info("Generated fresh presigned URL for R2 path: {}", r2Path);
            } catch (Exception e) {
                logger.error("Failed to generate URLs for R2 path: {}", r2Path, e);
                throw new IOException("Failed to generate access URL for video: " + r2Path, e);
            }
        }

        // Enhanced local file handling
        String localFileName = project.getId() + "_" + System.currentTimeMillis() + "_" + new File(r2Path).getName();
        String localRelativePath = "temp/videos/" + localFileName;
        File localFile = new File(baseDir, localRelativePath);

        logger.info("Downloading video from R2: {} to localPath={}", r2Path, localFile.getAbsolutePath());
        try {
            // Ensure parent directory exists
            File parentDir = localFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
            }

            // Use enhanced download with retry
            localFile = cloudflareR2Service.downloadFileWithRetry(r2Path, localFile.getAbsolutePath(), 3);

            // Validate downloaded file
            if (!localFile.exists() || !localFile.isFile() || localFile.length() == 0) {
                logger.error("Downloaded file is invalid: localPath={}, exists={}, isFile={}, size={}",
                        localFile.getAbsolutePath(), localFile.exists(), localFile.isFile(), localFile.length());
                throw new IOException("Downloaded video file is invalid: " + localFile.getAbsolutePath());
            }

            logger.info("Successfully downloaded video to: {} (size: {} bytes)", localFile.getAbsolutePath(), localFile.length());
        } catch (IOException e) {
            logger.error("Failed to download video from R2: r2Path={}, error={}", r2Path, e.getMessage(), e);
            throw new IOException("Failed to download video from R2: " + r2Path, e);
        }

        try {
            // Get video duration with proper error handling
            double fullDuration;
            try {
                fullDuration = getVideoDuration(r2Path);
                logger.info("Video duration for {}: {} seconds for projectId={}", r2Path, fullDuration, project.getId());
            } catch (Exception e) {
                logger.error("Failed to get video duration for {}: {}", r2Path, e.getMessage());
                throw new IOException("Failed to get video duration: " + r2Path, e);
            }

            // Validate inputs with better error messages
            layer = layer != null ? layer : 0;
            timelineStartTime = timelineStartTime != null ? roundToThreeDecimals(timelineStartTime) : getLastSegmentEndTime(timelineState, layer);
            startTime = startTime != null ? roundToThreeDecimals(startTime) : 0.0;
            endTime = endTime != null ? roundToThreeDecimals(endTime) : fullDuration;

            double clipDuration = endTime - startTime;
            timelineEndTime = timelineEndTime != null ? roundToThreeDecimals(timelineEndTime) :
                    roundToThreeDecimals(timelineStartTime + (clipDuration / (speed != null ? speed : 1.0)));

            // Validate timeline position
            if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
                logger.warn("Timeline position overlap at layer={} for projectId={}, timelineStart={}, timelineEnd={}",
                        layer, project.getId(), timelineStartTime, timelineEndTime);
                throw new IOException("Timeline position overlaps with an existing segment in layer " + layer);
            }

            // Validate time bounds
            if (startTime < 0 || endTime > fullDuration || startTime >= endTime) {
                logger.warn("Invalid time bounds: startTime={}, endTime={}, fullDuration={} for projectId={}",
                        startTime, endTime, fullDuration, project.getId());
                throw new IOException(String.format("Invalid time bounds: startTime=%.3f, endTime=%.3f, duration=%.3f",
                        startTime, endTime, fullDuration));
            }

            // FIXED: Handle audio extraction BEFORE creating video segment
            // Handle audio extraction BEFORE creating video segment
            String audioPath = null;
            String audioFileName = null;
            AudioSegment audioSegment = null;

            if (createAudioSegment) {
                try {
                    // Generate audio filename
                    String videoFileName = new File(r2Path).getName();
                    String baseVideoName = videoFileName.contains(".") ? videoFileName.substring(0, videoFileName.lastIndexOf(".")) : videoFileName;
                    String baseAudioFileName = baseVideoName.replaceAll("[^a-zA-Z0-9_-]", "_");
                    String audioR2Path = "audio/projects/" + project.getId() + "/extracted/extracted_" + baseAudioFileName;

                    logger.info("Processing audio extraction for video: {} -> audio: {} for projectId={}",
                            videoFileName, baseAudioFileName, project.getId());

                    // Refresh project to get latest extracted audio metadata
                    project = projectRepository.findById(project.getId())
                            .orElseThrow(() -> new RuntimeException("Project not found"));

                    // Ensure r2Path is final for use in lambda
                    final String finalR2Path = r2Path;

                    List<Map<String, String>> extractedAudio = getExtractedAudio(project);
                    Map<String, String> existingAudio = extractedAudio.stream()
                            .filter(audio -> audio.get("sourceVideoPath").equals(finalR2Path))
                            .findFirst()
                            .orElse(null);

                    String waveformJsonPath = null;
                    if (existingAudio != null) {
                        logger.info("Reusing existing audio: {} for projectId={}", existingAudio.get("audioPath"), project.getId());
                        audioPath = existingAudio.get("audioPath");
                        audioFileName = existingAudio.get("audioFileName");
                        waveformJsonPath = existingAudio.get("waveformJsonPath");
                    } else {
                        logger.info("Extracting new audio from video: {} for projectId={}", r2Path, project.getId());
                        Map<String, String> extractionResult = extractAudioFromVideoWithRetry(r2Path, project.getId(), baseAudioFileName);
                        audioPath = extractionResult.get("audioPath");
                        audioFileName = extractionResult.get("audioFileName");
                        waveformJsonPath = extractionResult.get("waveformJsonPath");

                        if (audioPath != null) {
                            logger.info("Audio extraction completed successfully: {} for projectId={}", audioPath, project.getId());
                            if (!cloudflareR2Service.waitForFileAvailability(audioPath, 20, 500)) {
                                logger.error("Extracted audio file not available in R2: audioPath={}, projectId={}", audioPath, project.getId());
                                throw new IOException("Extracted audio file not available in R2: " + audioPath);
                            }
                            addExtractedAudio(project, audioPath, audioFileName, r2Path, waveformJsonPath);
                        } else {
                            logger.info("No audio extracted for video: {}, projectId={}", r2Path, project.getId());
                        }
                    }

                    // Update video metadata with or without audio
                    updateVideoMetadata(project, r2Path, videoFileName, audioPath);
                    projectRepository.saveAndFlush(project);

                    // Create audio segment only if audio extraction was successful
                    if (audioPath != null && waveformJsonPath != null) {
                        audioSegment = createAudioSegment(audioPath, waveformJsonPath, startTime, endTime,
                                timelineStartTime, timelineEndTime, timelineState);
                        logger.info("Audio segment created successfully for projectId={}", project.getId());
                    } else {
                        logger.info("Skipping audio segment creation as no audio was extracted for projectId={}", project.getId());
                    }

                } catch (Exception e) {
                    logger.error("Audio extraction failed for projectId={}: {}", project.getId(), e.getMessage(), e);
                    // Update video metadata without audio and continue
                    updateVideoMetadata(project, r2Path, new File(r2Path).getName(), null);
                    projectRepository.saveAndFlush(project);
                }
            }
            // Create video segment
            VideoSegment segment = new VideoSegment();
            segment.setSourceVideoPath(r2Path);
            segment.setStartTime(startTime);
            segment.setEndTime(endTime);
            segment.setPositionX(0);
            segment.setPositionY(0);
            segment.setScale(1.0);
            segment.setOpacity(1.0);
            segment.setLayer(layer);
            segment.setTimelineStartTime(timelineStartTime);
            segment.setTimelineEndTime(timelineEndTime);
            segment.setCropB(0.0);
            segment.setCropL(0.0);
            segment.setCropR(0.0);
            segment.setCropT(0.0);
            segment.setSpeed(speed != null ? speed : 1.0);
            segment.setRotation(rotation != null ? rotation : 0.0);

            // Link audio segment to video segment
            if (audioSegment != null) {
                segment.setAudioId(audioSegment.getId());
                timelineState.getAudioSegments().add(audioSegment);
                logger.info("Audio segment linked to video segment for projectId={}", project.getId());
            }

            timelineState.getSegments().add(segment);
            saveTimelineStateNewTransaction(sessionId, timelineState);
            logger.info("Successfully added video segment to timeline: projectId={}, r2Path={}, layer={}, timelineStart={}, timelineEnd={}, hasAudio={}",
                    project.getId(), r2Path, layer, timelineStartTime, timelineEndTime, audioSegment != null);

        } finally {
            // Cleanup temporary file
            try {
                if (localFile != null && localFile.exists()) {
                    Files.delete(localFile.toPath());
                    logger.debug("Deleted temporary video file: {}", localFile.getAbsolutePath());
                }
            } catch (IOException e) {
                logger.warn("Failed to delete temporary video file: {}, error: {}",
                        localFile != null ? localFile.getAbsolutePath() : "null", e.getMessage());
            }
        }
    }

    // NEW: Enhanced audio extraction with retry logic
    private Map<String, String> extractAudioFromVideoWithRetry(String videoPath, Long projectId, String audioFileName) throws IOException, InterruptedException {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                Map<String, String> result = extractAudioFromVideo(videoPath, projectId, audioFileName);

                // If no audio stream was found, return null result
                if (result.get("audioPath") == null) {
                    logger.info("No audio stream found on attempt {} for video: {}, projectId={}", attempt + 1, videoPath, projectId);
                    return result;
                }

                // Verify the extraction was successful
                String audioPath = result.get("audioPath");
                Thread.sleep(1000); // Wait for file to be fully processed
                if (cloudflareR2Service.fileExists(audioPath)) {
                    logger.info("Audio extraction successful on attempt {}: {}, projectId={}", attempt + 1, audioPath, projectId);
                    return result;
                } else {
                    logger.warn("Audio file not found in R2 after extraction on attempt {}: {}, projectId={}", attempt + 1, audioPath, projectId);
                }

            } catch (Exception e) {
                logger.warn("Audio extraction failed on attempt {}/{} for video: {}, error: {}, projectId={}",
                        attempt + 1, maxRetries, videoPath, e.getMessage(), projectId);
                if (attempt == maxRetries - 1) {
                    throw new IOException("Audio extraction failed after " + maxRetries + " attempts for video: " + videoPath, e);
                }
            }

            attempt++;
            if (attempt < maxRetries) {
                Thread.sleep(2000 * attempt); // Progressive delay
            }
        }

        throw new IOException("Audio extraction failed after " + maxRetries + " attempts for video: " + videoPath);
    }
    private double getLastSegmentEndTime(TimelineState timelineState, int layer) {
        return timelineState.getSegments().stream()
                .filter(segment -> segment.getLayer() == layer)
                .mapToDouble(VideoSegment::getTimelineEndTime)
                .max()
                .orElse(0.0);
    }

    private void updateVideoMetadata(Project project, String r2Path, String videoFileName, String audioPath) throws JsonProcessingException {
        List<Map<String, String>> videos = getVideos(project);
        boolean videoExists = false;
        for (Map<String, String> video : videos) {
            if (video.get("videoPath").equals(r2Path)) {
                video.put("audioPath", audioPath);
                videoExists = true;
                break;
            }
        }
        if (!videoExists) {
            addVideo(project, r2Path, videoFileName, audioPath);
        } else {
            project.setVideosJson(objectMapper.writeValueAsString(videos));
        }
        projectRepository.save(project);
    }

    private AudioSegment createAudioSegment(String audioPath, String waveformJsonPath, double startTime, double endTime,
                                            double timelineStartTime, double timelineEndTime, TimelineState timelineState) {
        AudioSegment audioSegment = new AudioSegment();
        audioSegment.setAudioPath(audioPath);
        audioSegment.setWaveformJsonPath(waveformJsonPath);
        int audioLayer = findAvailableAudioLayer(timelineState, timelineStartTime, timelineEndTime);
        audioSegment.setLayer(audioLayer);
        audioSegment.setStartTime(startTime);
        audioSegment.setEndTime(endTime);
        audioSegment.setTimelineStartTime(timelineStartTime);
        audioSegment.setTimelineEndTime(timelineEndTime);
        audioSegment.setVolume(1.0);
        audioSegment.setExtracted(true);
        return audioSegment;
    }

    // NEW: Helper method to find an available audio layer
        private int findAvailableAudioLayer(TimelineState timelineState, double timelineStartTime, double timelineEndTime) {
            int layer = -1; // Start with layer -1
            while (true) {
                final int currentLayer = layer;
                boolean hasOverlap = timelineState.getAudioSegments().stream()
                        .filter(a -> a.getLayer() == currentLayer)
                        .anyMatch(a -> {
                            double existingStart = a.getTimelineStartTime();
                            double existingEnd = a.getTimelineEndTime();
                            return timelineStartTime < existingEnd && timelineEndTime > existingStart;
                        });
                if (!hasOverlap) {
                    return currentLayer;
                }
                layer--; // Try next layer (-2, -3, etc.)
            }
        }

    private Map<String, String> extractAudioFromVideo(String videoPath, Long projectId, String audioFileName) throws IOException, InterruptedException {
        String r2VideoPath = videoPath.startsWith("videos/projects/") ? videoPath : "videos/projects/" + projectId + "/" + videoPath;
        logger.info("Extracting audio from video: {} for projectId={}", r2VideoPath, projectId);

        // Construct local file path
        String localFileName = new File(r2VideoPath).getName();
        String localRelativePath = "temp/videos/" + localFileName;
        File videoFile = new File(baseDir, localRelativePath);

        try {
            if (!videoFile.exists()) {
                logger.info("Local video not found, downloading from R2: {} for projectId={}", r2VideoPath, projectId);
                videoFile.getParentFile().mkdirs();
                cloudflareR2Service.downloadFile(r2VideoPath, videoFile.getAbsolutePath());
                logger.info("Downloaded video to: {} for projectId={}", videoFile.getAbsolutePath(), projectId);
            }

            // Log file details
            logger.info("Local file: {} exists={}, isFile={}, canRead={}, size={} bytes for projectId={}",
                    videoFile.getAbsolutePath(), videoFile.exists(), videoFile.isFile(), videoFile.canRead(),
                    videoFile.exists() ? videoFile.length() : 0, projectId);

            // Generate unique audio file name with timestamp
            String baseFileName = audioFileName;
            if (baseFileName.contains(".")) {
                baseFileName = baseFileName.substring(0, baseFileName.lastIndexOf("."));
            }
            if (!baseFileName.startsWith("extracted_")) {
                baseFileName = "extracted_" + baseFileName;
            }
            String timestamp = String.valueOf(System.currentTimeMillis());
            String cleanAudioFileName = baseFileName.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + timestamp + ".mp3";
            String audioR2Path = "audio/projects/" + projectId + "/extracted/" + cleanAudioFileName;

            logger.info("Generated audio file name: {} with path: {} for projectId={}", cleanAudioFileName, audioR2Path, projectId);

            File audioDir = new File(baseDir, "temp/audio/" + projectId + "/extracted");
            if (!audioDir.exists() && !audioDir.mkdirs()) {
                logger.error("Failed to create audio directory: {} for projectId={}", audioDir.getAbsolutePath(), projectId);
                throw new IOException("Failed to create audio directory: " + audioDir.getAbsolutePath());
            }

            File audioFile = new File(audioDir, cleanAudioFileName);
            String waveformJsonPath = null;

            // Check if audio stream exists using ffprobe
            List<String> probeCommand = new ArrayList<>();
            probeCommand.add(ffprobePath);
            probeCommand.add("-v");
            probeCommand.add("error");
            probeCommand.add("-show_streams");
            probeCommand.add("-select_streams");
            probeCommand.add("a");
            probeCommand.add("-of");
            probeCommand.add("json");
            probeCommand.add(videoFile.getAbsolutePath());

            ProcessBuilder probeBuilder = new ProcessBuilder(probeCommand);
            probeBuilder.redirectErrorStream(true);
            Process probeProcess = probeBuilder.start();
            StringBuilder probeOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(probeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    probeOutput.append(line);
                }
            }
            int probeExitCode = probeProcess.waitFor();
            if (probeExitCode != 0) {
                logger.error("ffprobe failed for video: {}, output: {}, projectId={}", r2VideoPath, probeOutput, projectId);
                throw new IOException("Failed to probe video for audio streams: " + r2VideoPath);
            }

            Map<String, Object> probeData = objectMapper.readValue(probeOutput.toString(), new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> streams = (List<Map<String, Object>>) probeData.getOrDefault("streams", new ArrayList<>());
            if (streams.isEmpty()) {
                logger.info("No audio stream found in video: {} for projectId={}", r2VideoPath, projectId);
                Map<String, String> result = new HashMap<>();
                result.put("audioPath", null);
                result.put("waveformJsonPath", null);
                return result;
            }

            // Extract audio with improved FFmpeg command
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(videoFile.getAbsolutePath());
            command.add("-vn"); // No video
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-b:a");
            command.add("128k");
            command.add("-ar");
            command.add("44100");
            command.add("-y");
            command.add(audioFile.getAbsolutePath());

            logger.info("Executing FFmpeg command: {} for projectId={}", String.join(" ", command), projectId);

            try {
                executeFFmpegCommand(command);
                logger.info("Audio extraction completed successfully: {} for projectId={}", audioFile.getAbsolutePath(), projectId);
            } catch (RuntimeException e) {
                logger.error("Audio extraction failed for video: {} for projectId={}", r2VideoPath, projectId, e);
                throw new IOException("Failed to extract audio from video: " + r2VideoPath, e);
            }

            // Upload audio and generate waveform
            if (audioFile.exists() && audioFile.length() > 0) {
                logger.info("Uploading audio file to R2: {} -> {} for projectId={}", audioFile.getAbsolutePath(), audioR2Path, projectId);
                cloudflareR2Service.uploadFile(audioFile, audioR2Path);

                // Verify upload was successful
                if (!cloudflareR2Service.waitForFileAvailability(audioR2Path, 10, 1000)) {
                    throw new IOException("Failed to upload audio file to R2: " + audioR2Path);
                }

                Files.deleteIfExists(audioFile.toPath());
                waveformJsonPath = generateAndSaveWaveformJson(audioR2Path, projectId);

                // Update project with correct sourceVideoPath
                Project project = projectRepository.findById(projectId)
                        .orElseThrow(() -> new RuntimeException("Project not found"));
                addExtractedAudio(project, audioR2Path, cleanAudioFileName, r2VideoPath, waveformJsonPath);
                projectRepository.saveAndFlush(project); // Use saveAndFlush to ensure immediate persistence

                logger.info("Audio extraction and upload completed successfully: {} for projectId={}", audioR2Path, projectId);
            } else {
                logger.warn("No audio file created or file is empty: {} for projectId={}", audioFile.getAbsolutePath(), projectId);
                Map<String, String> result = new HashMap<>();
                result.put("audioPath", null);
                result.put("waveformJsonPath", null);
                return result;
            }

            Map<String, String> result = new HashMap<>();
            result.put("audioPath", audioR2Path);
            result.put("audioFileName", cleanAudioFileName); // Explicitly return audioFileName
            result.put("waveformJsonPath", waveformJsonPath);
            return result;
        } finally {
            // Cleanup temporary video file
            if (videoFile.exists()) {
                Files.deleteIfExists(videoFile.toPath());
                logger.debug("Deleted temporary video file: {}", videoFile.getAbsolutePath());
            }
        }
    }
    public double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        // Construct local file path
        String localFileName = new File(videoPath).getName();
        String localRelativePath = "temp/videos/" + localFileName;
        File localFile = new File(baseDir, localRelativePath);

        // Download if not exists
        if (!localFile.exists()) {
            logger.info("Local video not found, downloading from R2: {}", videoPath);
            try {
                localFile.getParentFile().mkdirs();
                cloudflareR2Service.downloadFile(videoPath, localFile.getAbsolutePath());
                logger.info("Downloaded video to: {}", localFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to download video from R2: {}", videoPath, e);
                throw new IOException("Failed to download video from R2: " + videoPath, e);
            }
        }

        // Log file details
        logger.info("Local file: {} exists={}, isFile={}, canRead={}, size={} bytes",
                localFile.getAbsolutePath(), localFile.exists(), localFile.isFile(), localFile.canRead(),
                localFile.exists() ? localFile.length() : 0);

        try {
            List<String> command = new ArrayList<>();
            command.add(ffprobePath);
            command.add("-v");
            command.add("error");
            command.add("-show_entries");
            command.add("format=duration");
            command.add("-of");
            command.add("default=noprint_wrappers=1:nokey=1");
            command.add(localFile.getAbsolutePath());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            String duration = output.toString().trim();

            if (exitCode != 0 || duration.isEmpty()) {
                logger.error("Failed to get video duration for path: {}. ffprobe exitCode: {}, output: {}",
                        videoPath, exitCode, output);
                throw new IOException("Failed to get video duration for path: " + videoPath);
            }

            try {
                return roundToThreeDecimals(Double.parseDouble(duration));
            } catch (NumberFormatException e) {
                logger.error("Invalid duration format for path: {}. Output: {}", videoPath, duration);
                throw new IOException("Invalid duration format for path: " + videoPath, e);
            }
        } finally {
            // Optional: Keep file for caching
            // Files.deleteIfExists(localFile.toPath());
        }
    }

    public void updateVideoSegment(
            String sessionId,
            String segmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Double timelineStartTime,
            Integer layer,
            Double timelineEndTime,
            Double startTime,
            Double endTime,
            Double cropL,
            Double cropR,
            Double cropT,
            Double cropB,
            Double speed,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException, InterruptedException {
        // Validate inputs early
        if (opacity != null && (opacity < 0 || opacity > 1)) {
            logger.error("Invalid opacity: {}", opacity);
            throw new IllegalArgumentException("Opacity must be between 0 and 1");
        }
        if (scale != null && scale <= 0) {
            logger.error("Invalid scale: {}", scale);
            throw new IllegalArgumentException("Scale must be positive");
        }
        if (timelineStartTime != null && timelineStartTime < 0) {
            logger.error("Invalid timelineStartTime: {}", timelineStartTime);
            throw new IllegalArgumentException("Timeline start time must be non-negative");
        }
        if (timelineEndTime != null && timelineEndTime <= (timelineStartTime != null ? timelineStartTime : 0)) {
            logger.error("Invalid timelineEndTime: {}", timelineEndTime);
            throw new IllegalArgumentException("Timeline end time must be greater than start time");
        }
        if (startTime != null && startTime < 0) {
            logger.error("Invalid startTime: {}", startTime);
            throw new IllegalArgumentException("Start time must be non-negative");
        }
        if (speed != null && (speed < 0.1 || speed > 5.0)) {
            logger.error("Invalid speed: {}", speed);
            throw new IllegalArgumentException("Speed must be between 0.1 and 5.0");
        }
        if (cropL != null && (cropL < 0 || cropL > 100)) {
            logger.error("Invalid cropL: {}", cropL);
            throw new IllegalArgumentException("cropL must be between 0 and 100");
        }
        if (cropR != null && (cropR < 0 || cropR > 100)) {
            logger.error("Invalid cropR: {}", cropR);
            throw new IllegalArgumentException("cropR must be between 0 and 100");
        }
        if (cropT != null && (cropT < 0 || cropT > 100)) {
            logger.error("Invalid cropT: {}", cropT);
            throw new IllegalArgumentException("cropT must be between 0 and 100");
        }
        if (cropB != null && (cropB < 0 || cropB > 100)) {
            logger.error("Invalid cropB: {}", cropB);
            throw new IllegalArgumentException("cropB must be between 0 and 100");
        }

        // Non-transactional: Load project and validate
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        VideoSegment segmentToUpdate = timelineState.getSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> {
                    logger.error("Video segment not found: segmentId={}", segmentId);
                    return new RuntimeException("No segment found with ID: " + segmentId);
                });

        // Validate crop parameters
        double effectiveCropL = cropL != null ? cropL : (segmentToUpdate.getCropL() != null ? segmentToUpdate.getCropL() : 0.0);
        double effectiveCropR = cropR != null ? cropR : (segmentToUpdate.getCropR() != null ? segmentToUpdate.getCropR() : 0.0);
        double effectiveCropT = cropT != null ? cropT : (segmentToUpdate.getCropT() != null ? segmentToUpdate.getCropT() : 0.0);
        double effectiveCropB = cropB != null ? cropB : (segmentToUpdate.getCropB() != null ? segmentToUpdate.getCropB() : 0.0);
        if (effectiveCropL + effectiveCropR >= 100) {
            logger.error("Invalid crop: cropL={} + cropR={} >= 100", effectiveCropL, effectiveCropR);
            throw new IllegalArgumentException("Total crop percentage (left + right) must be less than 100");
        }
        if (effectiveCropT + effectiveCropB >= 100) {
            logger.error("Invalid crop: cropT={} + cropB={} >= 100", effectiveCropT, effectiveCropB);
            throw new IllegalArgumentException("Total crop percentage (top + bottom) must be less than 100");
        }

        // Validate video duration
        String videoPath = segmentToUpdate.getSourceVideoPath();
        String r2Path = videoPath.startsWith("videos/projects/") ? videoPath : "videos/projects/" + project.getId() + "/" + videoPath;
        if (!cloudflareR2Service.fileExists(r2Path)) {
            logger.error("Video file not found in R2: {}", r2Path);
            throw new IOException("Video file not found in R2: " + r2Path);
        }
        double videoDuration = getVideoDuration(r2Path);
        if (endTime != null && endTime > videoDuration) {
            logger.error("End time {} exceeds video duration {}", endTime, videoDuration);
            throw new IllegalArgumentException("End time exceeds video duration");
        }
        if (startTime != null && endTime != null && startTime >= endTime) {
            logger.error("Invalid time bounds: startTime={} >= endTime={}", startTime, endTime);
            throw new IllegalArgumentException("Start time must be less than end time");
        }

        // Validate keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            double segmentDuration = segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime();
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                for (Keyframe kf : entry.getValue()) {
                    if (kf.getTime() < 0 || kf.getTime() > segmentDuration) {
                        logger.error("Keyframe time {} out of bounds for property {}", kf.getTime(), property);
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                }
            }
        }

        // Transactional update with retries
        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                doUpdateVideoSegment(
                        sessionId,
                        segmentId,
                        positionX,
                        positionY,
                        scale,
                        opacity,
                        timelineStartTime,
                        layer,
                        timelineEndTime,
                        startTime,
                        endTime,
                        cropL,
                        cropR,
                        cropT,
                        cropB,
                        speed,
                        rotation,
                        keyframes
                );
                logger.info("Successfully updated video segment: sessionId={}, segmentId={}", sessionId, segmentId);
                return;
            } catch (OptimisticLockException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    logger.error("Failed to update video segment after {} retries: sessionId={}, segmentId={}", maxRetries, sessionId, segmentId, e);
                    throw new RuntimeException("Failed to update video segment due to concurrent modification", e);
                }
                logger.warn("Optimistic lock exception on attempt {}/{} for sessionId={}, segmentId={}. Retrying...", attempt, maxRetries, sessionId, segmentId);
                try {
                    Thread.sleep(100 * attempt); // Backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    private void doUpdateVideoSegment(
            String sessionId,
            String segmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Double timelineStartTime,
            Integer layer,
            Double timelineEndTime,
            Double startTime,
            Double endTime,
            Double cropL,
            Double cropR,
            Double cropT,
            Double cropB,
            Double speed,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException, InterruptedException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        VideoSegment segmentToUpdate = timelineState.getSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> {
                    logger.error("Video segment not found in transaction: segmentId={}", segmentId);
                    return new RuntimeException("No segment found with ID: " + segmentId);
                });

        double originalTimelineStartTime = segmentToUpdate.getTimelineStartTime();
        double originalTimelineEndTime = segmentToUpdate.getTimelineEndTime();
        double originalStartTime = segmentToUpdate.getStartTime();
        double originalEndTime = segmentToUpdate.getEndTime();
        int originalLayer = segmentToUpdate.getLayer();
        Double originalCropL = segmentToUpdate.getCropL();
        Double originalCropR = segmentToUpdate.getCropR();
        Double originalCropT = segmentToUpdate.getCropT();
        Double originalCropB = segmentToUpdate.getCropB();
        Double originalSpeed = segmentToUpdate.getSpeed();
        Double originalRotation = segmentToUpdate.getRotation();
        boolean timelineOrLayerChanged = false;

        // Handle keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                segmentToUpdate.getKeyframes().remove(property);
                if (!kfs.isEmpty()) {
                    for (Keyframe kf : kfs) {
                        kf.setTime(roundToThreeDecimals(kf.getTime()));
                        segmentToUpdate.addKeyframe(property, kf);
                    }
                }
                switch (property) {
                    case "positionX":
                        if (kfs.isEmpty()) segmentToUpdate.setPositionX(positionX);
                        else segmentToUpdate.setPositionX(null);
                        break;
                    case "positionY":
                        if (kfs.isEmpty()) segmentToUpdate.setPositionY(positionY);
                        else segmentToUpdate.setPositionY(null);
                        break;
                    case "scale":
                        if (kfs.isEmpty()) segmentToUpdate.setScale(scale);
                        else segmentToUpdate.setScale(null);
                        break;
                    case "opacity":
                        if (kfs.isEmpty()) segmentToUpdate.setOpacity(opacity);
                        else segmentToUpdate.setOpacity(null);
                        break;
                }
            }
        } else {
            segmentToUpdate.getKeyframes().clear();
            if (positionX != null) segmentToUpdate.setPositionX(positionX);
            if (positionY != null) segmentToUpdate.setPositionY(positionY);
            if (scale != null) segmentToUpdate.setScale(scale);
            if (opacity != null) segmentToUpdate.setOpacity(opacity);
            if (rotation != null) segmentToUpdate.setRotation(rotation);
        }

        // Update non-keyframed properties
        if (positionX != null && (keyframes == null || !keyframes.containsKey("positionX") || keyframes.get("positionX").isEmpty())) {
            segmentToUpdate.setPositionX(positionX);
        }
        if (positionY != null && (keyframes == null || !keyframes.containsKey("positionY") || keyframes.get("positionY").isEmpty())) {
            segmentToUpdate.setPositionY(positionY);
        }
        if (scale != null && (keyframes == null || !keyframes.containsKey("scale") || keyframes.get("scale").isEmpty())) {
            segmentToUpdate.setScale(scale);
        }
        if (opacity != null && (keyframes == null || !keyframes.containsKey("opacity") || keyframes.get("opacity").isEmpty())) {
            segmentToUpdate.setOpacity(opacity);
        }
        if (rotation != null) {
            segmentToUpdate.setRotation(rotation);
        }
        if (layer != null) {
            segmentToUpdate.setLayer(layer);
            timelineOrLayerChanged = true;
        }
        if (timelineStartTime != null) {
            timelineStartTime = roundToThreeDecimals(timelineStartTime);
            segmentToUpdate.setTimelineStartTime(timelineStartTime);
            timelineOrLayerChanged = true;
        }
        if (startTime != null) {
            startTime = roundToThreeDecimals(startTime);
            segmentToUpdate.setStartTime(Math.max(0, startTime));
        }
        if (endTime != null) {
            endTime = roundToThreeDecimals(endTime);
            segmentToUpdate.setEndTime(endTime);
        }
        if (cropL != null) segmentToUpdate.setCropL(cropL);
        if (cropR != null) segmentToUpdate.setCropR(cropR);
        if (cropT != null) segmentToUpdate.setCropT(cropT);
        if (cropB != null) segmentToUpdate.setCropB(cropB);
        if (speed != null) {
            segmentToUpdate.setSpeed(speed);
        }

        // Adjust timelineEndTime based on speed and clip duration
        double newStartTime = startTime != null ? startTime : segmentToUpdate.getStartTime();
        double newEndTime = endTime != null ? endTime : segmentToUpdate.getEndTime();
        double newClipDuration = roundToThreeDecimals(newEndTime - newStartTime);
        double effectiveSpeed = speed != null ? speed : (originalSpeed != null ? originalSpeed : 1.0);

        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
            segmentToUpdate.setTimelineEndTime(timelineEndTime);
            timelineOrLayerChanged = true;
        } else if (startTime != null || endTime != null || speed != null) {
            double newTimelineDuration = roundToThreeDecimals(newClipDuration / effectiveSpeed);
            segmentToUpdate.setTimelineEndTime(roundToThreeDecimals(segmentToUpdate.getTimelineStartTime() + newTimelineDuration));
            timelineOrLayerChanged = true;
        }

        // Validate timeline position
        timelineState.getSegments().remove(segmentToUpdate);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                segmentToUpdate.getTimelineStartTime(),
                segmentToUpdate.getTimelineEndTime(),
                segmentToUpdate.getLayer());
        timelineState.getSegments().add(segmentToUpdate);

        if (!positionAvailable) {
            logger.warn("Timeline position overlap: segmentId={}, layer={}, timelineStart={}, timelineEnd={}",
                    segmentId, segmentToUpdate.getLayer(), segmentToUpdate.getTimelineStartTime(), segmentToUpdate.getTimelineEndTime());
            segmentToUpdate.setTimelineStartTime(originalTimelineStartTime);
            segmentToUpdate.setTimelineEndTime(originalTimelineEndTime);
            segmentToUpdate.setStartTime(originalStartTime);
            segmentToUpdate.setEndTime(originalEndTime);
            segmentToUpdate.setLayer(originalLayer);
            segmentToUpdate.setCropL(originalCropL);
            segmentToUpdate.setCropR(originalCropR);
            segmentToUpdate.setCropT(originalCropT);
            segmentToUpdate.setCropB(originalCropB);
            segmentToUpdate.setSpeed(originalSpeed);
            segmentToUpdate.setRotation(originalRotation);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + segmentToUpdate.getLayer());
        }

        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    segmentId,
                    segmentToUpdate.getLayer(),
                    segmentToUpdate.getTimelineStartTime(),
                    segmentToUpdate.getTimelineEndTime()
            );
        }

        saveTimelineState(sessionId, timelineState);
    }

    // Private method for internal use with B2 paths
    public double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        // Verify file exists in R2
        if (!cloudflareR2Service.fileExists(audioPath)) {
            logger.error("Audio file not found in R2: {}", audioPath);
            throw new IOException("Audio file not found in R2: " + audioPath);
        }

        // Construct local file path
        String localFileName = new File(audioPath).getName();
        String localRelativePath = "temp/audio/" + localFileName;
        File localFile = new File(baseDir, localRelativePath);

        // Download if not exists
        if (!localFile.exists()) {
            logger.info("Local audio not found, downloading from R2: {}", audioPath);
            try {
                localFile.getParentFile().mkdirs();
                cloudflareR2Service.downloadFile(audioPath, localFile.getAbsolutePath());
                logger.info("Downloaded audio to: {}", localFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to download audio from R2: {}", audioPath, e);
                throw new IOException("Failed to download audio from R2: " + audioPath, e);
            }
        }

        try {
            return getAudioDurationFromFile(localFile);
        } finally {
            // Optional: Keep file for caching
            // Files.deleteIfExists(localFile.toPath());
        }
    }
    // Helper method to get duration from a File using ffprobe
    private double getAudioDurationFromFile(File audioFile) throws IOException, InterruptedException {
        logger.info("Extracting duration for file: {}", audioFile.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(
                ffprobePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioFile.getAbsolutePath()
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String duration = reader.readLine();
        int exitCode = process.waitFor();

        if (exitCode != 0 || duration == null) {
            throw new IOException("Failed to get audio duration for file: " + audioFile.getAbsolutePath());
        }

        double durationSeconds = Double.parseDouble(duration);
        return roundToThreeDecimals(durationSeconds);
    }

    public VideoSegment getVideoSegment(String sessionId, String segmentId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        for (VideoSegment segment : timelineState.getSegments()) {
            if (segment.getId().equals(segmentId)) {
                project.setLastModified(LocalDateTime.now());
                projectRepository.save(project);
                return segment;
            }
        }
        throw new RuntimeException("No segment found with ID: " + segmentId);
    }

    public void addTextToTimeline(String sessionId, String text, int layer, double timelineStartTime, double timelineEndTime,
                                  String fontFamily, Double scale, String fontColor, String backgroundColor,
                                  Integer positionX, Integer positionY, Double opacity, String alignment,
                                  Double backgroundOpacity, Integer backgroundBorderWidth, String backgroundBorderColor,
                                  Integer backgroundH, Integer backgroundW,
                                  Integer backgroundBorderRadius,
                                  String textBorderColor, Integer textBorderWidth, Double textBorderOpacity,
                                  Double letterSpacing, Double lineSpacing, Double rotation) throws IOException { // Added lineSpacing parameter
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        timelineEndTime = roundToThreeDecimals(timelineEndTime);

        if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new IllegalArgumentException("Cannot add text: position overlaps with existing element in layer " + layer);
        }

        TextSegment textSegment = new TextSegment();
        textSegment.setId(UUID.randomUUID().toString()); // Ensure unique ID
        textSegment.setText(text);
        textSegment.setLayer(layer);
        textSegment.setTimelineStartTime(timelineStartTime);
        textSegment.setTimelineEndTime(timelineEndTime);
        textSegment.setFontFamily(fontFamily != null ? fontFamily : "Arial");
        textSegment.setScale(scale != null ? scale : 1.0);
        textSegment.setFontColor(fontColor != null ? fontColor : "white");
        textSegment.setBackgroundColor(backgroundColor != null ? backgroundColor : "transparent");
        textSegment.setPositionX(positionX != null ? positionX : 0);
        textSegment.setPositionY(positionY != null ? positionY : 0);
        textSegment.setOpacity(opacity != null ? opacity : 1.0);
        textSegment.setAlignment(alignment != null ? alignment : "left");
        // Set background properties
        textSegment.setBackgroundOpacity(backgroundOpacity);
        textSegment.setBackgroundBorderWidth(backgroundBorderWidth);
        textSegment.setBackgroundBorderColor(backgroundBorderColor != null ? backgroundBorderColor : "transparent");
        textSegment.setBackgroundH(backgroundH);
        textSegment.setBackgroundW(backgroundW);
        textSegment.setBackgroundBorderRadius(backgroundBorderRadius);
        // Set text border properties
        textSegment.setTextBorderColor(textBorderColor != null ? textBorderColor : "transparent");
        textSegment.setTextBorderWidth(textBorderWidth);
        textSegment.setTextBorderOpacity(textBorderOpacity);
        // Set letter spacing
        textSegment.setLetterSpacing(letterSpacing);
        // Set line spacing
        textSegment.setLineSpacing(lineSpacing);
        textSegment.setRotation(rotation);

        // Generate text PNG and upload to R2
        File tempDir = new File(baseDir);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        String tempPngPath = generateTextPng(textSegment, tempDir,
                timelineState.getCanvasWidth() != null ? timelineState.getCanvasWidth() : 1920,
                timelineState.getCanvasHeight() != null ? timelineState.getCanvasHeight() : 1080);
        File tempPngFile = new File(tempPngPath);
        if (!tempPngFile.exists()) {
            throw new IOException("Failed to generate text PNG: " + tempPngPath);
        }

        // Use projectId instead of sessionId
        String r2Path = String.format("text/projects/%d/%s.png", project.getId(), textSegment.getId());
        textSegment.setR2Path(r2Path);
        cloudflareR2Service.uploadFile(r2Path, tempPngFile);

        // Clean up temporary PNG file
        try {
            Files.deleteIfExists(tempPngFile.toPath());
        } catch (IOException e) {
            logger.warn("Failed to delete temporary text PNG: {}", tempPngPath, e);
        }

        timelineState.getTextSegments().add(textSegment);
        project.setTimelineState(objectMapper.writeValueAsString(timelineState));
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
    }

    public void updateTextSegment(
            String sessionId,
            String segmentId,
            String text,
            String fontFamily,
            Double scale,
            String fontColor,
            String backgroundColor,
            Integer positionX,
            Integer positionY,
            Double opacity,
            Double timelineStartTime,
            Double timelineEndTime,
            Integer layer,
            String alignment,
            Double backgroundOpacity,
            Integer backgroundBorderWidth,
            String backgroundBorderColor,
            Integer backgroundH,
            Integer backgroundW,
            Integer backgroundBorderRadius,
            String textBorderColor,
            Integer textBorderWidth,
            Double textBorderOpacity,
            Double letterSpacing,
            Double lineSpacing,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        // Validate inputs early
        if (opacity != null && (opacity < 0 || opacity > 1)) {
            throw new IllegalArgumentException("Opacity must be between 0 and 1");
        }
        if (scale != null && scale <= 0) {
            throw new IllegalArgumentException("Scale must be positive");
        }
        if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
            throw new IllegalArgumentException("Background opacity must be between 0 and 1");
        }
        if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
            throw new IllegalArgumentException("Text border opacity must be between 0 and 1");
        }
        if (letterSpacing != null && letterSpacing < 0) {
            throw new IllegalArgumentException("Letter spacing must be non-negative");
        }
        if (lineSpacing != null && lineSpacing < 0) {
            throw new IllegalArgumentException("Line spacing must be non-negative");
        }
        if (timelineStartTime != null && timelineStartTime < 0) {
            throw new IllegalArgumentException("Timeline start time must be non-negative");
        }
        if (timelineEndTime != null && timelineEndTime <= (timelineStartTime != null ? timelineStartTime : 0)) {
            throw new IllegalArgumentException("Timeline end time must be greater than start time");
        }

        // Non-transactional: Load project and timeline state for validation
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        TextSegment textSegment = timelineState.getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        // Validate keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            double segmentDuration = textSegment.getTimelineEndTime() - textSegment.getTimelineStartTime();
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                for (Keyframe kf : entry.getValue()) {
                    if (kf.getTime() < 0 || kf.getTime() > segmentDuration) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                }
            }
        }

        // Transactional update with retries
        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                doUpdateTextSegment(sessionId, segmentId, text, fontFamily, scale, fontColor, backgroundColor,
                        positionX, positionY, opacity, timelineStartTime, timelineEndTime, layer, alignment,
                        backgroundOpacity, backgroundBorderWidth, backgroundBorderColor, backgroundH, backgroundW,
                        backgroundBorderRadius, textBorderColor, textBorderWidth, textBorderOpacity,
                        letterSpacing, lineSpacing, rotation, keyframes);
                return;
            } catch (OptimisticLockException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    logger.error("Failed to update text segment after {} retries: sessionId={}, segmentId={}", maxRetries, sessionId, segmentId, e);
                    throw new RuntimeException("Failed to update text segment due to concurrent modification", e);
                }
                logger.warn("Optimistic lock exception on attempt {}/{} for sessionId={}, segmentId={}. Retrying...", attempt, maxRetries, sessionId, segmentId);
                try {
                    Thread.sleep(100 * attempt); // Backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    private void doUpdateTextSegment(
            String sessionId,
            String segmentId,
            String text,
            String fontFamily,
            Double scale,
            String fontColor,
            String backgroundColor,
            Integer positionX,
            Integer positionY,
            Double opacity,
            Double timelineStartTime,
            Double timelineEndTime,
            Integer layer,
            String alignment,
            Double backgroundOpacity,
            Integer backgroundBorderWidth,
            String backgroundBorderColor,
            Integer backgroundH,
            Integer backgroundW,
            Integer backgroundBorderRadius,
            String textBorderColor,
            Integer textBorderWidth,
            Double textBorderOpacity,
            Double letterSpacing,
            Double lineSpacing,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        TextSegment textSegment = timelineState.getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        double originalTimelineStartTime = textSegment.getTimelineStartTime();
        double originalTimelineEndTime = textSegment.getTimelineEndTime();
        int originalLayer = textSegment.getLayer();
        Double originalRotation = textSegment.getRotation();
        boolean timelineOrLayerChanged = false;
        boolean textAppearanceChanged = false;

        // Check if properties affecting PNG appearance have changed
        if (text != null || fontFamily != null || scale != null || fontColor != null || backgroundColor != null ||
                alignment != null || backgroundOpacity != null || backgroundBorderWidth != null || backgroundBorderColor != null ||
                backgroundH != null || backgroundW != null || backgroundBorderRadius != null || textBorderColor != null ||
                textBorderWidth != null || textBorderOpacity != null || letterSpacing != null || lineSpacing != null) {
            textAppearanceChanged = true;
        }

        // Handle keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                textSegment.getKeyframes().remove(property);
                if (!kfs.isEmpty()) {
                    for (Keyframe kf : kfs) {
                        kf.setTime(roundToThreeDecimals(kf.getTime()));
                        textSegment.addKeyframe(property, kf);
                    }
                }
                switch (property) {
                    case "positionX":
                        if (kfs.isEmpty()) textSegment.setPositionX(positionX);
                        else textSegment.setPositionX(null);
                        break;
                    case "positionY":
                        if (kfs.isEmpty()) textSegment.setPositionY(positionY);
                        else textSegment.setPositionY(null);
                        break;
                    case "opacity":
                        if (kfs.isEmpty()) textSegment.setOpacity(opacity);
                        else textSegment.setOpacity(null);
                        break;
                    case "scale":
                        if (kfs.isEmpty()) textSegment.setScale(scale);
                        else {
                            textSegment.setScale(null);

                            textAppearanceChanged = true; // Scale keyframes affect PNG size
                        }
                        break;
                }
            }
        } else {
            textSegment.getKeyframes().clear();
            if (positionX != null) textSegment.setPositionX(positionX);
            if (positionY != null) textSegment.setPositionY(positionY);
            if (opacity != null) textSegment.setOpacity(opacity);
            if (scale != null) {
                textSegment.setScale(scale);
                textAppearanceChanged = true; // Scale change requires PNG regeneration
            }
            if (rotation != null) textSegment.setRotation(rotation);
        }

        // Update non-keyframed properties
        if (text != null) textSegment.setText(text);
        if (fontFamily != null) textSegment.setFontFamily(fontFamily);
        if (scale != null && (keyframes == null || !keyframes.containsKey("scale") || keyframes.get("scale").isEmpty())) {
            textSegment.setScale(scale);
        }
        if (fontColor != null) textSegment.setFontColor(fontColor);
        if (backgroundColor != null) textSegment.setBackgroundColor(backgroundColor);
        if (positionX != null && (keyframes == null || !keyframes.containsKey("positionX") || keyframes.get("positionX").isEmpty())) {
            textSegment.setPositionX(positionX);
        }
        if (positionY != null && (keyframes == null || !keyframes.containsKey("positionY") || keyframes.get("positionY").isEmpty())) {
            textSegment.setPositionY(positionY);
        }
        if (opacity != null && (keyframes == null || !keyframes.containsKey("opacity") || keyframes.get("opacity").isEmpty())) {
            textSegment.setOpacity(opacity);
        }
        if (rotation != null) textSegment.setRotation(rotation);
        if (timelineStartTime != null) {
            timelineStartTime = roundToThreeDecimals(timelineStartTime);
            textSegment.setTimelineStartTime(timelineStartTime);
            timelineOrLayerChanged = true;
        }
        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
            textSegment.setTimelineEndTime(timelineEndTime);
            timelineOrLayerChanged = true;
        }
        if (layer != null) {
            textSegment.setLayer(layer);
            timelineOrLayerChanged = true;
        }
        if (alignment != null) textSegment.setAlignment(alignment);
        if (backgroundOpacity != null) textSegment.setBackgroundOpacity(backgroundOpacity);
        if (backgroundBorderWidth != null) textSegment.setBackgroundBorderWidth(backgroundBorderWidth);
        if (backgroundBorderColor != null) textSegment.setBackgroundBorderColor(backgroundBorderColor);
        if (backgroundH != null) textSegment.setBackgroundH(backgroundH);
        if (backgroundW != null) textSegment.setBackgroundW(backgroundW);
        if (backgroundBorderRadius != null) textSegment.setBackgroundBorderRadius(backgroundBorderRadius);
        if (textBorderColor != null) textSegment.setTextBorderColor(textBorderColor);
        if (textBorderWidth != null) textSegment.setTextBorderWidth(textBorderWidth);
        if (textBorderOpacity != null) textSegment.setTextBorderOpacity(textBorderOpacity);
        if (letterSpacing != null) textSegment.setLetterSpacing(letterSpacing);
        if (lineSpacing != null) textSegment.setLineSpacing(lineSpacing);

        // Regenerate and upload text PNG if appearance changed
        if (textAppearanceChanged) {
            File tempDir = new File(baseDir);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            String tempPngPath = generateTextPng(textSegment, tempDir,
                    timelineState.getCanvasWidth() != null ? timelineState.getCanvasWidth() : 1920,
                    timelineState.getCanvasHeight() != null ? timelineState.getCanvasHeight() : 1080);
            File tempPngFile = new File(tempPngPath);
            if (!tempPngFile.exists()) {
                throw new IOException("Failed to generate text PNG: " + tempPngPath);
            }

            String r2Path = textSegment.getR2Path();
            if (r2Path == null) {
                // Use projectId instead of sessionId
                r2Path = String.format("text/projects/%d/%s.png", project.getId(), segmentId);
                textSegment.setR2Path(r2Path);
            }
            cloudflareR2Service.uploadFile(r2Path, tempPngFile);

            // Clean up temporary PNG file
            try {
                Files.deleteIfExists(tempPngFile.toPath());
            } catch (IOException e) {
                logger.warn("Failed to delete temporary text PNG: {}", tempPngPath, e);
            }
        }

        // Validate timeline position
        timelineState.getTextSegments().remove(textSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                textSegment.getTimelineStartTime(),
                textSegment.getTimelineEndTime(),
                textSegment.getLayer());
        timelineState.getTextSegments().add(textSegment);

        if (!positionAvailable) {
            textSegment.setTimelineStartTime(originalTimelineStartTime);
            textSegment.setTimelineEndTime(originalTimelineEndTime);
            textSegment.setLayer(originalLayer);
            textSegment.setRotation(originalRotation);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + textSegment.getLayer());
        }

        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    segmentId,
                    textSegment.getLayer(),
                    textSegment.getTimelineStartTime(),
                    textSegment.getTimelineEndTime()
            );
        }

        saveTimelineState(sessionId, timelineState);
    }

    public Project uploadAudioToProject(User user, Long projectId, MultipartFile[] audioFiles, String[] audioFileNames) throws IOException, InterruptedException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> audioList = getAudio(project);

        for (int i = 0; i < audioFiles.length; i++) {
            MultipartFile audioFile = audioFiles[i];
            String originalFileName = audioFile.getOriginalFilename();
            String uniqueFileName = (audioFileNames != null && i < audioFileNames.length && audioFileNames[i] != null)
                    ? audioFileNames[i]
                    : projectId + "_" + System.currentTimeMillis() + "_" + originalFileName;

            // Use consistent R2 path
            String r2Path = "audio/projects/" + projectId + "/" + uniqueFileName;
            logger.info("Uploading audio to R2: {}", r2Path);

            // Upload to Cloudflare R2
            String uploadedPath = cloudflareR2Service.uploadFile(audioFile, r2Path);

            // Generate waveform JSON
            String waveformJsonPath = generateAndSaveWaveformJson(r2Path, projectId);

            // Store metadata
            Map<String, String> audioData = new HashMap<>();
            audioData.put("audioPath", r2Path);
            audioData.put("audioFileName", uniqueFileName);
            audioData.put("waveformJsonPath", waveformJsonPath);
            audioData.put("cdnUrl", cloudflareR2Service.generateDownloadUrl(r2Path, 3600));
            audioList.add(audioData);
        }

        project.setAudioJson(objectMapper.writeValueAsString(audioList));
        project.setLastModified(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public void addAudioToTimelineFromProject(
            User user, String sessionId, Long projectId, int layer, double startTime, Double endTime,
            double timelineStartTime, Double timelineEndTime, String audioFileName
    ) throws IOException, InterruptedException, JsonProcessingException {
        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));
        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }
        String audioPath = null;
        boolean isExtracted = false;
        String normalizedFileName = audioFileName.contains("/") ?
                audioFileName.substring(audioFileName.lastIndexOf("/") + 1) : audioFileName;
        List<Map<String, String>> audioFiles = getAudio(project);
        Map<String, String> targetAudio = audioFiles.stream()
                .filter(audio -> audio.get("audioFileName").equals(normalizedFileName) ||
                        audio.get("audioPath").endsWith(normalizedFileName) ||
                        audio.get("audioFileName").equals(audioFileName) ||
                        audio.get("audioPath").equals(audioFileName))
                .findFirst()
                .orElse(null);
        if (targetAudio != null) {
            audioPath = targetAudio.get("audioPath");
            logger.info("Found audio in audioJson: audioPath={}", audioPath);
        } else {
            List<Map<String, String>> extractedAudios = getExtractedAudio(project);
            Map<String, String> extractedAudio = extractedAudios.stream()
                    .filter(audio -> audio.get("audioFileName").equals(normalizedFileName) ||
                            audio.get("audioPath").endsWith(normalizedFileName) ||
                            audio.get("audioFileName").equals(audioFileName) ||
                            audio.get("audioPath").equals(audioFileName))
                    .findFirst()
                    .orElse(null);
            if (extractedAudio != null) {
                audioPath = extractedAudio.get("audioPath");
                isExtracted = true;
                logger.info("Found audio in extractedAudioJson: audioPath={}", audioPath);
            } else {
                logger.error("Audio file not found: projectId={}, audioFileName={}", projectId, audioFileName);
                throw new RuntimeException("File with such name does not exist: " + audioFileName);
            }
        }
        startTime = roundToThreeDecimals(startTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        double calculatedEndTime = endTime != null ? roundToThreeDecimals(endTime) :
                roundToThreeDecimals(getAudioDuration(audioPath));
        double calculatedTimelineEndTime = timelineEndTime != null ? roundToThreeDecimals(timelineEndTime) :
                roundToThreeDecimals(timelineStartTime + (calculatedEndTime - startTime));
        addAudioToTimeline(sessionId, audioPath, layer, startTime, calculatedEndTime, timelineStartTime, calculatedTimelineEndTime, isExtracted);
    }

    public void addAudioToTimeline(
            String sessionId,
            String audioPath,
            int layer,
            double startTime,
            double endTime,
            double timelineStartTime,
            Double timelineEndTime,
            boolean isExtracted
    ) throws IOException, InterruptedException {
        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }

        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        // Validate and construct R2 path
        String r2Path;
        if (audioPath.startsWith("audio/projects/")) {
            r2Path = audioPath;
        } else {
            List<Map<String, String>> audioFiles = getAudio(project);
            List<Map<String, String>> extractedAudio = getExtractedAudio(project);
            Map<String, String> targetAudio = Stream.concat(audioFiles.stream(), extractedAudio.stream())
                    .filter(audio -> audio.get("audioFileName").equals(audioPath) || audio.get("audioPath").endsWith("/" + audioPath))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Audio file not found: " + audioPath));
            r2Path = targetAudio.get("audioPath");
            isExtracted = r2Path.contains("/extracted/");
        }

        logger.info("Using R2 audio path: bucket={}, path={} for projectId={}", r2Path, project.getId());

        // Verify file exists in R2
        if (!cloudflareR2Service.fileExists(r2Path)) {
            logger.error("Audio file not found in R2: bucket={}, path={}", r2Path);
            throw new IOException("Audio file not found in R2: " + r2Path);
        }

        // Get audio duration
        double audioDuration = getAudioDuration(r2Path);
        logger.info("Audio duration for {}: {} seconds for projectId={}", r2Path, audioDuration, project.getId());

        startTime = roundToThreeDecimals(startTime);
        endTime = roundToThreeDecimals(endTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        timelineEndTime = roundToThreeDecimals(timelineEndTime != null ? timelineEndTime : timelineStartTime + (endTime - startTime));

        if (startTime < 0 || endTime > audioDuration || startTime >= endTime) {
            logger.warn("Invalid audio startTime={} or endTime={} for duration={} for projectId={}", startTime, endTime, audioDuration, project.getId());
            throw new RuntimeException("Invalid audio start/end times");
        }

        if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            logger.warn("Timeline position overlap at layer={} for projectId={}", layer, project.getId());
            throw new RuntimeException("Timeline position overlaps with existing audio in layer " + layer);
        }

        AudioSegment audioSegment = new AudioSegment();
        audioSegment.setAudioPath(r2Path); // Store R2 path
        audioSegment.setLayer(layer);
        audioSegment.setStartTime(startTime);
        audioSegment.setEndTime(endTime);
        audioSegment.setTimelineStartTime(timelineStartTime);
        audioSegment.setTimelineEndTime(timelineEndTime);
        audioSegment.setVolume(1.0);
        audioSegment.setExtracted(isExtracted);

        String waveformJsonPath = getWaveformJsonPath(project, r2Path);
        audioSegment.setWaveformJsonPath(waveformJsonPath);

        timelineState.getAudioSegments().add(audioSegment);
        saveTimelineState(sessionId, timelineState);
        logger.info("Successfully added audio segment to timeline: projectId={}, r2Path={}", project.getId(), r2Path);
    }

    private String getWaveformJsonPath(Project project, String r2Path) throws JsonProcessingException {
        List<Map<String, String>> audioFiles = getAudio(project);
        String waveformJsonPath = audioFiles.stream()
                .filter(audio -> audio.get("audioPath").equals(r2Path))
                .map(audio -> audio.get("waveformJsonPath"))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (waveformJsonPath == null) {
            List<Map<String, String>> extractedAudio = getExtractedAudio(project);
            waveformJsonPath = extractedAudio.stream()
                    .filter(audio -> audio.get("audioPath").equals(r2Path))
                    .map(audio -> audio.get("waveformJsonPath"))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return waveformJsonPath;
    }
    public void updateAudioSegment(
            String sessionId,
            String audioSegmentId,
            Double startTime,
            Double endTime,
            Double timelineStartTime,
            Double timelineEndTime,
            Double volume,
            Integer layer,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException, InterruptedException {
        // Validate inputs early
        if (volume != null && (volume < 0 || volume > 15)) {
            throw new IllegalArgumentException("Volume must be between 0.0 and 15.0");
        }
        if (startTime != null && startTime < 0) {
            throw new IllegalArgumentException("Start time must be non-negative");
        }
        if (endTime != null && endTime <= (startTime != null ? startTime : 0)) {
            throw new IllegalArgumentException("End time must be greater than start time");
        }
        if (timelineStartTime != null && timelineStartTime < 0) {
            throw new IllegalArgumentException("Timeline start time must be non-negative");
        }
        if (timelineEndTime != null && timelineEndTime <= (timelineStartTime != null ? timelineStartTime : 0)) {
            throw new IllegalArgumentException("Timeline end time must be greater than start time");
        }
        if (layer != null && layer >= 0) {
            throw new IllegalArgumentException("Audio layers must be negative");
        }

        // Non-transactional: Load project and validate audio duration
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        AudioSegment audioSegment = timelineState.getAudioSegments().stream()
                .filter(segment -> segment.getId().equals(audioSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Audio segment not found: " + audioSegmentId));

        // Get audio duration
        double audioDuration = getAudioDuration(audioSegment.getAudioPath());

        // Validate start/end times against duration
        if (startTime != null && startTime >= audioDuration) {
            throw new IllegalArgumentException("Start time out of bounds: " + startTime);
        }
        if (endTime != null && (endTime <= (startTime != null ? startTime : audioSegment.getStartTime()) || endTime > audioDuration)) {
            throw new IllegalArgumentException("End time out of bounds: " + endTime + ", audioDuration: " + audioDuration);
        }

        // Validate keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            double segmentDuration = audioSegment.getTimelineEndTime() - audioSegment.getTimelineStartTime();
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                for (Keyframe kf : entry.getValue()) {
                    if (kf.getTime() < 0 || kf.getTime() > segmentDuration) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                }
            }
        }

        // Transactional update with retries
        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                doUpdateAudioSegment(
                        sessionId,
                        audioSegmentId,
                        startTime,
                        endTime,
                        timelineStartTime,
                        timelineEndTime,
                        volume,
                        layer,
                        keyframes
                );
                return;
            } catch (OptimisticLockException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    logger.error("Failed to update audio segment after {} retries: sessionId={}, segmentId={}", maxRetries, sessionId, audioSegmentId, e);
                    throw new RuntimeException("Failed to update audio segment due to concurrent modification", e);
                }
                logger.warn("Optimistic lock exception on attempt {}/{} for sessionId={}, segmentId={}. Retrying...", attempt, maxRetries, sessionId, audioSegmentId);
                try {
                    Thread.sleep(100 * attempt); // Backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    private void doUpdateAudioSegment(
            String sessionId,
            String audioSegmentId,
            Double startTime,
            Double endTime,
            Double timelineStartTime,
            Double timelineEndTime,
            Double volume,
            Integer layer,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        AudioSegment audioSegment = timelineState.getAudioSegments().stream()
                .filter(segment -> segment.getId().equals(audioSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Audio segment not found: " + audioSegmentId));

        double originalStartTime = audioSegment.getStartTime();
        double originalEndTime = audioSegment.getEndTime();
        double originalTimelineStartTime = audioSegment.getTimelineStartTime();
        double originalTimelineEndTime = audioSegment.getTimelineEndTime();
        int originalLayer = audioSegment.getLayer();
        boolean timelineChanged = false;

        // Handle keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                audioSegment.getKeyframes().remove(property);
                if (!kfs.isEmpty()) {
                    for (Keyframe kf : kfs) {
                        kf.setTime(roundToThreeDecimals(kf.getTime()));
                        audioSegment.addKeyframe(property, kf);
                    }
                }
                if ("volume".equals(property)) {
                    audioSegment.setVolume(kfs.isEmpty() ? volume : null);
                }
            }
        } else {
            audioSegment.getKeyframes().clear();
            if (volume != null) audioSegment.setVolume(volume);
        }

        // Update non-keyframed properties
        if (volume != null && (keyframes == null || !keyframes.containsKey("volume") || keyframes.get("volume").isEmpty())) {
            audioSegment.setVolume(volume);
        }
        if (layer != null) {
            audioSegment.setLayer(layer);
            timelineChanged = true;
        }
        if (timelineStartTime != null) {
            timelineStartTime = roundToThreeDecimals(timelineStartTime);
            audioSegment.setTimelineStartTime(timelineStartTime);
            timelineChanged = true;
        }
        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
            audioSegment.setTimelineEndTime(timelineEndTime);
            timelineChanged = true;
        }
        if (startTime != null) {
            startTime = roundToThreeDecimals(startTime);
            audioSegment.setStartTime(startTime);
        }
        if (endTime != null) {
            endTime = roundToThreeDecimals(endTime);
            audioSegment.setEndTime(endTime);
        }

        // Adjust timeline times based on audio clip duration
        if (startTime != null || endTime != null || timelineChanged) {
            double newStartTime = startTime != null ? startTime : audioSegment.getStartTime();
            double newEndTime = endTime != null ? endTime : audioSegment.getEndTime();
            double clipDuration = roundToThreeDecimals(newEndTime - newStartTime);

            if (timelineChanged) {
                double providedTimelineDuration = roundToThreeDecimals(audioSegment.getTimelineEndTime() - audioSegment.getTimelineStartTime());
                if (Math.abs(providedTimelineDuration - clipDuration) > 0.001) {
                    audioSegment.setTimelineEndTime(roundToThreeDecimals(audioSegment.getTimelineStartTime() + clipDuration));
                }
            } else {
                if (startTime != null) {
                    double startTimeShift = newStartTime - originalStartTime;
                    audioSegment.setTimelineStartTime(roundToThreeDecimals(originalTimelineStartTime + startTimeShift));
                }
                audioSegment.setTimelineEndTime(roundToThreeDecimals(audioSegment.getTimelineStartTime() + clipDuration));
            }
        }

        // Final validation
        double newTimelineDuration = roundToThreeDecimals(audioSegment.getTimelineEndTime() - audioSegment.getTimelineStartTime());
        double newClipDuration = roundToThreeDecimals(audioSegment.getEndTime() - audioSegment.getStartTime());
        if (Math.abs(newTimelineDuration - newClipDuration) > 0.001) {
            throw new RuntimeException("Timeline duration (" + newTimelineDuration + ") does not match clip duration (" + newClipDuration + ")");
        }
        if (newTimelineDuration <= 0) {
            throw new RuntimeException("Invalid timeline duration: " + newTimelineDuration);
        }

        // Validate timeline position
        timelineState.getAudioSegments().remove(audioSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                audioSegment.getTimelineStartTime(),
                audioSegment.getTimelineEndTime(),
                audioSegment.getLayer());
        timelineState.getAudioSegments().add(audioSegment);

        if (!positionAvailable) {
            audioSegment.setStartTime(originalStartTime);
            audioSegment.setEndTime(originalEndTime);
            audioSegment.setTimelineStartTime(originalTimelineStartTime);
            audioSegment.setTimelineEndTime(originalTimelineEndTime);
            audioSegment.setLayer(originalLayer);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + audioSegment.getLayer());
        }

        saveTimelineState(sessionId, timelineState);
    }

    public void removeAudioSegment(String sessionId, String audioSegmentId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        boolean removed = timelineState.getAudioSegments().removeIf(
                segment -> segment.getId().equals(audioSegmentId)
        );

        if (!removed) {
            throw new RuntimeException("Audio segment not found with ID: " + audioSegmentId);
        }

        saveTimelineState(sessionId, timelineState);
    }

    public Project uploadImageToProject(User user, Long projectId, MultipartFile[] imageFiles, String[] imageFileNames) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> imageList = getImages(project);

        for (int i = 0; i < imageFiles.length; i++) {
            MultipartFile imageFile = imageFiles[i];
            if (imageFile.isEmpty()) {
                logger.error("Empty image file uploaded for projectId={}", projectId);
                throw new IOException("Uploaded image file is empty");
            }

            String originalFileName = imageFile.getOriginalFilename();
            String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
            String uniqueFileName = (imageFileNames != null && i < imageFileNames.length && imageFileNames[i] != null)
                    ? imageFileNames[i]
                    : projectId + "_" + System.currentTimeMillis() + "_" + sanitizedFileName;

            String r2Path = "image/projects/" + projectId + "/" + uniqueFileName;
            logger.info("Uploading image to R2: r2Path={}, projectId={}", r2Path, projectId);

            String uploadedPath = cloudflareR2Service.uploadFile(imageFile, r2Path);

            if (!cloudflareR2Service.fileExists(r2Path)) {
                logger.error("Uploaded image not found in R2: r2Path={}, projectId={}", r2Path, projectId);
                throw new IOException("Failed to verify uploaded image in R2: " + r2Path);
            }

            Map<String, String> imageData = new HashMap<>();
            imageData.put("imagePath", r2Path);
            imageData.put("imageFileName", uniqueFileName);
            imageData.put("originalFileName", originalFileName);

            // Generate both CDN and presigned URLs
            Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);
            imageData.put("cdnUrl", urls.get("cdnUrl"));
            imageData.put("presignedUrl", urls.get("presignedUrl"));

            imageList.add(imageData);
        }

        project.setImagesJson(objectMapper.writeValueAsString(imageList));
        project.setLastModified(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public void addImageToTimelineFromProject(
            User user,
            String sessionId,
            Long projectId,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Map<String, String> filters,
            String imageFileName,
            Double opacity,
            boolean isElement,
            Double rotation) throws IOException {
        String imagePath;

        if (isElement) {
            logger.debug("Searching for global element: imageFileName={}", imageFileName);
            try {
                GlobalElement globalElement = globalElementRepository.findByFileName(imageFileName)
                        .orElseThrow(() -> new RuntimeException("Global element not found with filename: " + imageFileName));
                logger.info("Global element found: id={}, globalElementJson={}", globalElement.getId(), globalElement.getGlobalElementJson());

                Map<String, String> jsonData = objectMapper.readValue(
                        globalElement.getGlobalElementJson(),
                        new TypeReference<Map<String, String>>() {}
                );
                imagePath = jsonData.get("imagePath");
                logger.info("Extracted imagePath: {}", imagePath);

                if (!cloudflareR2Service.fileExists(imagePath)) {
                    logger.error("Global element not found in R2: imagePath={}", imagePath);
                    throw new IOException("Global element not found in R2: " + imagePath);
                }

                Project project = projectRepository.findById(projectId)
                        .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));
                if (!project.getUser().getId().equals(user.getId())) {
                    throw new RuntimeException("Unauthorized to modify this project");
                }
                addElement(project, imagePath, imageFileName);
            } catch (RuntimeException e) {
                logger.error("Failed to process global element: imageFileName={}, error={}", imageFileName, e.getMessage(), e);
                throw e;
            } catch (IOException e) {
                logger.error("JSON parsing or R2 error for global element: imageFileName={}, error={}", imageFileName, e.getMessage(), e);
                throw e;
            }
        } else {
            // Handle project image
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));
            if (!project.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Unauthorized to modify this project");
            }
            List<Map<String, String>> images = getImages(project);
            Map<String, String> targetImage = images.stream()
                    .filter(img -> img.get("imageFileName").equals(imageFileName))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No image found with filename: " + imageFileName));
            imagePath = targetImage.get("imagePath"); // e.g., "images/projects/{projectId}/filename.png"
        }

        int positionX = 0;
        int positionY = 0;
        double scale = 1.0;
        // Round timeline times to three decimal places
        timelineStartTime = timelineStartTime != null ? roundToThreeDecimals(timelineStartTime) : 0.0;
        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
        }

        // Pass isElement to addImageToTimeline
        addImageToTimeline(sessionId, imagePath, layer, timelineStartTime, timelineEndTime, positionX, positionY, scale, opacity, filters, isElement, rotation);
    }
    public void addImageToTimeline(
            String sessionId,
            String imagePath, // This is the R2 path, e.g., elements/filename.png or image/projects/19/...
            int layer,
            double timelineStartTime,
            Double timelineEndTime,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Map<String, String> filters,
            boolean isElement,
            Double rotation
    ) throws IOException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        // Validate R2 path
        if (!cloudflareR2Service.fileExists(imagePath)) {
            logger.error("Image file not found in R2: path={}", imagePath);
            throw new IOException("Image file not found in R2: " + imagePath);
        }

        // Construct local file path for temporary storage
        String localFileName = project.getId() + "_" + System.currentTimeMillis() + "_" + new File(imagePath).getName();
        String localRelativePath = "temp/images/" + localFileName;
        File localFile = new File(baseDir, localRelativePath);

        // Download from R2
        logger.info("Downloading image from R2: path={} to localPath={}", imagePath, localFile.getAbsolutePath());
        try {
            // Ensure parent directory exists
            localFile.getParentFile().mkdirs();
            // Download file from R2
            cloudflareR2Service.downloadFile(imagePath, localFile.getAbsolutePath());
            logger.info("Successfully downloaded image to: {}", localFile.getAbsolutePath());

            // Validate downloaded file
            if (!localFile.exists() || !localFile.isFile() || localFile.length() == 0) {
                logger.error("Downloaded file is invalid: localPath={}, exists={}, isFile={}, size={}",
                        localFile.getAbsolutePath(), localFile.exists(), localFile.isFile(), localFile.length());
                throw new IOException("Downloaded image file is invalid: " + localFile.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to download image from R2: path={}, error={}", imagePath, e.getMessage(), e);
            throw new IOException("Failed to download image from R2: " + imagePath, e);
        }

        // Round timeline times to three decimal places
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        if (timelineEndTime == null) {
            timelineEndTime = roundToThreeDecimals(timelineStartTime + 5.0);
        } else {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
        }

        if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            logger.warn("Timeline position overlap at layer={} for projectId={}", layer, project.getId());
            throw new RuntimeException("Timeline position overlaps with existing segment in layer " + layer);
        }

        ImageSegment imageSegment = new ImageSegment();
        imageSegment.setId(UUID.randomUUID().toString());
        imageSegment.setImagePath(imagePath); // Store R2 path
        imageSegment.setLayer(layer);
        imageSegment.setPositionX(positionX != null ? positionX : 0);
        imageSegment.setPositionY(positionY != null ? positionY : 0);
        imageSegment.setScale(scale != null ? scale : 1.0);
        imageSegment.setOpacity(opacity != null ? opacity : 1.0);
        imageSegment.setTimelineStartTime(timelineStartTime);
        imageSegment.setTimelineEndTime(timelineEndTime);
        imageSegment.setElement(isElement);
        imageSegment.setCropB(0.0);
        imageSegment.setCropL(0.0);
        imageSegment.setCropR(0.0);
        imageSegment.setCropT(0.0);
        imageSegment.setRotation(rotation != null ? rotation : 0.0);

        try {
            BufferedImage img = ImageIO.read(localFile);
            imageSegment.setWidth(img.getWidth());
            imageSegment.setHeight(img.getHeight());
        } catch (IOException e) {
            logger.error("Error reading image file: path={}, error={}", localFile.getAbsolutePath(), e.getMessage(), e);
            throw new IOException("Error reading image file: " + localFile.getAbsolutePath(), e);
        }

        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                Filter filter = new Filter();
                filter.setSegmentId(imageSegment.getId());
                filter.setFilterName(entry.getKey());
                filter.setFilterValue(entry.getValue());
                timelineState.getFilters().add(filter);
            }
        }

        timelineState.getImageSegments().add(imageSegment);
        saveTimelineState(sessionId, timelineState);
        logger.info("Added image segment to timeline: projectId={}, r2Path={}, isElement={}", project.getId(), imagePath, isElement);

        // Clean up temporary file
        try {
            Files.deleteIfExists(localFile.toPath());
            logger.debug("Deleted temporary image file: {}", localFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to delete temporary image file: {}", localFile.getAbsolutePath(), e);
        }
    }

    public void updateImageSegment(
            String sessionId,
            String imageSegmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Integer layer,
            Integer customWidth,
            Integer customHeight,
            Boolean maintainAspectRatio,
            Double timelineStartTime,
            Double timelineEndTime,
            Double cropL,
            Double cropR,
            Double cropT,
            Double cropB,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        // Validate inputs early
        if (opacity != null && (opacity < 0 || opacity > 1)) {
            throw new IllegalArgumentException("Opacity must be between 0 and 1");
        }
        if (scale != null && scale <= 0) {
            throw new IllegalArgumentException("Scale must be positive");
        }
        if (timelineStartTime != null && timelineStartTime < 0) {
            throw new IllegalArgumentException("Timeline start time must be non-negative");
        }
        if (timelineEndTime != null && timelineEndTime <= (timelineStartTime != null ? timelineStartTime : 0)) {
            throw new IllegalArgumentException("Timeline end time must be greater than start time");
        }
        if (customWidth != null && customWidth <= 0) {
            throw new IllegalArgumentException("Custom width must be positive");
        }
        if (customHeight != null && customHeight <= 0) {
            throw new IllegalArgumentException("Custom height must be positive");
        }
        if (cropL != null && (cropL < 0 || cropL > 100)) {
            throw new IllegalArgumentException("cropL must be between 0 and 100");
        }
        if (cropR != null && (cropR < 0 || cropR > 100)) {
            throw new IllegalArgumentException("cropR must be between 0 and 100");
        }
        if (cropT != null && (cropT < 0 || cropT > 100)) {
            throw new IllegalArgumentException("cropT must be between 0 and 100");
        }
        if (cropB != null && (cropB < 0 || cropB > 100)) {
            throw new IllegalArgumentException("cropB must be between 0 and 100");
        }

        // Non-transactional: Load project and validate
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        ImageSegment imageSegment = timelineState.getImageSegments().stream()
                .filter(segment -> segment.getId().equals(imageSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Image segment not found: " + imageSegmentId));

        // Validate crop parameters
        double effectiveCropL = cropL != null ? cropL : imageSegment.getCropL();
        double effectiveCropR = cropR != null ? cropR : imageSegment.getCropR();
        double effectiveCropT = cropT != null ? cropT : imageSegment.getCropT();
        double effectiveCropB = cropB != null ? cropB : imageSegment.getCropB();
        if (effectiveCropL + effectiveCropR >= 100) {
            throw new IllegalArgumentException("Total crop percentage (left + right) must be less than 100");
        }
        if (effectiveCropT + effectiveCropB >= 100) {
            throw new IllegalArgumentException("Total crop percentage (top + bottom) must be less than 100");
        }

        // Validate keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            double segmentDuration = imageSegment.getTimelineEndTime() - imageSegment.getTimelineStartTime();
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                for (Keyframe kf : entry.getValue()) {
                    if (kf.getTime() < 0 || kf.getTime() > segmentDuration) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                }
            }
        }

        // Validate image file (non-transactional)
        String imagePath = imageSegment.getImagePath();
        if (!imageSegment.isElement() && !cloudflareR2Service.fileExists(imagePath)) {
            logger.error("Image file not found in R2: {}", imagePath);
            throw new IOException("Image file not found in R2: " + imagePath);
        }

        // Transactional update with retries
        int maxRetries = 3;
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                doUpdateImageSegment(
                        sessionId,
                        imageSegmentId,
                        positionX,
                        positionY,
                        scale,
                        opacity,
                        layer,
                        customWidth,
                        customHeight,
                        maintainAspectRatio,
                        timelineStartTime,
                        timelineEndTime,
                        cropL,
                        cropR,
                        cropT,
                        cropB,
                        rotation,
                        keyframes
                );
                return;
            } catch (OptimisticLockException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    logger.error("Failed to update image segment after {} retries: sessionId={}, segmentId={}", maxRetries, sessionId, imageSegmentId, e);
                    throw new RuntimeException("Failed to update image segment due to concurrent modification", e);
                }
                logger.warn("Optimistic lock exception on attempt {}/{} for sessionId={}, segmentId={}. Retrying...", attempt, maxRetries, sessionId, imageSegmentId);
                try {
                    Thread.sleep(100 * attempt); // Backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    private void doUpdateImageSegment(
            String sessionId,
            String imageSegmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Integer layer,
            Integer customWidth,
            Integer customHeight,
            Boolean maintainAspectRatio,
            Double timelineStartTime,
            Double timelineEndTime,
            Double cropL,
            Double cropR,
            Double cropT,
            Double cropB,
            Double rotation,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        ImageSegment imageSegment = timelineState.getImageSegments().stream()
                .filter(segment -> segment.getId().equals(imageSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Image segment not found: " + imageSegmentId));

        double originalTimelineStartTime = imageSegment.getTimelineStartTime();
        double originalTimelineEndTime = imageSegment.getTimelineEndTime();
        int originalLayer = imageSegment.getLayer();
        Double originalCropL = imageSegment.getCropL();
        Double originalCropR = imageSegment.getCropR();
        Double originalCropT = imageSegment.getCropT();
        Double originalCropB = imageSegment.getCropB();
        Double originalRotation = imageSegment.getRotation();
        boolean timelineOrLayerChanged = false;

        // Handle keyframes
        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                imageSegment.getKeyframes().remove(property);
                if (!kfs.isEmpty()) {
                    for (Keyframe kf : kfs) {
                        kf.setTime(roundToThreeDecimals(kf.getTime()));
                        imageSegment.addKeyframe(property, kf);
                    }
                }
                switch (property) {
                    case "positionX":
                        if (kfs.isEmpty()) imageSegment.setPositionX(positionX);
                        else imageSegment.setPositionX(null);
                        break;
                    case "positionY":
                        if (kfs.isEmpty()) imageSegment.setPositionY(positionY);
                        else imageSegment.setPositionY(null);
                        break;
                    case "scale":
                        if (kfs.isEmpty()) imageSegment.setScale(scale);
                        else imageSegment.setScale(null);
                        break;
                    case "opacity":
                        if (kfs.isEmpty()) imageSegment.setOpacity(opacity);
                        else imageSegment.setOpacity(null);
                        break;
                }
            }
        } else {
            imageSegment.getKeyframes().clear();
            if (positionX != null) imageSegment.setPositionX(positionX);
            if (positionY != null) imageSegment.setPositionY(positionY);
            if (scale != null) imageSegment.setScale(scale);
            if (opacity != null) imageSegment.setOpacity(opacity);
            if (rotation != null) imageSegment.setRotation(rotation);
        }

        // Update non-keyframed properties
        if (positionX != null && (keyframes == null || !keyframes.containsKey("positionX") || keyframes.get("positionX").isEmpty())) {
            imageSegment.setPositionX(positionX);
        }
        if (positionY != null && (keyframes == null || !keyframes.containsKey("positionY") || keyframes.get("positionY").isEmpty())) {
            imageSegment.setPositionY(positionY);
        }
        if (scale != null && (keyframes == null || !keyframes.containsKey("scale") || keyframes.get("scale").isEmpty())) {
            imageSegment.setScale(scale);
        }
        if (opacity != null && (keyframes == null || !keyframes.containsKey("opacity") || keyframes.get("opacity").isEmpty())) {
            imageSegment.setOpacity(opacity);
        }
        if (rotation != null) {
            imageSegment.setRotation(rotation);
        }
        if (layer != null) {
            imageSegment.setLayer(layer);
            timelineOrLayerChanged = true;
        }
        if (customWidth != null) imageSegment.setCustomWidth(customWidth);
        if (customHeight != null) imageSegment.setCustomHeight(customHeight);
        if (maintainAspectRatio != null) imageSegment.setMaintainAspectRatio(maintainAspectRatio);
        if (timelineStartTime != null) {
            timelineStartTime = roundToThreeDecimals(timelineStartTime);
            imageSegment.setTimelineStartTime(timelineStartTime);
            timelineOrLayerChanged = true;
        }
        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
            imageSegment.setTimelineEndTime(timelineEndTime);
            timelineOrLayerChanged = true;
        }
        if (cropL != null) imageSegment.setCropL(cropL);
        if (cropR != null) imageSegment.setCropR(cropR);
        if (cropT != null) imageSegment.setCropT(cropT);
        if (cropB != null) imageSegment.setCropB(cropB);

        // Validate timeline position
        timelineState.getImageSegments().remove(imageSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                imageSegment.getTimelineStartTime(),
                imageSegment.getTimelineEndTime(),
                imageSegment.getLayer());
        timelineState.getImageSegments().add(imageSegment);

        if (!positionAvailable) {
            imageSegment.setTimelineStartTime(originalTimelineStartTime);
            imageSegment.setTimelineEndTime(originalTimelineEndTime);
            imageSegment.setLayer(originalLayer);
            imageSegment.setCropL(originalCropL);
            imageSegment.setCropR(originalCropR);
            imageSegment.setCropT(originalCropT);
            imageSegment.setCropB(originalCropB);
            imageSegment.setRotation(originalRotation);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + imageSegment.getLayer());
        }

        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    imageSegmentId,
                    imageSegment.getLayer(),
                    imageSegment.getTimelineStartTime(),
                    imageSegment.getTimelineEndTime()
            );
        }

        saveTimelineState(sessionId, timelineState);
    }
    public void removeImageSegment(String sessionId, String segmentId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        boolean removed = timelineState.getImageSegments().removeIf(
                segment -> segment.getId().equals(segmentId)
        );

        if (!removed) {
            throw new RuntimeException("Image segment not found with ID: " + segmentId);
        }

        timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId));
        saveTimelineState(sessionId, timelineState);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveTimelineState(String sessionId, TimelineState timelineState) throws JsonProcessingException {
        logger.debug("Saving TimelineState for sessionId: {}", sessionId);
        Project project = getProjectBySession(sessionId);
        String serializedState = objectMapper.writeValueAsString(timelineState);
        logger.debug("Serialized TimelineState: {}", serializedState);
        project.setTimelineState(serializedState);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
        logger.info("Saved timeline state for sessionId: {}", sessionId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    private void saveTimelineStateNewTransaction(String sessionId, TimelineState timelineState) throws JsonProcessingException {
        saveTimelineState(sessionId, timelineState);
    }

    public TimelineState getTimelineState(String sessionId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
        return timelineState;
    }

        public void addKeyframeToSegment(String sessionId, String segmentId, String segmentType, String property, Keyframe keyframe) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            keyframe.setTime(roundToThreeDecimals(keyframe.getTime()));
            switch (segmentType.toLowerCase()) {
                case "video":
                    VideoSegment video = timelineState.getSegments().stream()
                            .filter(s -> s.getId().equals(segmentId))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Video segment not found: " + segmentId));
                    video.addKeyframe(property, keyframe);
                    break;
                case "image":
                    ImageSegment image = timelineState.getImageSegments().stream()
                            .filter(s -> s.getId().equals(segmentId))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Image segment not found: " + segmentId));
                    image.addKeyframe(property, keyframe);
                    break;
                case "text":
                    TextSegment text = timelineState.getTextSegments().stream()
                            .filter(s -> s.getId().equals(segmentId))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Text segment not found: " + segmentId));
                    text.addKeyframe(property, keyframe);
                    break;
                case "audio":
                    AudioSegment audio = timelineState.getAudioSegments().stream()
                            .filter(s -> s.getId().equals(segmentId))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Audio segment not found: " + segmentId));
                    audio.addKeyframe(property, keyframe);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid segment type: " + segmentType);
            }
            saveTimelineState(sessionId, timelineState);
        }

    public void updateKeyframeToSegment(String sessionId, String segmentId, String segmentType, String property, Keyframe keyframe) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        keyframe.setTime(roundToThreeDecimals(keyframe.getTime()));

        if (keyframe.getTime() < 0) {
            throw new IllegalArgumentException("Keyframe time must be non-negative");
        }

        switch (segmentType.toLowerCase()) {
            case "video":
                VideoSegment video = timelineState.getSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Video segment not found: " + segmentId));
                if (keyframe.getTime() > (video.getTimelineEndTime() - video.getTimelineStartTime())) {
                    throw new IllegalArgumentException("Keyframe time out of segment bounds for video segment");
                }
                video.updateKeyframe(property, keyframe);
                break;
            case "image":
                ImageSegment image = timelineState.getImageSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Image segment not found: " + segmentId));
                if (keyframe.getTime() > (image.getTimelineEndTime() - image.getTimelineStartTime())) {
                    throw new IllegalArgumentException("Keyframe time out of segment bounds for image segment");
                }
                image.updateKeyframe(property, keyframe);
                break;
            case "text":
                TextSegment text = timelineState.getTextSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Text segment not found: " + segmentId));
                if (keyframe.getTime() > (text.getTimelineEndTime() - text.getTimelineStartTime())) {
                    throw new IllegalArgumentException("Keyframe time out of segment bounds for text segment");
                }
                text.updateKeyframe(property, keyframe);
                break;
            case "audio":
                AudioSegment audio = timelineState.getAudioSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Audio segment not found: " + segmentId));
                if (keyframe.getTime() > (audio.getTimelineEndTime() - audio.getTimelineStartTime())) {
                    throw new IllegalArgumentException("Keyframe time out of segment bounds for audio segment");
                }
                audio.updateKeyframe(property, keyframe);
                break;
            default:
                throw new IllegalArgumentException("Invalid segment type: " + segmentType);
        }
        saveTimelineState(sessionId, timelineState);
    }

    public void removeKeyframeFromSegment(String sessionId, String segmentId, String segmentType, String property, double time) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        time = roundToThreeDecimals(time);
        switch (segmentType.toLowerCase()) {
            case "video":
                VideoSegment video = timelineState.getSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Video segment not found: " + segmentId));
                video.removeKeyframe(property, time);
                break;
            case "image":
                ImageSegment image = timelineState.getImageSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Image segment not found: " + segmentId));
                image.removeKeyframe(property, time);
                break;
            case "text":
                TextSegment text = timelineState.getTextSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Text segment not found: " + segmentId));
                text.removeKeyframe(property, time);
                break;
            case "audio":
                AudioSegment audio = timelineState.getAudioSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Audio segment not found: " + segmentId));
                audio.removeKeyframe(property, time);
                break;
            default:
                throw new IllegalArgumentException("Invalid segment type: " + segmentType);
        }
        saveTimelineState(sessionId, timelineState);
    }

    public void deleteProjectFiles(Long projectId) throws IOException {
        try {
            // Delete videos
            String videoPrefix = "videos/projects/" + projectId + "/";
            cloudflareR2Service.deleteDirectory(videoPrefix);

            // Delete audio and waveform JSON
            String audioPrefix = "audio/projects/" + projectId + "/";
            cloudflareR2Service.deleteDirectory(audioPrefix);

            // Delete images
            String imagePrefix = "image/projects/" + projectId + "/";
            cloudflareR2Service.deleteDirectory(imagePrefix);

            // Delete exported videos
            String exportPrefix = "exports/" + projectId + "/";
            cloudflareR2Service.deleteDirectory(exportPrefix);
        } catch (IOException e) {
            throw new IOException("Failed to delete project files from Cloudflare R2", e);
        }
    }

    private void deleteDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        directory.delete();
    }

    public void addTransition(
                String sessionId,
                String type,
                double duration,
                String segmentId,
                boolean start,
                boolean end,
                int layer,
                Map<String, String> parameters
        ) throws IOException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            Segment segment = findSegment(timelineState, segmentId);
            if (segment == null) {
                throw new RuntimeException("Segment not found with ID: " + segmentId);
            }
            if (segment.getLayer() != layer) {
                throw new RuntimeException("Segment must be on the same layer as the transition");
            }
            if (!start && !end) {
                throw new RuntimeException("Transition must be applied at start, end, or both");
            }

            // Validate duration
            if (duration <= 0) {
                throw new RuntimeException("Invalid transition duration: Duration must be positive");
            }
            double segmentDuration = segment.getTimelineEndTime() - segment.getTimelineStartTime();
            if (duration > segmentDuration) {
                throw new RuntimeException("Invalid transition duration: Duration exceeds segment duration");
            }

            // Calculate timeline start time
            double timelineStartTime;
            if (start) {
                timelineStartTime = roundToThreeDecimals(segment.getTimelineStartTime());
            } else { // end
                timelineStartTime = roundToThreeDecimals(segment.getTimelineEndTime() - duration);
            }

            // Check for overlapping transitions
            for (Transition existingTransition : timelineState.getTransitions()) {
                if (existingTransition.getLayer() == layer &&
                        existingTransition.getSegmentId().equals(segmentId) &&
                        existingTransition.isStart() == start &&
                        existingTransition.isEnd() == end &&
                        timelineStartTime < existingTransition.getTimelineStartTime() + existingTransition.getDuration() &&
                        timelineStartTime + duration > existingTransition.getTimelineStartTime()) {
                    throw new RuntimeException("Transition overlaps with an existing transition on layer " + layer +
                            " for segment " + segmentId + " at " + (start ? "start" : "end"));
                }
            }

            // Create and add transition
            Transition transition = new Transition();
            transition.setType(type);
            transition.setDuration(roundToThreeDecimals(duration));
            transition.setSegmentId(segmentId);
            transition.setStart(start);
            transition.setEnd(end);
            transition.setLayer(layer);
            transition.setTimelineStartTime(timelineStartTime);
            if (parameters != null) {
                transition.setParameters(parameters);
            }

            timelineState.getTransitions().add(transition);
            saveTimelineState(sessionId, timelineState);
        }

        public Transition updateTransition(
                String sessionId,
                String transitionId,
                String type,
                Double duration,
                String segmentId,
                Boolean start,
                Boolean end,
                Integer layer,
                Map<String, String> parameters
        ) throws IOException {
            Logger log = LoggerFactory.getLogger(VideoEditingService.class);
            log.info("Updating transition: sessionId={}, transitionId={}, type={}, duration={}, segmentId={}, start={}, end={}, layer={}",
                    sessionId, transitionId, type, duration, segmentId, start, end, layer);

            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            Transition transition = timelineState.getTransitions().stream()
                    .filter(t -> t.getId().equals(transitionId))
                    .findFirst()
                    .orElseThrow(() -> {
                        log.error("Transition not found: transitionId={}", transitionId);
                        return new RuntimeException("Transition not found: " + transitionId);
                    });


            // Store original values for rollback
            String originalType = transition.getType();
            double originalDuration = transition.getDuration();
            String originalSegmentId = transition.getSegmentId();
            boolean originalStart = transition.isStart();
            boolean originalEnd = transition.isEnd();
            int originalLayer = transition.getLayer();
            double originalTimelineStartTime = transition.getTimelineStartTime();
            Map<String, String> originalParameters = transition.getParameters();

            // Update fields if provided
            if (type != null) transition.setType(type);
            if (duration != null) transition.setDuration(roundToThreeDecimals(duration));
            if (segmentId != null) transition.setSegmentId(segmentId);
            if (start != null) transition.setStart(start);
            if (end != null) transition.setEnd(end);
            if (layer != null) transition.setLayer(layer);
            if (parameters != null) transition.setParameters(parameters);

            // Validate updated transition
            if (transition.isStart() == false && transition.isEnd() == false) {
                rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Transition must be applied at start, end, or both");
            }

            Segment segment = findSegment(timelineState, transition.getSegmentId());
            if (segment == null) {
                rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Segment not found: " + transition.getSegmentId());
            }

            // Validate layer consistency
            if (segment.getLayer() != transition.getLayer()) {
                rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Segment must be on the same layer as the transition");
            }

            // Validate duration
            if (transition.getDuration() <= 0) {
                rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Invalid transition duration: Duration must be positive");
            }
            double segmentDuration = segment.getTimelineEndTime() - segment.getTimelineStartTime();
            if (transition.getDuration() > segmentDuration) {
                rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Invalid transition duration: Duration exceeds segment duration");
            }

            // Recalculate timelineStartTime if necessary
            double timelineStartTime;
            if (transition.isStart()) {
                timelineStartTime = roundToThreeDecimals(segment.getTimelineStartTime());
            } else { // transition.isEnd()
                timelineStartTime = roundToThreeDecimals(segment.getTimelineEndTime() - transition.getDuration());
            }
            transition.setTimelineStartTime(timelineStartTime);

            // Check for overlapping transitions
            timelineState.getTransitions().remove(transition);
            for (Transition existingTransition : timelineState.getTransitions()) {
                if (existingTransition.getLayer() == transition.getLayer() &&
                        existingTransition.getSegmentId().equals(transition.getSegmentId()) &&
                        existingTransition.isStart() == transition.isStart() &&
                        existingTransition.isEnd() == transition.isEnd() &&
                        transition.getTimelineStartTime() < existingTransition.getTimelineStartTime() + existingTransition.getDuration() &&
                        transition.getTimelineStartTime() + transition.getDuration() > existingTransition.getTimelineStartTime()) {
                    timelineState.getTransitions().add(transition);
                    rollbackTransition(transition, originalType, originalDuration, originalSegmentId, originalStart, originalEnd, originalLayer, originalTimelineStartTime, originalParameters);
                    throw new RuntimeException("Transition overlaps with an existing transition on layer " + transition.getLayer() +
                            " for segment " + transition.getSegmentId() + " at " + (transition.isStart() ? "start" : "end"));
                }
            }
            timelineState.getTransitions().add(transition);

            saveTimelineState(sessionId, timelineState);
            log.info("Transition updated successfully: id={}", transition.getId());
            return transition;
            }

        private void rollbackTransition(
                Transition transition,
                String type,
                double duration,
                String segmentId,
                boolean start,
                boolean end,
                int layer,
                double timelineStartTime,
                Map<String, String> parameters
        ) {
            transition.setType(type);
            transition.setDuration(duration);
            transition.setSegmentId(segmentId);
            transition.setStart(start);
            transition.setEnd(end);
            transition.setLayer(layer);
            transition.setTimelineStartTime(timelineStartTime);
            transition.setParameters(parameters);
        }

        public void removeTransition(String sessionId, String transitionId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);


            boolean removed = timelineState.getTransitions().removeIf(t -> t.getId().equals(transitionId));
            if (!removed) {
                throw new RuntimeException("Transition not found: " + transitionId);
            }

            saveTimelineState(sessionId, timelineState);
        }

        // NEW: Helper method to find a segment by ID
        private Segment findSegment(TimelineState timelineState, String segmentId) {
            for (VideoSegment segment : timelineState.getSegments()) {
                if (segment.getId().equals(segmentId)) return segment;
            }
            for (ImageSegment segment : timelineState.getImageSegments()) {
                if (segment.getId().equals(segmentId)) return segment;
            }
            for (TextSegment segment : timelineState.getTextSegments()) {
                if (segment.getId().equals(segmentId)) return segment;
            }
            return null;
        }

        private void updateAssociatedTransitions(String sessionId, String segmentId, int newLayer, double newTimelineStartTime, double newTimelineEndTime) throws IOException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            List<Transition> transitionsToUpdate = timelineState.getTransitions().stream()
                    .filter(t -> segmentId.equals(t.getSegmentId()))
                    .collect(Collectors.toList());

            for (Transition transition : transitionsToUpdate) {
                // Update layer
                transition.setLayer(newLayer);

                // Recalculate timelineStartTime
                double timelineStartTime;
                if (transition.isStart()) {
                    timelineStartTime = roundToThreeDecimals(newTimelineStartTime);
                } else { // transition.isEnd()
                    timelineStartTime = roundToThreeDecimals(newTimelineEndTime - transition.getDuration());
                }
                transition.setTimelineStartTime(timelineStartTime);

                // Validate transition duration
                double segmentDuration = newTimelineEndTime - newTimelineStartTime;
                if (transition.getDuration() > segmentDuration) {
                    throw new RuntimeException("Transition duration exceeds segment duration after update for transition: " + transition.getId());
                }
            }

            saveTimelineState(sessionId, timelineState);
        }

        private String generateWaveformImage(String audioPath, Long projectId, String uniqueFileName) throws IOException, InterruptedException {
            File audioFile = new File(baseDir, audioPath);
            if (!audioFile.exists()) {
                throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
            }

            // Create waveform directory: audio/projects/{projectId}/waveforms/
            File waveformDir = new File(baseDir, "audio/projects/" + projectId + "/waveforms");
            if (!waveformDir.exists()) {
                waveformDir.mkdirs();
            }

            // Generate waveform filename based on audio filename
            String waveformFileName = "waveform_" + uniqueFileName.replaceAll("[^a-zA-Z0-9.]", "_") + ".png";
            File waveformFile = new File(waveformDir, waveformFileName);

            // FFmpeg command to generate waveform image
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(audioFile.getAbsolutePath());
            command.add("-filter_complex");
            command.add(
                    "[0:a]showwavespic=s=1920x120:colors=0x00FFFF@1.0|0xFFFFFF@0.8:split_channels=0,format=rgba[w];" + // Waveform with cyan and white, semi-transparent
                            "color=s=1920x120:c=0x1E2A44@1.0,format=rgba[bg];" + // Solid dark blue-gray background
                            "[bg][w]overlay=0:0:format=rgb,format=rgba" // Overlay waveform on background
            );
            command.add("-frames:v");
            command.add("1");
            command.add("-y"); // Overwrite output file if exists
            command.add(waveformFile.getAbsolutePath());

            executeFFmpegCommand(command);

            // Return relative path
            return "audio/projects/" + projectId + "/waveforms/" + waveformFileName;
        }

    // Public method for controller to get duration using projectId and filename
    public double getAudioDuration(Long projectId, String filename) throws IOException, InterruptedException {
        logger.info("Getting audio duration for projectId: {}, filename: {}", projectId, filename);

        String directPath = "audio/projects/" + projectId + "/" + filename;
        String extractedPath = "audio/projects/" + projectId + "/extracted/" + filename;

        String[] possiblePaths = {directPath, extractedPath};
        File tempFile = null;
        String tempPath = baseDir + "/temp/audio_" + System.currentTimeMillis() + "_" + filename;

        for (String r2Path : possiblePaths) {
            try {
                tempFile = cloudflareR2Service.downloadFile(r2Path, tempPath);
                if (tempFile.exists()) {
                    break;
                }
            } catch (IOException e) {
                logger.debug("File not found at R2 path: {}", r2Path);
            }
        }

        if (tempFile == null || !tempFile.exists()) {
            throw new IOException("Audio file not found for project ID: " + projectId + ", filename: " + filename);
        }

        try {
            return getAudioDurationFromFile(tempFile);
        } finally {
            Files.deleteIfExists(tempFile.toPath());
            logger.debug("Cleaned up temporary file: {}", tempPath);
        }
    }

    private String generateAndSaveWaveformJson(String audioPath, Long projectId) throws IOException, InterruptedException {
        String tempAudioPath = baseDir + "/temp/waveform_" + System.currentTimeMillis() + ".mp3";
        File audioFile = cloudflareR2Service.downloadFile(audioPath, tempAudioPath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
        }

        // Generate PCM data
        File tempPcmFile = new File(baseDir, "temp/waveform_" + projectId + "_" + System.currentTimeMillis() + ".pcm");
        File tempDir = new File(baseDir, "temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(audioFile.getAbsolutePath());
        command.add("-f");
        command.add("s16le");
        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add("44100");
        command.add("-y");
        command.add(tempPcmFile.getAbsolutePath());

        executeFFmpegCommand(command);

        // Read PCM data and compute peaks
        List<Float> peaks = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(tempPcmFile)) {
            byte[] buffer = new byte[4096];
            int samplesPerPeak = 44100 / 100;
            int sampleCount = 0;
            float maxAmplitude = 0;

            while (fis.read(buffer) != -1) {
                for (int i = 0; i < buffer.length; i += 2) {
                    short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    float amplitude = Math.abs(sample / 32768.0f);
                    maxAmplitude = Math.max(maxAmplitude, amplitude);
                    sampleCount++;

                    if (sampleCount >= samplesPerPeak) {
                        peaks.add(maxAmplitude);
                        maxAmplitude = 0;
                        sampleCount = 0;
                    }
                }
            }
            if (sampleCount > 0) {
                peaks.add(maxAmplitude);
            }
        } finally {
            Files.deleteIfExists(tempPcmFile.toPath());
        }

        // Create JSON structure
        Map<String, Object> waveformData = new HashMap<>();
        waveformData.put("sampleRate", 100);
        waveformData.put("peaks", peaks);

        // Save JSON to temporary file
        String waveformFileName = "waveform_" + audioFile.getName().replaceAll("[^a-zA-Z0-9.]", "_") + ".json";
        File waveformFile = new File(baseDir + "/temp/" + waveformFileName);
        objectMapper.writeValue(waveformFile, waveformData);

        String r2WaveformPath = "audio/projects/" + projectId + "/waveforms/" + waveformFileName;
        cloudflareR2Service.uploadFile(waveformFile, r2WaveformPath);

        // Clean up
        Files.deleteIfExists(waveformFile.toPath());
        Files.deleteIfExists(audioFile.toPath());

        return r2WaveformPath;
    }
    public Map<String, String> exportProject(String sessionId) throws IOException, InterruptedException {
        Project project = projectRepository.findByEditSession(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        // Create output filename
        String outputFileName = project.getName().replaceAll("[^a-zA-Z0-9]", "_") + "_"
                + System.currentTimeMillis() + ".mp4";
        String r2Path = "exports/projects/" + project.getId() + "/" + outputFileName;

        // Create temporary local file path
        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDir = baseDirPath.resolve("temp/exports").toAbsolutePath().normalize();
        if (!Files.exists(tempDir)) Files.createDirectories(tempDir);
        Path tempOutputFile = tempDir.resolve(outputFileName);

        try {
            // Render the final video to the temporary local file
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);
            renderFinalVideo(timelineState, tempOutputFile.toAbsolutePath().toString(), project.getWidth(), project.getHeight(), project.getFps(), project.getId());

            // Upload to Cloudflare R2
            logger.info("Uploading exported video to R2: r2Path={}", r2Path);
            cloudflareR2Service.uploadFile(tempOutputFile.toFile(), r2Path);

            // Generate presigned download URL (valid for 1 hour)
            String downloadUrl = cloudflareR2Service.generatePresignedUrl(r2Path, 3600);

            // Create export link details
            ExportLinkDetails exportLink = new ExportLinkDetails();
            exportLink.setFileName(outputFileName);
            exportLink.setDownloadUrl(downloadUrl);
            exportLink.setR2Path(r2Path);
            exportLink.setCreatedAt(LocalDateTime.now());
            exportLink.setExpiresAt(LocalDateTime.now().plusHours(1));

            // Load existing export links from exportsJson
            List<ExportLinkDetails> exportLinks = new ArrayList<>();
            if (project.getExportsJson() != null && !project.getExportsJson().isEmpty()) {
                exportLinks = objectMapper.readValue(project.getExportsJson(), new TypeReference<List<ExportLinkDetails>>() {});
            }

            // Add new export link
            exportLinks.add(exportLink);

            // Save updated export links back to exportsJson
            project.setExportsJson(objectMapper.writeValueAsString(exportLinks));
            project.setStatus("EXPORTED");
            project.setLastModified(LocalDateTime.now());
            projectRepository.save(project);

            // Return metadata
            Map<String, String> result = new HashMap<>();
            result.put("fileName", outputFileName);
            result.put("downloadUrl", downloadUrl);
            result.put("r2Path", r2Path);
            return result;
        } finally {
            // Clean up temporary file
            if (Files.exists(tempOutputFile)) {
                Files.delete(tempOutputFile);
                logger.debug("Deleted temporary export file: {}", tempOutputFile.toAbsolutePath());
            }
        }
    }
    public String renderFinalVideo(TimelineState timelineState, String outputPath, int canvasWidth, int canvasHeight, Float fps, Long projectId)
            throws IOException, InterruptedException {
        System.out.println("Rendering final video to: " + outputPath);

        // Use provided canvas dimensions or fallback to timelineState values
        if (timelineState.getCanvasWidth() != null) canvasWidth = timelineState.getCanvasWidth();
        if (timelineState.getCanvasHeight() != null) canvasHeight = timelineState.getCanvasHeight();

        // Create temporary directory for batch files
        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        if (!Files.exists(baseDirPath)) Files.createDirectories(baseDirPath);
        Path tempDir = baseDirPath.resolve("temp").toAbsolutePath().normalize();
        if (!Files.exists(tempDir)) Files.createDirectories(tempDir);

        // Calculate total video duration
        double totalDuration = Math.max(
                timelineState.getSegments().stream().mapToDouble(VideoSegment::getTimelineEndTime).max().orElse(0.0),
                Math.max(
                        timelineState.getImageSegments().stream().mapToDouble(ImageSegment::getTimelineEndTime).max().orElse(0.0),
                        Math.max(
                                timelineState.getTextSegments().stream().mapToDouble(TextSegment::getTimelineEndTime).max().orElse(0.0),
                                timelineState.getAudioSegments().stream().mapToDouble(AudioSegment::getTimelineEndTime).max().orElse(0.0)
                        )
                )
        );
        System.out.println("Total video duration: " + totalDuration + " seconds");

        // Define batch size (e.g., 8 seconds)
        double batchSize = 8.0; // Adjustable based on server capacity
        List<String> tempVideoFiles = new ArrayList<>();
        List<File> tempTextFiles = new ArrayList<>();

        try {
            // Process video in batches
            for (double startTime = 0; startTime < totalDuration; startTime += batchSize) {
                double endTime = Math.min(startTime + batchSize, totalDuration);
                String tempOutput = tempDir.resolve("batch_" + startTime + ".mp4").toAbsolutePath().toString();
                tempVideoFiles.add(tempOutput);
                renderBatch(timelineState, tempOutput, canvasWidth, canvasHeight, fps, projectId, startTime, endTime, tempTextFiles);
            }

            // Concatenate all batch files into the final video
            concatenateBatches(tempVideoFiles, outputPath, fps != null ? fps : 30);

        } finally {
            // Clean up temporary text PNGs
            for (File tempFile : tempTextFiles) {
                if (tempFile.exists()) {
                    try {
                        Files.delete(tempFile.toPath());
                        System.out.println("Deleted temporary text PNG: " + tempFile.getAbsolutePath());
                    } catch (IOException e) {
                        System.err.println("Failed to delete temporary text PNG " + tempFile.getAbsolutePath() + ": " + e.getMessage());
                    }
                }
            }
            // Clean up temporary batch videos
            for (String tempVideo : tempVideoFiles) {
                Path tempFile = Paths.get(tempVideo);
                if (Files.exists(tempFile)) {
                    try {
                        Files.delete(tempFile);
                        System.out.println("Deleted temporary batch video: " + tempFile.toAbsolutePath());
                    } catch (IOException e) {
                        System.err.println("Failed to delete temporary batch video " + tempFile.toAbsolutePath() + ": " + e.getMessage());
                    }
                }
            }
        }

        return outputPath;
    }

    private void renderBatch(TimelineState timelineState, String outputPath, int canvasWidth, int canvasHeight, Float fps,
                             Long projectId, double batchStart, double batchEnd, List<File> tempTextFiles)
            throws IOException, InterruptedException {
        double batchDuration = batchEnd - batchStart;
        System.out.println("Rendering batch from " + batchStart + " to " + batchEnd + " seconds");

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        StringBuilder filterComplex = new StringBuilder();
        Map<String, String> videoInputIndices = new HashMap<>();
        Map<String, String> audioInputIndices = new HashMap<>();
        Map<String, String> textInputIndices = new HashMap<>();
        List<File> tempFiles = new ArrayList<>(); // Track all temp files for cleanup
        int inputCount = 0;

        // Ensure baseDir is absolute
        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        if (!Files.exists(baseDirPath)) {
            Files.createDirectories(baseDirPath);
        }

        // Create temp directory
        Path tempDir = baseDirPath.resolve("temp").toAbsolutePath().normalize();
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        // Initialize black background for the batch
        filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                .append(":d=").append(String.format("%.6f", batchDuration)).append("[base];");

        // Filter segments that overlap with the batch
        List<VideoSegment> relevantVideoSegments = timelineState.getSegments().stream()
                .filter(vs -> vs.getTimelineStartTime() < batchEnd && vs.getTimelineEndTime() > batchStart)
                .collect(Collectors.toList());
        List<ImageSegment> relevantImageSegments = timelineState.getImageSegments().stream()
                .filter(is -> is.getTimelineStartTime() < batchEnd && is.getTimelineEndTime() > batchStart)
                .collect(Collectors.toList());
        List<TextSegment> relevantTextSegments = timelineState.getTextSegments().stream()
                .filter(ts -> ts.getTimelineStartTime() < batchEnd && ts.getTimelineEndTime() > batchStart)
                .collect(Collectors.toList());
        List<AudioSegment> relevantAudioSegments = timelineState.getAudioSegments().stream()
                .filter(as -> as.getTimelineStartTime() < batchEnd && as.getTimelineEndTime() > batchStart)
                .collect(Collectors.toList());

        // Add inputs for relevant video segments
        for (VideoSegment vs : relevantVideoSegments) {
            String r2Path = vs.getSourceVideoPath();
            String localFileName = vs.getId() + "_" + System.currentTimeMillis() + "_" + new File(r2Path).getName();
            Path localPath = tempDir.resolve("videos").resolve(localFileName).toAbsolutePath().normalize();
            Files.createDirectories(localPath.getParent());
            logger.info("Downloading video from R2: {} to {}", r2Path, localPath);
            File downloadedFile = cloudflareR2Service.downloadFileWithRetry(r2Path, localPath.toString(), 3);
            if (!Files.exists(localPath)) {
                throw new IOException("Video file not downloaded: " + localPath);
            }
            tempFiles.add(downloadedFile);
            command.add("-i");
            command.add(localPath.toString());
            videoInputIndices.put(vs.getId(), String.valueOf(inputCount));
            audioInputIndices.put(vs.getId(), String.valueOf(inputCount));
            inputCount++;
        }

        // Add inputs for relevant image segments
        for (ImageSegment is : relevantImageSegments) {
            String r2Path = is.getImagePath();
            String localFileName = is.getId() + "_" + System.currentTimeMillis() + "_" + new File(r2Path).getName();
            Path localPath = tempDir.resolve("images").resolve(localFileName).toAbsolutePath().normalize();
            Files.createDirectories(localPath.getParent());
            if (!is.isElement()) {
                logger.info("Downloading image from R2: {} to {}", r2Path, localPath);
                File downloadedFile = cloudflareR2Service.downloadFileWithRetry(r2Path, localPath.toString(), 3);
                if (!Files.exists(localPath)) {
                    throw new IOException("Image file not downloaded: " + localPath);
                }
                tempFiles.add(downloadedFile);
            } else {
                Path globalPath = Paths.get(globalElementsDirectory, new File(r2Path).getName()).toAbsolutePath().normalize();
                Files.copy(globalPath, localPath, StandardCopyOption.REPLACE_EXISTING);
                if (!Files.exists(localPath)) {
                    throw new IOException("Image file not available: " + localPath);
                }
                tempFiles.add(localPath.toFile());
            }
            command.add("-loop");
            command.add("1");
            command.add("-i");
            command.add(localPath.toString());
            videoInputIndices.put(is.getId(), String.valueOf(inputCount++));
        }

        // Add inputs for relevant text segments
        for (TextSegment ts : relevantTextSegments) {
            if (ts.getText() == null || ts.getText().trim().isEmpty()) {
                System.err.println("Skipping text segment " + ts.getId() + ": empty text");
                continue;
            }
            String textPngPath = generateTextPng(ts, tempDir.toFile(), canvasWidth, canvasHeight);
            Path textPngFile = Paths.get(textPngPath).toAbsolutePath().normalize();
            if (!Files.exists(textPngFile)) {
                throw new IOException("Text PNG file not generated: " + textPngFile);
            }
            tempFiles.add(textPngFile.toFile());
            tempTextFiles.add(textPngFile.toFile());
            command.add("-loop");
            command.add("1");
            command.add("-i");
            command.add(textPngFile.toString());
            textInputIndices.put(ts.getId(), String.valueOf(inputCount++));
        }

        // Add inputs for relevant audio segments
        for (AudioSegment as : relevantAudioSegments) {
            String r2Path = as.getAudioPath();
            String localFileName = as.getId() + "_" + System.currentTimeMillis() + "_" + new File(r2Path).getName();
            Path localPath = tempDir.resolve("audio").resolve(localFileName).toAbsolutePath().normalize();
            Files.createDirectories(localPath.getParent());
            logger.info("Downloading audio from R2: {} to {}", r2Path, localPath);
            File downloadedFile = cloudflareR2Service.downloadFileWithRetry(r2Path, localPath.toString(), 3);
            if (!Files.exists(localPath)) {
                throw new IOException("Audio file not downloaded: " + localPath);
            }
            tempFiles.add(downloadedFile);
            command.add("-i");
            command.add(localPath.toString());
            audioInputIndices.put(as.getId(), String.valueOf(inputCount++));
        }

        // Sort segments by layer
        List<Object> allSegments = new ArrayList<>();
        allSegments.addAll(relevantVideoSegments);
        allSegments.addAll(relevantImageSegments);
        allSegments.addAll(relevantTextSegments);
        allSegments.sort(Comparator.comparingInt(segment -> {
            if (segment instanceof VideoSegment) return ((VideoSegment) segment).getLayer();
            if (segment instanceof ImageSegment) return ((ImageSegment) segment).getLayer();
            if (segment instanceof TextSegment) return ((TextSegment) segment).getLayer();
            return 0;
        }));

        String lastOutput = "base";
        int overlayCount = 0;

        for (Object segment : allSegments) {
            String outputLabel = "ov" + overlayCount++;
            double segmentStart = 0, segmentEnd = batchDuration;

            if (segment instanceof VideoSegment) {
                VideoSegment vs = (VideoSegment) segment;
                String inputIdx = videoInputIndices.get(vs.getId());

                // Adjust timings for the batch
                double timelineStart = Math.max(vs.getTimelineStartTime(), batchStart);
                double timelineEnd = Math.min(vs.getTimelineEndTime(), batchEnd);
                segmentStart = timelineStart - batchStart;
                segmentEnd = timelineEnd - batchStart;

                // Calculate source time offset considering speed
                double speed = vs.getSpeed() != null ? vs.getSpeed() : 1.0;
                double speedFactor = 1.0 / speed; // Inverse for faster playback
                // Source time relative to the segment's start, scaled by speed
                double sourceStart = vs.getStartTime() + (timelineStart - vs.getTimelineStartTime()) * speed;
                double sourceEnd = vs.getStartTime() + (timelineEnd - vs.getTimelineStartTime()) * speed;

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=").append(String.format("%.6f", sourceStart)).append(":").append(String.format("%.6f", sourceEnd)).append(",");
                // Adjust PTS to account for speed and position within batch
                filterComplex.append("setpts=").append(String.format("%.6f", speedFactor)).append("*(PTS-STARTPTS),");
                filterComplex.append("setpts=PTS-STARTPTS+").append(String.format("%.6f", segmentStart)).append("/TB,");

                // Apply crop, filters, transitions, keyframes, etc. (unchanged from original)
                double cropL = vs.getCropL() != null ? vs.getCropL() : 0.0;
                double cropR = vs.getCropR() != null ? vs.getCropR() : 0.0;
                double cropT = vs.getCropT() != null ? vs.getCropT() : 0.0;
                double cropB = vs.getCropB() != null ? vs.getCropB() : 0.0;

                if (cropL < 0 || cropL > 100 || cropR < 0 || cropR > 100 || cropT < 0 || cropT > 100 || cropB < 0 || cropB > 100) {
                    throw new IllegalArgumentException("Crop percentages must be between 0 and 100 for segment " + vs.getId());
                }
                if (cropL + cropR >= 100 || cropT + cropB >= 100) {
                    throw new IllegalArgumentException("Total crop percentages must be less than 100 for segment " + vs.getId());
                }

                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(vs.getId()))
                        .collect(Collectors.toList());
                boolean hasVignette = false;
                double vignetteValue = 0.0;

                for (Filter filter : segmentFilters) {
                    if (filter == null || filter.getFilterName() == null || filter.getFilterName().trim().isEmpty()) {
                        System.err.println("Skipping invalid filter for segment " + vs.getId() + ": null or empty filter name");
                        continue;
                    }
                    String filterName = filter.getFilterName().toLowerCase().trim();
                    String filterValue = filter.getFilterValue() != null ? String.valueOf(filter.getFilterValue()) : "";
                    if (filterValue.isEmpty() && !Arrays.asList("grayscale", "sepia", "invert").contains(filterName)) {
                        System.err.println("Skipping filter " + filterName + " for segment " + vs.getId() + ": empty filter value");
                        continue;
                    }
                    try {
                        switch (filterName) {
                            case "brightness":
                                double brightness = Double.parseDouble(filterValue);
                                if (brightness >= -1 && brightness <= 1) {
                                    double cssBrightnessMultiplier = 1 + brightness;
                                    if (cssBrightnessMultiplier <= 0) {
                                        filterComplex.append("lutrgb=r=0:g=0:b=0,");
                                        break;
                                    }
                                    if (cssBrightnessMultiplier == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgba,");
                                    String lut = String.format(
                                            "lutrgb=r='val*%f':g='val*%f':b='val*%f',",
                                            cssBrightnessMultiplier, cssBrightnessMultiplier, cssBrightnessMultiplier
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=rgba,");
                                }
                                break;
                            case "contrast":
                                double contrast = Double.parseDouble(filterValue);
                                if (contrast >= 0 && contrast <= 2) {
                                    if (contrast == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgba,");
                                    double offset = 128 * (1 - contrast);
                                    String lut = String.format(
                                            "lutrgb=r='clip(val*%f+%f,0,255)':g='clip(val*%f+%f,0,255)':b='clip(val*%f+%f,0,255)',",
                                            contrast, offset, contrast, offset, contrast, offset
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=rgba,");
                                }
                                break;
                            case "saturation":
                                double saturation = Double.parseDouble(filterValue);
                                if (saturation >= 0 && saturation <= 2) {
                                    if (Math.abs(saturation - 1.0) < 0.01) {
                                        System.out.println("Skipping saturation filter for segment " + vs.getId() + ": value ≈ 1 (" + saturation + ")");
                                        break;
                                    }
                                    filterComplex.append("eq=saturation=").append(String.format("%.2f", saturation)).append(",");
                                }
                                break;
                            case "hue":
                                double hue = Double.parseDouble(filterValue);
                                if (hue >= -180 && hue <= 180) {
                                    if (hue == 0.0) {
                                        break;
                                    }
                                    filterComplex.append("hue=h=").append(String.format("%.1f", hue)).append(",");
                                }
                                break;
                            case "grayscale":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("hue=s=0,");
                                }
                                break;
                            case "invert":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("negate,");
                                }
                                break;
                            case "flip":
                                if (filterValue.equals("horizontal")) {
                                    filterComplex.append("hflip,");
                                } else if (filterValue.equals("vertical")) {
                                    filterComplex.append("vflip,");
                                } else if (filterValue.equals("both")) {
                                    filterComplex.append("hflip,vflip,");
                                }
                                break;
                            case "vignette":
                                double vignette = Double.parseDouble(filterValue);
                                if (vignette >= 0 && vignette <= 1 && vignette > 0.01) {
                                    hasVignette = true;
                                    vignetteValue = vignette;
                                }
                                break;
                            case "blur":
                                double blurValue = Double.parseDouble(filterValue);
                                if (blurValue >= 0 && blurValue <= 10) {
                                    if (blurValue > 0) {
                                        double radius = blurValue * 10.0;
                                        filterComplex.append("boxblur=").append(String.format("%.2f", radius)).append(",");
                                        System.out.println("Blur filter applied to video segment " + vs.getId() + ": blurValue=" + blurValue + ", radius=" + radius);
                                    }
                                }
                                break;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid filter value for " + filterName + " in segment " + vs.getId() + ": " + filterValue);
                    }
                }

                if (hasVignette) {
                    double intensity = vignetteValue;
                    double angle = intensity * (Math.PI / 2);
                    filterComplex.append("vignette=angle=").append(String.format("%.6f", angle)).append(":mode=forward,");
                }

                filterComplex.append("format=rgba,");

                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> t.getSegmentId() != null && t.getSegmentId().equals(vs.getId()))
                        .filter(t -> t.getLayer() == vs.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, timelineStart, timelineEnd, canvasWidth, canvasHeight, batchStart, batchDuration);
                boolean hasTransitionCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasTransitionCrop) {
                    double transStart = timelineStart - batchStart;
                    double transEnd = Math.min(timelineStart + 1.0, timelineEnd) - batchStart;
                    filterComplex.append("crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'")
                            .append(",");
                }

                // Apply rotation
                StringBuilder rotationExpr = new StringBuilder();
                Double defaultRotation = vs.getRotation() != null ? vs.getRotation() : 0.0;
                rotationExpr.append(String.format("%.6f", Math.toRadians(defaultRotation)));

                if (!rotationExpr.toString().equals("0.000000")) {
                    filterComplex.append("rotate=a=").append(rotationExpr)
                            .append(":ow='hypot(iw,ih)'")
                            .append(":oh='hypot(iw,ih)'")
                            .append(":c=0x00000000,");
                }

                filterComplex.append("format=rgba,");

                double opacity = vs.getOpacity() != null ? vs.getOpacity() : 1.0;
                if (opacity < 1.0) {
                    filterComplex.append("lutrgb=a='val*").append(String.format("%.6f", opacity)).append("',");
                    filterComplex.append("format=rgba,");
                }

                if (cropL > 0 || cropR > 0 || cropT > 0 || cropB > 0) {
                    String cropWidth = String.format("iw*(1-%.6f-%.6f)", cropL / 100.0, cropR / 100.0);
                    String cropHeight = String.format("ih*(1-%.6f-%.6f)", cropT / 100.0, cropB / 100.0);
                    String cropX = String.format("iw*%.6f", cropL / 100.0);
                    String cropY = String.format("ih*%.6f", cropT / 100.0);

                    filterComplex.append("crop=").append(cropWidth).append(":")
                            .append(cropHeight).append(":")
                            .append(cropX).append(":")
                            .append(cropY).append(",");
                    filterComplex.append("format=rgba,");
                    filterComplex.append("pad=iw/(1-").append(String.format("%.6f", (cropL + cropR) / 100.0)).append("):")
                            .append("ih/(1-").append(String.format("%.6f", (cropT + cropB) / 100.0)).append("):")
                            .append("iw*").append(String.format("%.6f", cropL / (100.0 - cropL - cropR))).append(":")
                            .append("ih*").append(String.format("%.6f", cropT / (100.0 - cropT - cropB))).append(":")
                            .append("color=0x00000000,");
                }

                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = vs.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = vs.getScale() != null ? vs.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    // Find the value at batchStart by interpolating keyframes
                    double startValue = defaultScale;
                    double segmentTime = batchStart - vs.getTimelineStartTime();
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == scaleKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    }
                    scaleExpr.append(String.format("%.6f", startValue));
                    // Add keyframes that affect the batch
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                scaleExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    scaleExpr.append(String.format("%.6f", defaultScale));
                }

                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    scaleExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                }

                filterComplex.append("scale=w='iw*").append(scaleExpr).append("':h='ih*").append(scaleExpr).append("':eval=frame[scaled").append(outputLabel).append("];");

                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = vs.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = vs.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseX;
                    double segmentTime = batchStart - vs.getTimelineStartTime();
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posXKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    }
                    xExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                xExpr.insert(0, "(W/2)+(").append(")-(w/2)");

                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = vs.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = vs.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseY;
                    double segmentTime = batchStart - vs.getTimelineStartTime();
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posYKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    }
                    yExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                String yTransitionOffset = transitionOffsets.get("y");
                if (!yTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                yExpr.insert(0, "(H/2)+(").append(")-(h/2)");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=auto");
                filterComplex.append(":enable='between(t,").append(segmentStart).append(",").append(segmentEnd).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;
            } else if (segment instanceof ImageSegment) {
                ImageSegment is = (ImageSegment) segment;
                String inputIdx = videoInputIndices.get(is.getId());

                double timelineStart = Math.max(is.getTimelineStartTime(), batchStart);
                double timelineEnd = Math.min(is.getTimelineEndTime(), batchEnd);
                segmentStart = timelineStart - batchStart;
                segmentEnd = timelineEnd - batchStart;

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(String.format("%.6f", segmentEnd - segmentStart)).append(",");
                filterComplex.append("setpts=PTS-STARTPTS+").append(String.format("%.6f", segmentStart)).append("/TB,");

                double cropL = is.getCropL() != null ? is.getCropL() : 0.0;
                double cropR = is.getCropR() != null ? is.getCropR() : 0.0;
                double cropT = is.getCropT() != null ? is.getCropT() : 0.0;
                double cropB = is.getCropB() != null ? is.getCropB() : 0.0;

                if (cropL < 0 || cropL > 100 || cropR < 0 || cropR > 100 || cropT < 0 || cropT > 100 || cropB < 0 || cropB > 100) {
                    throw new IllegalArgumentException("Crop percentages must be between 0 and 100 for segment " + is.getId());
                }
                if (cropL + cropR >= 100 || cropT + cropB >= 100) {
                    throw new IllegalArgumentException("Total crop percentages must be less than 100 for segment " + is.getId());
                }

                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(is.getId()))
                        .collect(Collectors.toList());
                boolean hasVignette = false;
                double vignetteValue = 0.0;

                for (Filter filter : segmentFilters) {
                    if (filter == null || filter.getFilterName() == null || filter.getFilterName().trim().isEmpty()) {
                        System.err.println("Skipping invalid filter for segment " + is.getId() + ": null or empty filter name");
                        continue;
                    }
                    String filterName = filter.getFilterName().toLowerCase().trim();
                    String filterValue = filter.getFilterValue() != null ? String.valueOf(filter.getFilterValue()) : "";
                    if (filterValue.isEmpty() && !Arrays.asList("grayscale", "sepia", "invert").contains(filterName)) {
                        System.err.println("Skipping filter " + filterName + " for segment " + is.getId() + ": empty filter value");
                        continue;
                    }
                    try {
                        switch (filterName) {
                            case "brightness":
                                double brightness = Double.parseDouble(filterValue);
                                if (brightness >= -1 && brightness <= 1) {
                                    double cssBrightnessMultiplier = 1 + brightness;
                                    if (cssBrightnessMultiplier <= 0) {
                                        filterComplex.append("lutrgb=r=0:g=0:b=0,");
                                        break;
                                    }
                                    if (cssBrightnessMultiplier == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgba,");
                                    String lut = String.format(
                                            "lutrgb=r='val*%f':g='val*%f':b='val*%f':a='val',",
                                            cssBrightnessMultiplier, cssBrightnessMultiplier, cssBrightnessMultiplier
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=rgba,");
                                }
                                break;
                            case "contrast":
                                double contrast = Double.parseDouble(filterValue);
                                if (contrast >= 0 && contrast <= 2) {
                                    if (contrast == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgba,");
                                    double offset = 128 * (1 - contrast);
                                    String lut = String.format(
                                            "lutrgb=r='clip(val*%f+%f,0,255)':g='clip(val*%f+%f,0,255)':b='clip(val*%f+%f,0,255)':a='val',",
                                            contrast, offset, contrast, offset, contrast, offset
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=rgba,");
                                }
                                break;
                            case "saturation":
                                double saturation = Double.parseDouble(filterValue);
                                if (saturation >= 0 && saturation <= 2) {
                                    if (Math.abs(saturation - 1.0) < 0.01) {
                                        System.out.println("Skipping saturation filterCuba filter for segment " + is.getId() + ": value ≈ 1 (" + saturation + ")");
                                        break;
                                    }
                                    filterComplex.append("eq=saturation=").append(String.format("%.2f", saturation)).append(",");
                                }
                                break;
                            case "hue":
                                double hue = Double.parseDouble(filterValue);
                                if (hue >= -180 && hue <= 180) {
                                    if (hue == 0.0) {
                                        break;
                                    }
                                    filterComplex.append("hue=h=").append(String.format("%.1f", hue)).append(",");
                                }
                                break;
                            case "grayscale":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("hue=s=0,");
                                }
                                break;
                            case "invert":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("negate,");
                                }
                                break;
                            case "vignette":
                                double vignette = Double.parseDouble(filterValue);
                                if (vignette >= 0 && vignette <= 1 && vignette > 0.01) {
                                    hasVignette = true;
                                    vignetteValue = vignette;
                                }
                                break;
                            case "blur":
                                double blurValue = Double.parseDouble(filterValue);
                                if (blurValue >= 0 && blurValue <= 1) {
                                    if (blurValue > 0) {
                                        double radius = blurValue * 10.0;
                                        filterComplex.append("boxblur=").append(String.format("%.2f", radius)).append(",");
                                    }
                                }
                                break;
                            case "rotate":
                            case "flip":
                                break;
                            default:
                                System.err.println("Unsupported filter: " + filterName + " for segment " + is.getId());
                                break;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid filter value for " + filterName + " in segment " + is.getId() + ": " + filterValue);
                    }
                }

                if (hasVignette) {
                    double intensity = vignetteValue;
                    double angle = intensity * (Math.PI / 2);
                    filterComplex.append("vignette=angle=").append(String.format("%.6f", angle)).append(":mode=forward,");
                }

                filterComplex.append("format=rgba,");

                Filter flipFilter = segmentFilters.stream()
                        .filter(f -> "flip".equalsIgnoreCase(f.getFilterName()))
                        .findFirst()
                        .orElse(null);
                if (flipFilter != null) {
                    String flipValue = flipFilter.getFilterValue();
                    if (flipValue.equals("horizontal")) {
                        filterComplex.append("hflip,");
                    } else if (flipValue.equals("vertical")) {
                        filterComplex.append("vflip,");
                    } else if (flipValue.equals("both")) {
                        filterComplex.append("hflip,vflip,");
                    }
                }

                StringBuilder rotationExpr = new StringBuilder();
                Double defaultRotation = is.getRotation() != null ? is.getRotation() : 0.0;
                rotationExpr.append(String.format("%.6f", Math.toRadians(defaultRotation)));

                if (!rotationExpr.toString().equals("0.000000")) {
                    filterComplex.append("rotate=a=").append(rotationExpr)
                            .append(":ow='hypot(iw,ih)'")
                            .append(":oh='hypot(iw,ih)'")
                            .append(":c=0x00000000,");
                }

                filterComplex.append("format=rgba,");

                double opacity = is.getOpacity() != null ? is.getOpacity() : 1.0;
                if (opacity < 1.0) {
                    filterComplex.append("lutrgb=a='val*").append(String.format("%.6f", opacity)).append("',");
                    filterComplex.append("format=rgba,");
                }

                if (cropL > 0 || cropR > 0 || cropT > 0 || cropB > 0) {
                    String cropWidth = String.format("iw*(1-%.6f-%.6f)", cropL / 100.0, cropR / 100.0);
                    String cropHeight = String.format("ih*(1-%.6f-%.6f)", cropT / 100.0, cropB / 100.0);
                    String cropX = String.format("iw*%.6f", cropL / 100.0);
                    String cropY = String.format("ih*%.6f", cropT / 100.0);

                    filterComplex.append("crop=").append(cropWidth).append(":")
                            .append(cropHeight).append(":")
                            .append(cropX).append(":")
                            .append(cropY).append(",");
                    filterComplex.append("format=rgba,");
                    filterComplex.append("pad=iw/(1-").append(String.format("%.6f", (cropL + cropR) / 100.0)).append("):")
                            .append("ih/(1-").append(String.format("%.6f", (cropT + cropB) / 100.0)).append("):")
                            .append("iw*").append(String.format("%.6f", cropL / (100.0 - cropL - cropR))).append(":")
                            .append("ih*").append(String.format("%.6f", cropT / (100.0 - cropT - cropB))).append(":")
                            .append("color=0x00000000,");
                }

                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> t.getSegmentId() != null && t.getSegmentId().equals(is.getId()))
                        .filter(t -> t.getLayer() == is.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, timelineStart, timelineEnd, canvasWidth, canvasHeight, batchStart, batchDuration);
                boolean hasTransitionCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasTransitionCrop) {
                    double transStart = timelineStart - batchStart;
                    double transEnd = Math.min(timelineStart + 1.0, timelineEnd) - batchStart;
                    filterComplex.append("crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'")
                            .append(",");
                }

                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = is.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = is.getScale() != null ? is.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = defaultScale;
                    double segmentTime = batchStart - is.getTimelineStartTime();
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == scaleKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    }
                    scaleExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                scaleExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    scaleExpr.append(String.format("%.6f", defaultScale));
                }

                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    scaleExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                }

                filterComplex.append("scale=w='iw*").append(scaleExpr).append("':h='ih*").append(scaleExpr).append("':eval=frame[scaled").append(outputLabel).append("];");

                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = is.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = is.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseX;
                    double segmentTime = batchStart - is.getTimelineStartTime();
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posXKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    }
                    xExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                xExpr.insert(0, "(W/2)+(").append(")-(w/2)");

                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = is.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = is.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseY;
                    double segmentTime = batchStart - is.getTimelineStartTime();
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posYKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    }
                    yExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                String yTransitionOffset = transitionOffsets.get("y");
                if (!yTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                yExpr.insert(0, "(H/2)+(").append(")-(h/2)");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=auto");
                filterComplex.append(":enable='between(t,").append(segmentStart).append(",").append(segmentEnd).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;
            } else if (segment instanceof TextSegment) {
                TextSegment ts = (TextSegment) segment;
                String inputIdx = textInputIndices.get(ts.getId());
                if (inputIdx == null) {
                    System.err.println("Skipping text segment " + ts.getId() + ": no valid PNG input");
                    continue;
                }

                double timelineStart = Math.max(ts.getTimelineStartTime(), batchStart);
                double timelineEnd = Math.min(ts.getTimelineEndTime(), batchEnd);
                segmentStart = timelineStart - batchStart;
                segmentEnd = timelineEnd - batchStart;

                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> t.getSegmentId() != null && t.getSegmentId().equals(ts.getId()))
                        .filter(t -> t.getLayer() == ts.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, timelineStart, timelineEnd, canvasWidth, canvasHeight, batchStart, batchDuration);
                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(String.format("%.6f", segmentEnd - segmentStart)).append(",");
                filterComplex.append("setpts=PTS-STARTPTS+").append(String.format("%.6f", segmentStart)).append("/TB,");

                boolean hasCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasCrop) {
                    double transStart = timelineStart - batchStart;
                    double transEnd = Math.min(timelineStart + 1.0, timelineEnd) - batchStart;
                    filterComplex.append("crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'")
                            .append(",");
                }

                StringBuilder rotationExpr = new StringBuilder();
                Double defaultRotation = ts.getRotation() != null ? ts.getRotation() : 0.0;
                rotationExpr.append(String.format("%.6f", Math.toRadians(defaultRotation)));

                if (!rotationExpr.toString().equals("0.000000")) {
                    filterComplex.append("rotate=").append(rotationExpr)
                            .append(":ow='hypot(iw,ih)'")
                            .append(":oh='hypot(iw,ih)'")
                            .append(":c=0x00000000,");
                }

                filterComplex.append("format=rgba,");

                double opacity = ts.getOpacity() != null ? ts.getOpacity() : 1.0;
                if (opacity < 1.0) {
                    filterComplex.append("lutrgb=a='val*").append(String.format("%.6f", opacity)).append("',");
                    filterComplex.append("format=rgba,");
                }

                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = ts.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = ts.getScale() != null ? ts.getScale() : 1.0;
                double resolutionMultiplier = canvasWidth >= 3840 ? 1.5 : 2.0;
                double baseScale = 1.0 / resolutionMultiplier;
                double maxScale = defaultScale;

                if (!scaleKeyframes.isEmpty()) {
                    maxScale = Math.max(
                            defaultScale,
                            scaleKeyframes.stream()
                                    .mapToDouble(kf -> ((Number) kf.getValue()).doubleValue())
                                    .max()
                                    .orElse(defaultScale)
                    );
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = defaultScale / maxScale;
                    double segmentTime = batchStart - ts.getTimelineStartTime();
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue() / maxScale;
                        double kfValue = ((Number) kf.getValue()).doubleValue() / maxScale;
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue() / maxScale;
                            break;
                        } else if (j == scaleKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue() / maxScale;
                    }
                    scaleExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue() / maxScale;
                        double kfValue = ((Number) kf.getValue()).doubleValue() / maxScale;
                        if (kfTime > prevTime) {
                            double timelinePrevTime = ts.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = ts.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                scaleExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    scaleExpr.append(String.format("%.6f", defaultScale / maxScale));
                }

                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    scaleExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                }

                filterComplex.append("scale=w='iw*").append(baseScale).append("*").append(scaleExpr)
                        .append("':h='ih*").append(baseScale).append("*").append(scaleExpr)
                        .append("':flags=lanczos:force_original_aspect_ratio=decrease:eval=frame[scaled").append(outputLabel).append("];");

                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = ts.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = ts.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseX;
                    double segmentTime = batchStart - ts.getTimelineStartTime();
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posXKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    }
                    xExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = ts.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = ts.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                xExpr.insert(0, "(W/2)+(").append(")-(w/2)");

                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = ts.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = ts.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double startValue = baseY;
                    double segmentTime = batchStart - ts.getTimelineStartTime();
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (segmentTime >= prevTime && segmentTime <= kfTime && kfTime > prevTime) {
                            double progress = (segmentTime - prevTime) / (kfTime - prevTime);
                            startValue = prevValue + (kfValue - prevValue) * Math.min(1, Math.max(0, progress));
                            break;
                        } else if (segmentTime < prevTime) {
                            startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                            break;
                        } else if (j == posYKeyframes.size() - 1 && segmentTime > kfTime) {
                            startValue = kfValue;
                        }
                    }
                    if (segmentTime < 0) {
                        startValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    }
                    yExpr.append(String.format("%.6f", startValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();
                        if (kfTime > prevTime) {
                            double timelinePrevTime = ts.getTimelineStartTime() + prevTime - batchStart;
                            double timelineKfTime = ts.getTimelineStartTime() + kfTime - batchStart;
                            if (timelineKfTime >= 0 && timelinePrevTime <= batchDuration) {
                                yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                        .append(",min(1,max(0,(t-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append(")/(")
                                        .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", Math.max(0, timelinePrevTime))).append("))))");
                            }
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                String yTransitionOffset = transitionOffsets.get("y");
                if (!yTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                yExpr.insert(0, "(H/2)+(").append(")-(h/2)");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=auto");
                filterComplex.append(":enable='between(t,").append(segmentStart).append(",").append(segmentEnd).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;
            }
        }

        List<String> audioOutputs = new ArrayList<>();
        int audioCount = 0;

        for (AudioSegment as : relevantAudioSegments) {
            String inputIdx = audioInputIndices.get(as.getId());
            if (inputIdx == null) {
                System.err.println("No input index found for audio segment " + as.getId());
                continue;
            }
            String audioOutput = "aa" + audioCount++;

            double timelineStart = Math.max(as.getTimelineStartTime(), batchStart);
            double timelineEnd = Math.min(as.getTimelineEndTime(), batchEnd);
            double segmentStart = timelineStart - batchStart;
            double segmentDuration = timelineEnd - timelineStart;
            double sourceStart = as.getStartTime() + (timelineStart - as.getTimelineStartTime());
            double sourceEnd = as.getStartTime() + (timelineEnd - as.getTimelineStartTime());

            if (sourceStart < 0 || sourceEnd <= sourceStart || segmentStart < 0 || segmentDuration <= 0) {
                System.err.println("Invalid timing for audio segment " + as.getId() + ": sourceStart=" + sourceStart + ", sourceEnd=" + sourceEnd + ", segmentStart=" + segmentStart);
                continue;
            }

            filterComplex.append("[").append(inputIdx).append(":a]");
            filterComplex.append("atrim=").append(String.format("%.6f", sourceStart)).append(":").append(String.format("%.6f", sourceEnd)).append(",");
            filterComplex.append("asetpts=PTS-STARTPTS+").append(String.format("%.6f", segmentStart)).append("/TB");

            List<Keyframe> volumeKeyframes = as.getKeyframes().getOrDefault("volume", new ArrayList<>());
            double defaultVolume = as.getVolume() != null ? as.getVolume() : 1.0;

            if (!volumeKeyframes.isEmpty()) {
                Collections.sort(volumeKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                List<Keyframe> validKeyframes = volumeKeyframes.stream()
                        .filter(kf -> {
                            double time = kf.getTime();
                            double value = ((Number) kf.getValue()).doubleValue();
                            return time >= 0 && time <= segmentDuration && value >= 0 && value <= 15;
                        })
                        .collect(Collectors.toList());

                if (!validKeyframes.isEmpty()) {
                    StringBuilder volumeExpr = new StringBuilder("volume=");
                    double lastValue = ((Number) validKeyframes.get(validKeyframes.size() - 1).getValue()).doubleValue();
                    double lastTime = validKeyframes.get(validKeyframes.size() - 1).getTime();

                    volumeExpr.append("'");
                    int conditionCount = 0;
                    for (int j = 0; j < validKeyframes.size(); j++) {
                        double startTime, endTime, startValue, endValue;

                        Keyframe currentKf = validKeyframes.get(j);
                        startTime = currentKf.getTime();
                        startValue = ((Number) currentKf.getValue()).doubleValue();

                        if (j < validKeyframes.size() - 1) {
                            Keyframe nextKf = validKeyframes.get(j + 1);
                            endTime = nextKf.getTime();
                            endValue = ((Number) nextKf.getValue()).doubleValue();
                        } else {
                            endTime = segmentDuration;
                            endValue = startValue;
                        }

                        if (startTime < endTime) {
                            double adjustedStartTime = (startTime) + segmentStart;
                            double adjustedEndTime = (endTime) + segmentStart;
                            if (adjustedStartTime < batchDuration && adjustedEndTime > 0) {
                                adjustedStartTime = Math.max(0, adjustedStartTime);
                                adjustedEndTime = Math.min(batchDuration, adjustedEndTime);
                                String condition = String.format("between(t,%.6f,%.6f)", adjustedStartTime, adjustedEndTime);
                                String valueExpr;
                                if (Math.abs(startValue - endValue) < 0.000001) {
                                    valueExpr = String.format("%.6f", startValue);
                                } else {
                                    String progress = String.format("(t-%.6f)/(%.6f-%.6f)", adjustedStartTime, adjustedEndTime, adjustedStartTime);
                                    valueExpr = String.format("%.6f+(%.6f-%.6f)*min(1,max(0,%s))", startValue, endValue, startValue, progress);
                                }
                                volumeExpr.append(String.format("if(%s,%s,", condition, valueExpr));
                                conditionCount++;
                            }
                        }
                    }

                    volumeExpr.append(String.format("%.6f", lastValue));
                    for (int j = 0; j < conditionCount; j++) {
                        volumeExpr.append(")");
                    }

                    volumeExpr.append("'");
                    volumeExpr.append(":eval=frame");
                    filterComplex.append(",").append(volumeExpr);
                } else {
                    filterComplex.append(",").append("volume=").append(String.format("%.6f", defaultVolume));
                }
            } else {
                filterComplex.append(",").append("volume=").append(String.format("%.6f", defaultVolume));
            }

            filterComplex.append("[").append(audioOutput).append("];");
            audioOutputs.add(audioOutput);
        }

        if (!audioOutputs.isEmpty()) {
            filterComplex.append("[").append(String.join("][", audioOutputs)).append("]");
            filterComplex.append("amix=inputs=").append(audioOutputs.size()).append(":duration=longest:dropout_transition=0:normalize=0[aout];");
        }

        filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout]");

        command.add("-filter_complex");
        command.add(filterComplex.toString());

        command.add("-map");
        command.add("[vout]");
        if (!audioOutputs.isEmpty()) {
            command.add("-map");
            command.add("[aout]");
        } else {
            command.add("-an");
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryslow");
        command.add("-b:v");
        command.add(canvasWidth >= 3840 ? "10M" : "5M");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-color_range");
        command.add("tv");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("320k");
        command.add("-ar");
        command.add("48000");
        command.add("-t");
        command.add(String.format("%.6f", batchDuration));
        command.add("-r");
        command.add(String.valueOf(fps != null ? fps : 30));
        command.add("-y");
        Path absoluteOutputPath = Paths.get(outputPath).toAbsolutePath().normalize();
        command.add(absoluteOutputPath.toString());


        // Execute FFmpeg command
        System.out.println("FFmpeg command for batch: " + String.join(" ", command));
        try {
            executeFFmpegCommand(command);
        } finally {
            // Clean up temporary files
            for (File tempFile : tempFiles) {
                try {
                    if (tempFile.exists()) {
                        Files.delete(tempFile.toPath());
                        System.out.println("Deleted temporary file: " + tempFile.getAbsolutePath());
                    }
                } catch (IOException e) {
                    System.err.println("Failed to delete temporary file " + tempFile.getAbsolutePath() + ": " + e.getMessage());
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
            // If only one batch, rename it to the output path
            Files.move(Paths.get(tempVideoFiles.get(0)), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // Create a text file listing the batch files
        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDir = baseDirPath.resolve("temp").toAbsolutePath().normalize();
        if (!Files.exists(tempDir)) Files.createDirectories(tempDir);
        Path concatListFile = tempDir.resolve("concat_list.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatListFile, StandardCharsets.UTF_8))) {
            for (String tempFile : tempVideoFiles) {
                // Use platform-agnostic paths
                writer.println("file '" + Paths.get(tempFile).toAbsolutePath().normalize().toString().replace("'", "\\'") + "'");
            }
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-f");
        command.add("concat");
        command.add("-safe");
        command.add("0");
        command.add("-i");
        command.add(concatListFile.toAbsolutePath().toString());
        command.add("-c");
        command.add("copy");
        command.add("-r");
        command.add(String.valueOf(fps));
        command.add("-y");
        command.add(Paths.get(outputPath).toAbsolutePath().normalize().toString());

        System.out.println("FFmpeg concatenation command: " + String.join(" ", command));
        try {
            executeFFmpegCommand(command);
        } finally {
            if (Files.exists(concatListFile)) {
                try {
                    Files.delete(concatListFile);
                    System.out.println("Deleted concat list file: " + concatListFile.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Failed to delete concat list file: " + e.getMessage());
                }
            }
        }
    }
    private String generateTextPng(TextSegment ts, File tempDir, int canvasWidth, int canvasHeight) throws IOException {
        // Resolution multiplier for high-quality text (1.5 for 4K, 2.0 for 1080p)
        final double RESOLUTION_MULTIPLIER = canvasWidth >= 3840 ? 1.5 : 2.0;
        // Scaling factor for border width to match frontend's typical scaleFactor
        final double BORDER_SCALE_FACTOR = canvasWidth >= 3840 ? 1.5 : 2.0;

        // Determine maximum scale from keyframes or default scale
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

        // Parse colors
        Color fontColor = parseColor(ts.getFontColor(), Color.WHITE, "font", ts.getId());
        Color bgColor = ts.getBackgroundColor() != null && !ts.getBackgroundColor().equals("transparent") ?
                parseColor(ts.getBackgroundColor(), null, "background", ts.getId()) : null;
        Color bgBorderColor = ts.getBackgroundBorderColor() != null && !ts.getBackgroundBorderColor().equals("transparent") ?
                parseColor(ts.getBackgroundBorderColor(), null, "border", ts.getId()) : null;
        Color textBorderColor = ts.getTextBorderColor() != null && !ts.getTextBorderColor().equals("transparent") ?
                parseColor(ts.getTextBorderColor(), null, "text border", ts.getId()) : null;

        // Load font with fixed base size of 24, scaled by maxScale and resolution multiplier
        double baseFontSize = 24.0 * maxScale * RESOLUTION_MULTIPLIER;
        Font font;
        try {
            font = Font.createFont(Font.TRUETYPE_FONT, new File(getFontPathByFamily(ts.getFontFamily())))
                    .deriveFont((float) baseFontSize);
        } catch (Exception e) {
            System.err.println("Failed to load font for text segment " + ts.getId() + ": " + ts.getFontFamily() + ", using Arial");
            font = new Font("Arial", Font.PLAIN, (int) baseFontSize);
        }

        // Get letter spacing and line spacing, then scale them
        double letterSpacing = ts.getLetterSpacing() != null ? ts.getLetterSpacing() : 0.0;
        double scaledLetterSpacing = letterSpacing * maxScale * RESOLUTION_MULTIPLIER;
        double lineSpacing = ts.getLineSpacing() != null ? ts.getLineSpacing() : 1.2; // Use TextSegment's lineSpacing
        double scaledLineSpacing = lineSpacing * baseFontSize; // Line spacing as multiplier of font size

        // Measure text with letter spacing
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = tempImage.createGraphics();
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        String[] lines = ts.getText().split("\n");
        int lineHeight = (int) scaledLineSpacing; // Use scaledLineSpacing for line height
        int totalTextHeight = lines.length > 1 ? (lines.length - 1) * lineHeight + fm.getAscent() + fm.getDescent() : fm.getAscent() + fm.getDescent();
        int maxTextWidth = 0;
        for (String line : lines) {
            // Calculate width with letter spacing
            int lineWidth = 0;
            for (int i = 0; i < line.length(); i++) {
                lineWidth += fm.charWidth(line.charAt(i));
                if (i < line.length() - 1) {
                    lineWidth += (int) scaledLetterSpacing;
                }
            }
            maxTextWidth = Math.max(maxTextWidth, lineWidth);
        }
        // Calculate text block height for centering
        int textBlockHeight = totalTextHeight;
        g2d.dispose();
        tempImage.flush();

        // Apply background dimensions and borders (aligned with frontend logic, using maxScale)
        int bgHeight = (int) ((ts.getBackgroundH() != null ? ts.getBackgroundH() : 0) * maxScale * RESOLUTION_MULTIPLIER);
        int bgWidth = (int) ((ts.getBackgroundW() != null ? ts.getBackgroundW() : 0) * maxScale * RESOLUTION_MULTIPLIER);
        int bgBorderWidth = (int) ((ts.getBackgroundBorderWidth() != null ? ts.getBackgroundBorderWidth() : 0) * maxScale * BORDER_SCALE_FACTOR);
        int borderRadius = (int) ((ts.getBackgroundBorderRadius() != null ? ts.getBackgroundBorderRadius() : 0) * maxScale * RESOLUTION_MULTIPLIER);
        int textBorderWidth = (int) ((ts.getTextBorderWidth() != null ? ts.getTextBorderWidth() : 0) * maxScale * BORDER_SCALE_FACTOR);

        // Calculate content dimensions (text size + background dimensions)
        int contentWidth = maxTextWidth + bgWidth + 2 * textBorderWidth;
        int contentHeight = textBlockHeight + bgHeight + 2 * textBorderWidth;

        // Cap dimensions to prevent excessive memory usage
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

        // Calculate final image dimensions
        int totalWidth = contentWidth + 2 * bgBorderWidth + 2 * textBorderWidth;
        int totalHeight = contentHeight + 2 * bgBorderWidth + 2 * textBorderWidth;

        // Create high-resolution image
        BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setFont(font);
        fm = g2d.getFontMetrics();

        // Draw background
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

        // Draw background border
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

        // Draw text with border (stroke) and letter spacing
        String alignment = ts.getAlignment() != null ? ts.getAlignment().toLowerCase() : "center";
// Center text vertically within contentHeight, accounting for background height
        int textYStart = bgBorderWidth + textBorderWidth + (contentHeight - textBlockHeight) / 2 + fm.getAscent();
        int y = textYStart;

        FontRenderContext frc = g2d.getFontRenderContext();

        for (String line : lines) {
            // Calculate line width with letter spacing
            int lineWidth = 0;
            for (int i = 0; i < line.length(); i++) {
                lineWidth += fm.charWidth(line.charAt(i));
                if (i < line.length() - 1) {
                    lineWidth += (int) scaledLetterSpacing;
                }
            }

            // Calculate starting x position based on alignment
            int x;
            if (alignment.equals("left")) {
                x = bgBorderWidth + textBorderWidth;
            } else if (alignment.equals("center")) {
                x = bgBorderWidth + textBorderWidth + (contentWidth - lineWidth) / 2;
            } else { // right
                x = bgBorderWidth + textBorderWidth + contentWidth - lineWidth;
            }

            // Draw text border (stroke) and fill using the same positioning system
            if (textBorderColor != null && textBorderWidth > 0) {
                float textBorderOpacity = ts.getTextBorderOpacity() != null ? ts.getTextBorderOpacity().floatValue() : 1.0f;
                g2d.setColor(new Color(textBorderColor.getRed(), textBorderColor.getGreen(), textBorderColor.getBlue(), (int) (textBorderOpacity * 255)));
                g2d.setStroke(new BasicStroke((float) textBorderWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                // Create combined area for all characters in the line
                Area combinedArea = new Area();
                int currentX = x;

                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    TextLayout charLayout = new TextLayout(String.valueOf(c), font, frc);
                    // Use the same baseline positioning as drawString
                    Shape charShape = charLayout.getOutline(AffineTransform.getTranslateInstance(currentX, y));
                    combinedArea.add(new Area(charShape));
                    currentX += fm.charWidth(c) + (int) scaledLetterSpacing;
                }

                // Draw the stroke
                g2d.draw(combinedArea);
            }

            // Draw text fill using exactly the same positioning
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

        // Save the high-resolution PNG
        String tempPngPath = new File(tempDir, "text_" + ts.getId() + ".png").getAbsolutePath();
        ImageIO.write(image, "PNG", new File(tempPngPath));
        return tempPngPath;
    }
        // Helper method to parse colors
        private Color parseColor(String colorStr, Color fallback, String type, String segmentId) {
            try {
                return Color.decode(colorStr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid " + type + " color for text segment " + segmentId + ": " + colorStr + ", using " + (fallback != null ? "fallback" : "none"));
                return fallback;
            }
        }

        // Helper method to calculate x-position based on alignment
        private int calculateXPosition(String line, String alignment, int totalWidth, FontMetrics fm, int padding, int borderWidth) {
            int lineWidth = fm.stringWidth(line);
            if (alignment.equals("left")) {
                return padding + borderWidth;
            } else if (alignment.equals("right")) {
                return totalWidth - lineWidth - padding - borderWidth;
            } else {
                return (totalWidth - lineWidth) / 2;
            }
        }

    private Map<String, String> applyTransitionFilters(StringBuilder filterComplex, List<Transition> transitions,
                                                       double segmentStartTime, double segmentEndTime, int canvasWidth, int canvasHeight,
                                                       double batchStart, double batchDuration) {
        Map<String, String> transitionOffsets = new HashMap<>();
        transitionOffsets.put("x", "0");
        transitionOffsets.put("y", "0");
        transitionOffsets.put("cropWidth", "iw");
        transitionOffsets.put("cropHeight", "ih");
        transitionOffsets.put("cropX", "0");
        transitionOffsets.put("cropY", "0");
        transitionOffsets.put("scale", "1"); // Scale multiplier
        transitionOffsets.put("rotation", "0");

        for (Transition transition : transitions) {
            double transStart = transition.getTimelineStartTime();
            double transDuration = transition.getDuration();
            double transEnd;

            // Force 1-second wipe or zoom transition to start at segmentStartTime for start transitions
            if (("Wipe".equals(transition.getType()) || "Zoom".equals(transition.getType())) && Math.abs(transDuration - 1.0) < 0.01) {
                if (transition.isStart()) {
                    transStart = segmentStartTime;
                    transEnd = Math.min(segmentStartTime + 1.0, segmentEndTime);
                } else { // end
                    transEnd = Math.min(segmentEndTime, segmentStartTime + transStart + 1.0);
                    transStart = Math.max(segmentStartTime, transEnd - 1.0);
                }
                transDuration = transEnd - transStart;
                System.out.println(transition.getType() + " transition for segment ID=" + transition.getSegmentId() +
                        ": transStart=" + transStart + ", transEnd=" + transEnd + ", duration=" + transDuration +
                        ", position=" + (transition.isStart() ? "start" : "end"));
            } else {
                transEnd = transStart + transDuration;
            }

            // Adjust transition timings to batch's local timeline
            double batchTransStart = Math.max(transStart, batchStart) - batchStart;
            double batchTransEnd = Math.min(transEnd, batchStart + batchDuration) - batchStart;
            if (batchTransStart >= batchDuration || batchTransEnd <= 0) {
                System.out.println("Skipping transition " + transition.getId() + ": outside batch bounds, batchTransStart=" + batchTransStart +
                        ", batchTransEnd=" + batchTransEnd + ", batchDuration=" + batchDuration);
                continue;
            }

            // Ensure batchTransStart and batchTransEnd are non-negative and within batch
            batchTransStart = Math.max(0, batchTransStart);
            batchTransEnd = Math.min(batchDuration, batchTransEnd);
            double batchTransDuration = batchTransEnd - batchTransStart;

            // Skip if the transition duration in this batch is effectively zero
            if (batchTransDuration <= 0.000001) {
                System.out.println("Skipping transition " + transition.getId() + ": zero duration in batch, batchTransStart=" + batchTransStart +
                        ", batchTransEnd=" + batchTransEnd);
                continue;
            }

            // Calculate global progress relative to the transition's full duration
            String progressExpr = String.format("(t+%.6f-%.6f)/%.6f", batchStart, transStart, transDuration);
            progressExpr = String.format("max(0,min(1,%s))", progressExpr);

            String transType = transition.getType();
            Map<String, String> params = transition.getParameters() != null ? transition.getParameters() : new HashMap<>();
            String direction = params.getOrDefault("direction", getDefaultDirection(transType));
            boolean isStartTransition = transition.isStart();

            switch (transType) {
                case "Slide":
                    String slideXExpr = "0";
                    String slideYExpr = "0";
                    if (isStartTransition) {
                        switch (direction) {
                            case "right": slideXExpr = String.format("%d*(1-%s)", canvasWidth, progressExpr); break;
                            case "left": slideXExpr = String.format("-%d*(1-%s)", canvasWidth, progressExpr); break;
                            case "top": slideYExpr = String.format("-%d*(1-%s)", canvasHeight, progressExpr); break;
                            case "bottom": slideYExpr = String.format("%d*(1-%s)", canvasHeight, progressExpr); break;
                        }
                    } else { // end
                        switch (direction) {
                            case "right": slideXExpr = String.format("-%d*%s", canvasWidth, progressExpr); break;
                            case "left": slideXExpr = String.format("%d*%s", canvasWidth, progressExpr); break;
                            case "top": slideYExpr = String.format("%d*%s", canvasHeight, progressExpr); break;
                            case "bottom": slideYExpr = String.format("-%d*%s", canvasHeight, progressExpr); break;
                        }
                    }
                    transitionOffsets.put("x", String.format("if(between(t,%.6f,%.6f),%s,0)", batchTransStart, batchTransEnd, slideXExpr));
                    transitionOffsets.put("y", String.format("if(between(t,%.6f,%.6f),%s,0)", batchTransStart, batchTransEnd, slideYExpr));
                    System.out.println("Slide transition " + transition.getId() + ": x=" + transitionOffsets.get("x") +
                            ", y=" + transitionOffsets.get("y"));
                    break;

                case "Wipe":
                    String cropWidthExpr = "iw";
                    String cropHeightExpr = "ih";
                    String cropXExpr = "0";
                    String cropYExpr = "0";
                    if (isStartTransition) {
                        switch (direction) {
                            case "left":
                                cropWidthExpr = String.format("iw*%s", progressExpr);
                                cropXExpr = String.format("iw*(1-%s)", progressExpr);
                                break;
                            case "right":
                                cropWidthExpr = String.format("iw*%s", progressExpr);
                                cropXExpr = "0";
                                break;
                            case "top":
                                cropHeightExpr = String.format("ih*%s", progressExpr);
                                cropYExpr = String.format("ih*(1-%s)", progressExpr);
                                break;
                            case "bottom":
                                cropHeightExpr = String.format("ih*%s", progressExpr);
                                cropYExpr = "0";
                                break;
                        }
                    } else { // end
                        switch (direction) {
                            case "left":
                                cropWidthExpr = String.format("iw*(1-%s)", progressExpr);
                                cropXExpr = "0";
                                break;
                            case "right":
                                cropWidthExpr = String.format("iw*(1-%s)", progressExpr);
                                cropXExpr = String.format("iw*%s", progressExpr);
                                break;
                            case "top":
                                cropHeightExpr = String.format("ih*(1-%s)", progressExpr);
                                cropYExpr = "0";
                                break;
                            case "bottom":
                                cropHeightExpr = String.format("ih*(1-%s)", progressExpr);
                                cropYExpr = String.format("ih*%s", progressExpr);
                                break;
                        }
                    }
                    transitionOffsets.put("cropWidth", cropWidthExpr);
                    transitionOffsets.put("cropHeight", cropHeightExpr);
                    transitionOffsets.put("cropX", cropXExpr);
                    transitionOffsets.put("cropY", cropYExpr);
                    System.out.println("Wipe transition " + transition.getId() + ": cropWidth=" + cropWidthExpr +
                            ", cropHeight=" + cropHeightExpr + ", cropX=" + cropXExpr + ", cropY=" + cropYExpr);
                    break;

                case "Zoom":
                    String scaleExpr;
                    if (isStartTransition) {
                        if ("in".equals(direction)) {
                            scaleExpr = String.format("0.0+1.0*%s", progressExpr);
                        } else {
                            scaleExpr = String.format("2.0-1.0*%s", progressExpr);
                        }
                    } else { // end
                        if ("in".equals(direction)) {
                            scaleExpr = String.format("1.0+1.0*%s", progressExpr);
                        } else {
                            scaleExpr = String.format("1.0-0.9*%s", progressExpr);
                        }
                    }
                    transitionOffsets.put("scale", String.format("if(between(t,%.6f,%.6f),%s,1)", batchTransStart, batchTransEnd, scaleExpr));
                    System.out.println("Zoom transition " + transition.getId() + ": scale=" + transitionOffsets.get("scale") +
                            ", batchTransStart=" + batchTransStart + ", batchTransEnd=" + batchTransEnd);
                    break;

                case "Rotate":
                    double rotationAngle = "clockwise".equals(direction) ? 180.0 : -180.0;
                    double angleRad = Math.toRadians(rotationAngle);
                    String rotationExpr;
                    if (isStartTransition) {
                        rotationExpr = String.format("(%.6f)*(1-min(1,max(0,%s))^2)", angleRad, progressExpr);
                    } else {
                        rotationExpr = String.format("(%.6f)*(min(1,max(0,%s))^2)", angleRad, progressExpr);
                    }
                    transitionOffsets.put("rotation", String.format("if(between(t,%.6f,%.6f),%s,0)", batchTransStart, batchTransEnd, rotationExpr));
                    String rotateScaleExpr = String.format("1.0+0.05*sin(min(3.14159,%s*3.14159))", progressExpr);
                    String currentScale = transitionOffsets.get("scale");
                    if (currentScale.equals("1")) {
                        transitionOffsets.put("scale", String.format("if(between(t,%.6f,%.6f),%s,1)", batchTransStart, batchTransEnd, rotateScaleExpr));
                    } else {
                        transitionOffsets.put("scale", String.format("if(between(t,%.6f,%.6f),(%s)*(%s),(%s))",
                                batchTransStart, batchTransEnd, currentScale, rotateScaleExpr, currentScale));
                    }
                    System.out.println("Rotate transition " + transition.getId() + ": rotation=" + rotationExpr +
                            ", direction=" + direction + ", batchTransStart=" + batchTransStart + ", batchTransEnd=" + batchTransEnd);
                    break;

                case "Fade":
                    double opacityStart = isStartTransition ? 0.0 : 1.0;
                    double opacityEnd = isStartTransition ? 1.0 : 0.0;
                    String opacityExpr = String.format("%.6f+(%.6f-%.6f)*%s", opacityStart, opacityEnd, opacityStart, progressExpr);
                    filterComplex.append("format=rgba,");
                    filterComplex.append("lutrgb=a='val*").append(String.format("if(between(t,%.6f,%.6f),%s,1)", batchTransStart, batchTransEnd, opacityExpr)).append("',");
                    filterComplex.append("format=rgba,");
                    System.out.println("Fade transition " + transition.getId() + ": opacity=" + opacityExpr +
                            ", applied between t=" + batchTransStart + " and t=" + batchTransEnd);
                    break;

                default:
                    System.err.println("Unsupported transition type: " + transType);
                    break;
            }
        }
        return transitionOffsets;
    }
        private String getDefaultDirection(String transitionType) {
            switch (transitionType) {
                case "Zoom":
                    return "in";
                case "Rotate":
                    return "clockwise";
                case "Slide":
                case "Push":
                    return "right";
                case "Wipe":
                    return "left";
                default:
                    return "";
            }
        }

    private void executeFFmpegCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFmpeg failed with exit code {}: {}", exitCode, output);
            throw new IOException("FFmpeg process failed with exit code: " + exitCode + "\nOutput: " + output);
        }
        logger.info("FFmpeg completed successfully");
    }

        /**
         * Gets the system font path for a given font family name.
         * @param fontFamily The font family name
         * @return The full path to the font file
         */
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
        public void applyFilter(String sessionId, String segmentId, String filterName, String filterValue) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            boolean segmentExists = false;
            for (VideoSegment segment : timelineState.getSegments()) {
                if (segment.getId().equals(segmentId)) {
                    segmentExists = true;
                    break;
                }
            }
            if (!segmentExists) {
                for (ImageSegment segment : timelineState.getImageSegments()) {
                    if (segment.getId().equals(segmentId)) {
                        segmentExists = true;
                        break;
                    }
                }
            }
            if (!segmentExists) {
                throw new RuntimeException("Segment not found with ID: " + segmentId);
            }

            Filter filter = new Filter();
            filter.setSegmentId(segmentId);
            filter.setFilterName(filterName);
            filter.setFilterValue(filterValue);
            timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && f.getFilterName().equals(filterName));
            timelineState.getFilters().add(filter);


            saveTimelineState(sessionId, timelineState);
        }

    public void removeFilter(String sessionId, String segmentId, String filterId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        boolean removed = timelineState.getFilters().removeIf(f ->
                f.getSegmentId().equals(segmentId) && f.getFilterId().equals(filterId)
        );
        if (!removed) {
            throw new RuntimeException("Filter not found with ID: " + filterId + " for segment: " + segmentId);
        }

        saveTimelineState(sessionId, timelineState);
    }

    public void removeAllFilters(String sessionId, String segmentId) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        // Remove filters for the segment, no exception if none found
        timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId));

        saveTimelineState(sessionId, timelineState);
    }

        public List<Filter> getFiltersForSegment(String sessionId, String segmentId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            // Check if the segment exists in any of the segment types
            boolean segmentExists = timelineState.getSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                    timelineState.getImageSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                    timelineState.getTextSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                    timelineState.getAudioSegments().stream().anyMatch(s -> s.getId().equals(segmentId));

            if (!segmentExists) {
                throw new RuntimeException("Segment not found with ID: " + segmentId);
            }

            // Return filters associated with the segment
            return timelineState.getFilters().stream()
                    .filter(f -> f.getSegmentId().equals(segmentId))
                    .collect(Collectors.toList());
        }

        public void updateFilter(String sessionId, String segmentId, String filterId, String filterName, String filterValue) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            // Verify segment exists
            boolean segmentExists = false;
            for (VideoSegment segment : timelineState.getSegments()) {
                if (segment.getId().equals(segmentId)) {
                    segmentExists = true;
                    break;
                }
            }
            if (!segmentExists) {
                for (ImageSegment segment : timelineState.getImageSegments()) {
                    if (segment.getId().equals(segmentId)) {
                        segmentExists = true;
                        break;
                    }
                }
            }
            if (!segmentExists) {
                throw new RuntimeException("Segment not found with ID: " + segmentId);
            }

            // Find and update the existing filter
            Optional<Filter> filterToUpdate = timelineState.getFilters().stream()
                    .filter(f -> f.getSegmentId().equals(segmentId) && f.getFilterId().equals(filterId))
                    .findFirst();

            if (filterToUpdate.isPresent()) {
                Filter filter = filterToUpdate.get();
                filter.setFilterName(filterName);
                filter.setFilterValue(filterValue);
            } else {
                throw new RuntimeException("Filter not found with ID: " + filterId + " for segment: " + segmentId);
            }

            saveTimelineState(sessionId, timelineState);
        }

        // Delete Video Segment from Timeline
        public void deleteVideoFromTimeline(String sessionId, String segmentId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            boolean removed = timelineState.getSegments().removeIf(segment -> segment.getId().equals(segmentId));
            if (!removed) {
                throw new RuntimeException("Video segment not found with ID: " + segmentId);
            }

            // Remove associated filters
            timelineState.getFilters().removeIf(filter -> filter.getSegmentId().equals(segmentId));
            saveTimelineState(sessionId, timelineState);
        }
        // Delete Image Segment from Timeline
        public void deleteImageFromTimeline(String sessionId, String imageId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            boolean removed = timelineState.getImageSegments().removeIf(segment -> segment.getId().equals(imageId));
            if (!removed) {
                throw new RuntimeException("Image segment not found with ID: " + imageId);
            }

            // Remove associated filters
            timelineState.getFilters().removeIf(filter -> filter.getSegmentId().equals(imageId));
            saveTimelineState(sessionId, timelineState);
        }

        // Delete Audio Segment from Timeline
        public void deleteAudioFromTimeline(String sessionId, String audioId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            boolean removed = timelineState.getAudioSegments().removeIf(segment -> segment.getId().equals(audioId));
            if (!removed) {
                throw new RuntimeException("Audio segment not found with ID: " + audioId);
            }

            saveTimelineState(sessionId, timelineState);
        }
        // Delete Text Segment from Timeline
        public void deleteTextFromTimeline(String sessionId, String textId) throws JsonProcessingException {
            Project project = getProjectBySession(sessionId);
            TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            boolean removed = timelineState.getTextSegments().removeIf(segment -> segment.getId().equals(textId));
            if (!removed) {
                throw new RuntimeException("Text segment not found with ID: " + textId);
            }

            saveTimelineState(sessionId, timelineState);
        }
    public void deleteMultipleSegments(String sessionId, List<String> segmentIds) throws JsonProcessingException {
        Project project = getProjectBySession(sessionId);
        TimelineState timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

        // Track whether any segments were removed
        boolean segmentsRemoved = false;

        // Iterate through each segment ID
        for (String segmentId : segmentIds) {
            // Try removing from VideoSegments
            boolean removed = timelineState.getSegments().removeIf(
                    segment -> segment.getId().equals(segmentId)
            );
            if (removed) {
                segmentsRemoved = true;
                // Remove associated transitions
                timelineState.getTransitions().removeIf(
                        transition -> transition.getSegmentId().equals(segmentId)
                );
                continue;
            }

            // Try removing from ImageSegments
            removed = timelineState.getImageSegments().removeIf(
                    segment -> segment.getId().equals(segmentId)
            );
            if (removed) {
                segmentsRemoved = true;
                // Remove associated transitions and filters
                timelineState.getTransitions().removeIf(
                        transition -> transition.getSegmentId().equals(segmentId)
                );
                timelineState.getFilters().removeIf(
                        filter -> filter.getSegmentId().equals(segmentId)
                );
                continue;
            }

            // Try removing from AudioSegments
            removed = timelineState.getAudioSegments().removeIf(
                    segment -> segment.getId().equals(segmentId)
            );
            if (removed) {
                segmentsRemoved = true;
                continue;
            }

            // Try removing from TextSegments
            removed = timelineState.getTextSegments().removeIf(
                    segment -> segment.getId().equals(segmentId)
            );
            if (removed) {
                segmentsRemoved = true;
                // Remove associated transitions
                timelineState.getTransitions().removeIf(
                        transition -> transition.getSegmentId().equals(segmentId)
                );
                continue;
            }
        }

        // If no segments were removed, throw an exception
        if (!segmentsRemoved) {
            throw new RuntimeException("No segments found with the provided IDs: " + segmentIds);
        }

        saveTimelineState(sessionId, timelineState);

    }

        // Helper method to convert Element to ElementDto
        private ElementDto toElementDto(Element element) {
            ElementDto dto = new ElementDto();
            dto.setId(element.getId());
            dto.setTitle(element.getTitle());
            dto.setFilePath(element.getFilePath());
            dto.setFileName(element.getFileName());
            return dto;
        }

        // Add element to project (store in element_json)
        public void addElement(Project project, String imagePath, String imageFileName) throws JsonProcessingException {
            List<Map<String, String>> elements = getElements(project);
            Map<String, String> elementData = new HashMap<>();
            elementData.put("imagePath", imagePath);
            elementData.put("imageFileName", imageFileName);
            elements.add(elementData);
            project.setElementJson(objectMapper.writeValueAsString(elements));
        }

        // Get elements from project
        public List<Map<String, String>> getElements(Project project) throws JsonProcessingException {
            if (project.getElementJson() == null || project.getElementJson().isEmpty()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(project.getElementJson(), new TypeReference<List<Map<String, String>>>() {});
        }
}