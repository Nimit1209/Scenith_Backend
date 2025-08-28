package com.example.Scenith.controller;


import com.example.Scenith.dto.*;
import com.example.Scenith.entity.Project;
import com.example.Scenith.entity.User;
import com.example.Scenith.exception.SessionNotFoundException;
import com.example.Scenith.repository.ExportLinkRepository;
import com.example.Scenith.repository.ProjectRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.CloudflareR2Service;
import com.example.Scenith.service.VideoEditingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/projects")
public class ProjectController {
    private final VideoEditingService videoEditingService;
    private final ProjectRepository projectRepository;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CloudflareR2Service cloudflareR2Service; // Updated field
    private final ExportLinkRepository exportLinkRepository;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(ProjectController.class);

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    public ProjectController(
            VideoEditingService videoEditingService,
            ProjectRepository projectRepository,
            JwtUtil jwtUtil,
            UserRepository userRepository,
            CloudflareR2Service cloudflareR2Service,  ExportLinkRepository exportLinkRepository, ObjectMapper objectMapper) { // Updated constructor
        this.videoEditingService = videoEditingService;
        this.projectRepository = projectRepository;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.cloudflareR2Service = cloudflareR2Service;
        this.exportLinkRepository = exportLinkRepository;
        this.objectMapper = objectMapper;
    }
    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping
    public ResponseEntity<Project> createProject(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) throws JsonProcessingException {
        User user = getUserFromToken(token);

        String name = (String) request.get("name");
        Integer width = request.get("width") != null ?
                ((Number) request.get("width")).intValue() : 1920;
        Integer height = request.get("height") != null ?
                ((Number) request.get("height")).intValue() : 1080;

        Float fps = request.get("fps") != null ?
                ((Number) request.get("fps")).floatValue() : null;

        Project project = videoEditingService.createProject(user, name, width, height, fps);
        return ResponseEntity.ok(project);
    }

    @GetMapping
    public ResponseEntity<List<Project>> getUserProjects(
            @RequestHeader("Authorization") String token) {
        User user = getUserFromToken(token);
        List<Project> projects = projectRepository.findByUserOrderByLastModifiedDesc(user);
        return ResponseEntity.ok(projects);
    }

    @PostMapping("/{projectId}/session")
    public ResponseEntity<String> startEditingSession(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId) throws JsonProcessingException {
        User user = getUserFromToken(token);
        String sessionId = videoEditingService.startEditingSession(user, projectId);
        return ResponseEntity.ok(sessionId);
    }

    @PostMapping("/{projectId}/save")
    public ResponseEntity<?> saveProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) throws JsonProcessingException {
        videoEditingService.saveProject(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{projectId}/saveForUndoRedo")
    public ResponseEntity<?> saveForUndoRedo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> payload) throws JsonProcessingException {
        // Validate token and user
        User user = getUserFromToken(token);

        // Extract timeline_state from payload
        ObjectMapper mapper = new ObjectMapper();
        String timelineStateJson = mapper.writeValueAsString(payload.get("timelineState"));

        // Save project with updated timeline_state for undo/redo
        videoEditingService.saveForUndoRedo(projectId, sessionId, timelineStateJson);

        return ResponseEntity.ok().build();
    }

    @GetMapping("/{projectId}/export")
    public ResponseEntity<ExportLinkDTO> exportProject(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam(required = false) String requestId,
            Authentication authentication,
            HttpServletRequest request) throws Exception {

        String effectiveRequestId = requestId != null ? requestId : UUID.randomUUID().toString();
        logger.info("=== EXPORT REQUEST DEBUG ===");
        logger.info("Export request received: projectId={}, sessionId={}, requestId={}, tokenPrefix={}",
                projectId, sessionId, effectiveRequestId, token != null ? token.substring(0, Math.min(token.length(), 20)) : "null");

        // Authentication and project validation logic (unchanged)
        User user = null;
        if (token != null && token.startsWith("Bearer ")) {
            try {
                user = getUserFromToken(token);
                logger.info("STEP 1 PASSED: User from token: id={}, email={}", user.getId(), user.getEmail());
            } catch (Exception e) {
                logger.warn("Invalid token, proceeding without user: {}", e.getMessage());
            }
        }

        Project project = projectRepository.findByEditSession(sessionId)
                .orElseThrow(() -> {
                    logger.error("STEP 2 FAILED: Session not found: sessionId={}", sessionId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
                });
        logger.info("STEP 2 PASSED: Project found: id={}, userId={}, name={}",
                project.getId(), project.getUser() != null ? project.getUser().getId() : "null", project.getName());

        if (user != null && project.getUser() != null && !project.getUser().getId().equals(user.getId())) {
            logger.error("STEP 3 FAILED: User mismatch: projectUserId={}, authenticatedUserId={}",
                    project.getUser().getId(), user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized to export this project");
        }

        if (!project.getId().equals(projectId)) {
            logger.error("STEP 4 FAILED: Project ID mismatch: projectId={}, pathProjectId={}",
                    project.getId(), projectId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Project ID mismatch: session belongs to project " + project.getId() +
                            " but path specifies project " + projectId);
        }

        // Queue export task
        try {
            logger.info("STEP 5: Queuing video export...");
            Map<String, String> exportResult = videoEditingService.exportProject(sessionId);
            logger.info("STEP 5 PASSED: Export queued: messageId={}, fileName={}, r2Path={}",
                    exportResult.get("messageId"), exportResult.get("fileName"), exportResult.get("r2Path"));

            ExportLinkDTO exportLinkDTO = new ExportLinkDTO();
            exportLinkDTO.setFileName(exportResult.get("fileName"));
            exportLinkDTO.setR2Path(exportResult.get("r2Path"));
            exportLinkDTO.setStatus(exportResult.get("status"));
            exportLinkDTO.setMessageId(exportResult.get("messageId"));
            exportLinkDTO.setCreatedAt(LocalDateTime.now());

            logger.info("STEP 6 PASSED: Export link DTO created: fileName={}", exportLinkDTO.getFileName());
            return ResponseEntity.ok(exportLinkDTO);
        } catch (Exception e) {
            logger.error("STEP 5/6 FAILED: Export queuing failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export queuing failed: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/export/progress")
    public ResponseEntity<Map<String, Object>> getExportProgress(
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestHeader(value = "Authorization", required = false) String token) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId));

        if (!project.getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session does not match project");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", project.getStatus());
        response.put("progress", project.getProgress() != null ? project.getProgress() : 0.0);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{projectId}/export/status")
    public ResponseEntity<String> getExportStatus(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) {
        logger.info("=== EXPORT STATUS REQUEST DEBUG ===");
        logger.info("Export status request: projectId={}, sessionId={}, tokenPrefix={}",
                projectId, sessionId, token != null ? token.substring(0, Math.min(token.length(), 20)) : "null");

        // Authenticate user (optional, matching /export endpoint)
        User user = null;
        if (token != null && token.startsWith("Bearer ")) {
            try {
                user = getUserFromToken(token);
                logger.info("User from token: id={}, email={}", user.getId(), user.getEmail());
            } catch (Exception e) {
                logger.warn("Invalid token, proceeding without user: {}", e.getMessage());
            }
        }

        // Find project by sessionId
        Project project = projectRepository.findByEditSession(sessionId)
                .orElseThrow(() -> {
                    logger.error("Session not found: sessionId={}", sessionId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId);
                });

        // Verify project ID
        if (!project.getId().equals(projectId)) {
            logger.error("Project ID mismatch: requestedId={}, sessionProjectId={}", projectId, project.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project ID mismatch");
        }

        // Verify user ownership (if user is authenticated)
        if (user != null && project.getUser() != null && !project.getUser().getId().equals(user.getId())) {
            logger.error("User mismatch: projectUserId={}, authenticatedUserId={}",
                    project.getUser().getId(), user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized to access this project");
        }

        // Return the project status
        String status = project.getStatus();
        logger.info("Returning export status: status={}, projectId={}, sessionId={}", status, projectId, sessionId);
        return ResponseEntity.ok(status);
    }

    // Add this helper method to debug your JWT token parsing
    private void debugToken(String token) {
        try {
            logger.info("=== TOKEN DEBUG ===");
            if (token.startsWith("Bearer ")) {
                String jwtToken = token.substring(7);
                logger.info("JWT Token (first 50 chars): {}", jwtToken.substring(0, Math.min(jwtToken.length(), 50)));

                // If you're using JWT, you can decode the payload to see what's inside
                String[] chunks = jwtToken.split("\\.");
                if (chunks.length >= 2) {
                    java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();
                    String payload = new String(decoder.decode(chunks[1]));
                    logger.info("JWT Payload: {}", payload);
                }
            }
            logger.info("=== TOKEN DEBUG END ===");
        } catch (Exception e) {
            logger.warn("Could not debug token: {}", e.getMessage());
        }
    }

    @GetMapping("/export-links")
    public ResponseEntity<List<ExportLinkDTO>> getExportLinks(
            @RequestHeader("Authorization") String token,
            Authentication authentication) {

        logger.info("=== EXPORT LINKS REQUEST DEBUG ===");
        logger.debug("Fetching export links for token: {}", token.substring(0, Math.min(token.length(), 20)));

        // Debug authentication
        logger.info("Authentication debug: isNull={}, isAuthenticated={}, principalType={}, principal={}",
                authentication == null,
                authentication != null && authentication.isAuthenticated(),
                authentication != null && authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getSimpleName() : "null",
                authentication != null ? authentication.getPrincipal() : "null");

        // Verify authentication
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.error("Authentication failed for export-links");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }

        // Get user from token
        User user;
        try {
            user = getUserFromToken(token);
            logger.debug("User from token: id={}, email={}", user.getId(), user.getEmail());
        } catch (Exception e) {
            logger.error("Failed to get user from token: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }

        // More flexible principal verification
        Object principal = authentication.getPrincipal();
        String principalEmail = null;
        if (principal instanceof String) {
            principalEmail = (String) principal;
        } else if (principal instanceof UserDetails) {
            principalEmail = ((UserDetails) principal).getUsername();
        }

        logger.info("Principal email: {}, user email: {}", principalEmail, user.getEmail());

        // Fetch projects for the user and extract export links
        List<ExportLinkDTO> exportLinks = projectRepository.findByUserId(user.getId()).stream()
                .filter(project -> project.getExportsJson() != null && !project.getExportsJson().isEmpty())
                .flatMap(project -> {
                    try {
                        List<ExportLinkDetails> links = objectMapper.readValue(project.getExportsJson(), new TypeReference<List<ExportLinkDetails>>() {});
                        return links.stream().map(link -> {
                            // Regenerate URL if expired
                            if (link.isExpired()) {
                                try {
                                    String newUrl = cloudflareR2Service.generatePresignedUrl(link.getR2Path(), 3600);
                                    link.setDownloadUrl(newUrl);
                                    link.setExpiresAt(LocalDateTime.now().plusHours(1));

                                    // Update exportsJson in the project
                                    List<ExportLinkDetails> updatedLinks = objectMapper.readValue(project.getExportsJson(), new TypeReference<List<ExportLinkDetails>>() {});
                                    updatedLinks.removeIf(l -> l.getR2Path().equals(link.getR2Path()));
                                    updatedLinks.add(link);
                                    project.setExportsJson(objectMapper.writeValueAsString(updatedLinks));
                                    projectRepository.save(project);
                                    logger.debug("Regenerated expired URL for project: {}, r2Path: {}", project.getId(), link.getR2Path());
                                } catch (Exception e) {
                                    logger.error("Failed to regenerate URL for project: {}, r2Path: {}", project.getId(), link.getR2Path(), e);
                                }
                            }
                            // Convert to DTO
                            ExportLinkDTO dto = new ExportLinkDTO();
                            dto.setFileName(link.getFileName());
                            dto.setDownloadUrl(link.getDownloadUrl());
                            dto.setR2Path(link.getR2Path());
                            dto.setCreatedAt(link.getCreatedAt());
                            dto.setExpiresAt(link.getExpiresAt());
                            return dto;
                        });
                    } catch (Exception e) {
                        logger.error("Failed to parse exportsJson for project: {}", project.getId(), e);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());

        logger.info("Returning {} export links", exportLinks.size());
        logger.info("=== EXPORT LINKS REQUEST SUCCESS ===");
        return ResponseEntity.ok(exportLinks);
    }
    @GetMapping("/{projectId}")
    public ResponseEntity<Project> getProjectDetails(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId
    ) {
        User user = getUserFromToken(token);
        Project project = projectRepository.findByIdAndUser(projectId, user);

        return ResponseEntity.ok(project);
    }
    @PostMapping("/{projectId}/upload-video")
    public ResponseEntity<?> uploadVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("video") MultipartFile[] videoFiles,
            @RequestParam(value = "videoFileNames", required = false) String[] videoFileNames) {
        try {
            User user = getUserFromToken(token);
            Project updatedProject = videoEditingService.uploadVideoToProject(user, projectId, videoFiles, videoFileNames);

            // Extract video metadata from videosJson
            List<Map<String, String>> videoFilesMetadata = videoEditingService.getVideos(updatedProject);
            List<Map<String, String>> responseVideoFiles = videoFilesMetadata.stream()
                    .map(video -> {
                        Map<String, String> videoData = new HashMap<>();
                        videoData.put("videoFileName", video.get("videoFileName"));
                        videoData.put("videoPath", video.get("videoPath"));
                        videoData.put("cdnUrl", video.get("cdnUrl")); // CDN URL
                        videoData.put("presignedUrl", video.get("presignedUrl")); // Presigned URL
                        videoData.put("audioPath", video.getOrDefault("audioPath", null));
                        videoData.put("originalFileName", video.getOrDefault("originalFileName", null));
                        return videoData;
                    })
                    .collect(Collectors.toList());

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("project", updatedProject);
            response.put("videoFiles", responseVideoFiles);

            logger.info("Successfully uploaded videos for projectId={}: {}", projectId, responseVideoFiles);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            logger.error("Error uploading video for projectId={}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading video: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action for projectId={}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-to-timeline")
    public ResponseEntity<?> addVideoToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters from the request
            String videoPath = (String) request.get("videoPath");
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ? ((Number) request.get("timelineStartTime")).doubleValue() : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? ((Number) request.get("timelineEndTime")).doubleValue() : null;
            Double startTime = request.get("startTime") != null ? ((Number) request.get("startTime")).doubleValue() : null;
            Double endTime = request.get("endTime") != null ? ((Number) request.get("endTime")).doubleValue() : null;
            Double opacity = request.get("opacity") != null ? ((Number) request.get("opacity")).doubleValue() : null;
            Double speed = request.get("speed") != null ? ((Number) request.get("speed")).doubleValue() : null;
            Double rotation = request.get("rotation") != null ? ((Number) request.get("rotation")).doubleValue() : null;
            Boolean createAudioSegment = request.get("createAudioSegment") != null ?
                    Boolean.valueOf(request.get("createAudioSegment").toString()) :
                    (request.get("skipAudio") != null ? !Boolean.valueOf(request.get("skipAudio").toString()) : true);

            // Validate required parameters
            if (videoPath == null) {
                logger.warn("Missing required parameter: videoPath for projectId={}", projectId);
                return ResponseEntity.badRequest().body("Missing required parameter: videoPath");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                logger.warn("Invalid opacity value: {} for projectId={}", opacity, projectId);
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (speed != null && (speed < 0.1 || speed > 5.0)) {
                logger.warn("Invalid speed value: {} for projectId={}", speed, projectId);
                return ResponseEntity.badRequest().body("Speed must be between 0.1 and 5.0");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                logger.warn("Invalid rotation value: {} for projectId={}", rotation, projectId);
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }

            logger.info("Adding video to timeline: sessionId={}, videoPath={}, startTime={}, timelineStartTime={}",
                    sessionId, videoPath, startTime, timelineStartTime);

            // Resolve videoPath to full R2 path
            Project project = projectRepository.findByEditSession(sessionId)
                    .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
            List<Map<String, String>> videos = videoEditingService.getVideos(project);

            // Extract filename from videoPath and normalize it
            String filename = videoPath.contains("/") ? videoPath.substring(videoPath.lastIndexOf("/") + 1) : videoPath;
            String normalizedVideoPath = filename.toLowerCase().replaceAll("[^a-zA-Z0-9.-]", "_");
            logger.debug("Extracted filename={} from videoPath={}, normalized to={}", filename, videoPath, normalizedVideoPath);

            Map<String, String> targetVideo = videos.stream()
                    .filter(video -> {
                        String videoFileName = video.get("videoFileName").toLowerCase();
                        String videoPathLower = video.get("videoPath").toLowerCase();
                        String cdnUrl = video.get("cdnUrl") != null ? video.get("cdnUrl").toLowerCase() : "";
                        return videoFileName.equals(normalizedVideoPath) ||
                                videoPathLower.endsWith("/" + normalizedVideoPath) ||
                                cdnUrl.contains(normalizedVideoPath) ||
                                videoFileName.equals(filename) || // Match original filename
                                videoPathLower.contains(filename.toLowerCase());
                    })
                    .findFirst()
                    .orElseThrow(() -> {
                        logger.error("Video file not found in project metadata: videoPath={}, normalizedVideoPath={}, filename={}, projectId={}, videosJson={}",
                                videoPath, normalizedVideoPath, filename, projectId, videos);
                        return new RuntimeException("Video file not found in project metadata: " + videoPath);
                    });
            String resolvedVideoPath = targetVideo.get("videoPath");

            logger.info("Resolved videoPath={} to R2 path={}", videoPath, resolvedVideoPath);

            // Verify file exists in R2
            if (!cloudflareR2Service.fileExists(resolvedVideoPath)) {
                logger.error("Resolved video file does not exist in R2: r2Path={}, projectId={}", resolvedVideoPath, projectId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Video file not found in R2: " + resolvedVideoPath);
            }

            // Add video to timeline using resolved path
            videoEditingService.addVideoToTimeline(
                    sessionId, resolvedVideoPath, layer, timelineStartTime, timelineEndTime, startTime, endTime,
                    createAudioSegment, speed, rotation);

            // Retrieve the updated timeline state
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);

            // Find the newly added video segment using R2 path
            VideoSegment addedVideoSegment = timelineState.getSegments().stream()
                    .filter(s -> s.getSourceVideoPath().equals(resolvedVideoPath) &&
                            (startTime == null || Math.abs(s.getStartTime() - startTime) <= 0.01) &&
                            (timelineStartTime == null || Math.abs(s.getTimelineStartTime() - timelineStartTime) <= 0.01))
                    .findFirst()
                    .orElseThrow(() -> {
                        logger.error("Failed to find added video segment: r2Path={}, startTime={}, projectId={}",
                                resolvedVideoPath, startTime, projectId);
                        return new RuntimeException("Failed to find added video segment for path: " + resolvedVideoPath);
                    });

            AudioSegment addedAudioSegment = null;
            if (addedVideoSegment.getAudioId() != null) {
                addedAudioSegment = timelineState.getAudioSegments().stream()
                        .filter(a -> a.getId().equals(addedVideoSegment.getAudioId()))
                        .findFirst()
                        .orElse(null);
            }

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("videoSegmentId", addedVideoSegment.getId());
            response.put("layer", addedVideoSegment.getLayer());
            response.put("speed", addedVideoSegment.getSpeed());
            response.put("rotation", addedVideoSegment.getRotation());
            if (addedAudioSegment != null) {
                response.put("audioSegmentId", addedAudioSegment.getId());
                response.put("audioLayer", addedAudioSegment.getLayer());
                response.put("audioPath", addedAudioSegment.getAudioPath());
                response.put("waveformJsonPath", addedAudioSegment.getWaveformJsonPath());
                response.put("audioStartTime", addedAudioSegment.getStartTime());
                response.put("audioEndTime", addedAudioSegment.getEndTime());
                response.put("audioTimelineStartTime", addedAudioSegment.getTimelineStartTime());
                response.put("audioTimelineEndTime", addedAudioSegment.getTimelineEndTime());
                response.put("audioVolume", addedAudioSegment.getVolume());
                response.put("audioKeyframes", addedAudioSegment.getKeyframes() != null ?
                        addedAudioSegment.getKeyframes() : new HashMap<>());
            }

            logger.info("Successfully added video segment: id={} for projectId={}", addedVideoSegment.getId(), projectId);
            return ResponseEntity.ok(response);
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: sessionId={}, projectId={}", sessionId, projectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Session not found: " + e.getMessage());
        } catch (IOException e) {
            logger.error("IO error adding video to timeline for projectId={}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Error adding video to timeline: " + e.getMessage());
        } catch (InterruptedException e) {
            logger.error("Interrupted while adding video to timeline for projectId={}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Interrupted while adding video to timeline: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Error adding video to timeline for projectId={}: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
    @PutMapping("/{projectId}/update-segment")
    public ResponseEntity<?> updateVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            Double startTime = request.containsKey("startTime") ? Double.valueOf(request.get("startTime").toString()) : null;
            Double endTime = request.containsKey("endTime") ? Double.valueOf(request.get("endTime").toString()) : null;
            Double cropL = request.containsKey("cropL") ? Double.valueOf(request.get("cropL").toString()) : null;
            Double cropR = request.containsKey("cropR") ? Double.valueOf(request.get("cropR").toString()) : null;
            Double cropT = request.containsKey("cropT") ? Double.valueOf(request.get("cropT").toString()) : null;
            Double cropB = request.containsKey("cropB") ? Double.valueOf(request.get("cropB").toString()) : null;
            Double speed = request.containsKey("speed") ? Double.valueOf(request.get("speed").toString()) : null;
            Double rotation = request.containsKey("rotation") ? Double.valueOf(request.get("rotation").toString()) : null; // New parameter
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        // Validate keyframe value based on property
                        String property = entry.getKey();
                        if (property.equals("rotation") && value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) {
                            return ResponseEntity.badRequest().body("Rotation keyframe value must be a valid number");
                        }
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (cropL != null && (cropL < 0 || cropL > 100)) {
                return ResponseEntity.badRequest().body("cropL must be between 0 and 100");
            }
            if (cropR != null && (cropR < 0 || cropR > 100)) {
                return ResponseEntity.badRequest().body("cropR must be between 0 and 100");
            }
            if (cropT != null && (cropT < 0 || cropT > 100)) {
                return ResponseEntity.badRequest().body("cropT must be between 0 and 100");
            }
            if (cropB != null && (cropB < 0 || cropB > 100)) {
                return ResponseEntity.badRequest().body("cropB must be between 0 and 100");
            }
            if (speed != null && (speed < 0.1 || speed > 5.0)) {
                return ResponseEntity.badRequest().body("Speed must be between 0.1 and 5.0");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }
            // Validate total crop if static values are provided (not keyframed)
            if (parsedKeyframes == null ||
                    (!parsedKeyframes.containsKey("cropL") && !parsedKeyframes.containsKey("cropR") &&
                            !parsedKeyframes.containsKey("cropT") && !parsedKeyframes.containsKey("cropB"))) {
                double totalHorizontalCrop = (cropL != null ? cropL : 0.0) + (cropR != null ? cropR : 0.0);
                double totalVerticalCrop = (cropT != null ? cropT : 0.0) + (cropB != null ? cropB : 0.0);
                if (totalHorizontalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total horizontal crop (cropL + cropR) cannot be 100% or more");
                }
                if (totalVerticalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total vertical crop (cropT + cropB) cannot be 100% or more");
                }
            }

            videoEditingService.updateVideoSegment(
                    sessionId, segmentId, positionX, positionY, scale, opacity, timelineStartTime, layer,
                    timelineEndTime, startTime, endTime, cropL, cropR, cropT, cropB, speed, rotation, parsedKeyframes);
            return ResponseEntity.ok().build();
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException | InterruptedException e) {
            logger.error("IO error updating video segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating video segment: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error updating video segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/videos/{filename:.+}")
    public ResponseEntity<Void> serveVideo(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Construct R2 path
            String r2Path = "videos/projects/" + projectId + "/" + filename;

            // Verify video exists in project metadata
            List<Map<String, String>> videos = videoEditingService.getVideos(project);
            boolean videoExists = videos.stream()
                    .anyMatch(video -> video.get("videoPath").equals(r2Path));
            if (!videoExists) {
                logger.warn("Video not found in project metadata: {}", r2Path);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Generate pre-signed URL for the video
            String preSignedUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600); // 1-hour expiration

            // Redirect to the pre-signed URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, preSignedUrl)
                    .build();
        } catch (IOException e) { // Replaced B2Exception with IOException
            logger.error("Error generating pre-signed URL for video: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            logger.warn("Error serving video: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @GetMapping("/{projectId}/video-duration/{filename:.+}")
    public ResponseEntity<Double> getVideoDuration(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = getUserFromToken(token);
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String videoPath = "videos/projects/" + projectId + "/" + filename;
            double duration = videoEditingService.getVideoDuration(videoPath);
            return ResponseEntity.ok(duration);
        } catch (IOException | InterruptedException e) { // Removed B2Exception
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
        }
    }

    @GetMapping("/{projectId}/get-segment")
    public ResponseEntity<?> getVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            VideoSegment segment = videoEditingService.getVideoSegment(sessionId, segmentId);
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment audioSegment = segment.getAudioId() != null ?
                    timelineState.getAudioSegments().stream()
                            .filter(a -> a.getId().equals(segment.getAudioId()))
                            .findFirst()
                            .orElse(null) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("videoSegment", segment);
            if (audioSegment != null) {
                Map<String, Object> audioData = new HashMap<>();
                audioData.put("id", audioSegment.getId());
                audioData.put("audioPath", audioSegment.getAudioPath());
                audioData.put("layer", audioSegment.getLayer());
                audioData.put("startTime", audioSegment.getStartTime());
                audioData.put("endTime", audioSegment.getEndTime());
                audioData.put("timelineStartTime", audioSegment.getTimelineStartTime());
                audioData.put("timelineEndTime", audioSegment.getTimelineEndTime());
                audioData.put("volume", audioSegment.getVolume());
                audioData.put("waveformJsonPath", audioSegment.getWaveformJsonPath());
                audioData.put("keyframes", audioSegment.getKeyframes() != null ? audioSegment.getKeyframes() : new HashMap<>());
                response.put("audioSegment", audioData);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting video segment: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-text")
    public ResponseEntity<?> addTextToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Existing parameters
            String text = (String) request.get("text");
            Integer layer = request.get("layer") != null ? Integer.valueOf(request.get("layer").toString()) : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            String fontFamily = (String) request.get("fontFamily");
            Double scale = request.get("scale") != null ? Double.valueOf(request.get("scale").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.get("positionX") != null ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.get("positionY") != null ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double opacity = request.get("opacity") != null ? Double.valueOf(request.get("opacity").toString()) : null;
            String alignment = (String) request.get("alignment");
            Double rotation = request.get("rotation") != null ? Double.valueOf(request.get("rotation").toString()) : null; // New parameter

            // Background parameters
            Double backgroundOpacity = request.get("backgroundOpacity") != null ? Double.valueOf(request.get("backgroundOpacity").toString()) : null;
            Integer backgroundBorderWidth = request.get("backgroundBorderWidth") != null ? Integer.valueOf(request.get("backgroundBorderWidth").toString()) : null;
            String backgroundBorderColor = (String) request.get("backgroundBorderColor");
            Integer backgroundH = request.get("backgroundH") != null ? Integer.valueOf(request.get("backgroundH").toString()) : null;
            Integer backgroundW = request.get("backgroundW") != null ? Integer.valueOf(request.get("backgroundW").toString()) : null;
            Integer backgroundBorderRadius = request.get("backgroundBorderRadius") != null ? Integer.valueOf(request.get("backgroundBorderRadius").toString()) : null;

            // Text border parameters
            String textBorderColor = (String) request.get("textBorderColor");
            Integer textBorderWidth = request.get("textBorderWidth") != null ? Integer.valueOf(request.get("textBorderWidth").toString()) : null;
            Double textBorderOpacity = request.get("textBorderOpacity") != null ? Double.valueOf(request.get("textBorderOpacity").toString()) : null;

            // Letter spacing parameter
            Double letterSpacing = request.get("letterSpacing") != null ? Double.valueOf(request.get("letterSpacing").toString()) : null;

            // Line spacing parameter
            Double lineSpacing = request.get("lineSpacing") != null ? Double.valueOf(request.get("lineSpacing").toString()) : null;

            // Existing validation
            if (text == null || layer == null || timelineStartTime == null || timelineEndTime == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: text, layer, timelineStartTime, timelineEndTime");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (alignment != null && !Arrays.asList("left", "right", "center").contains(alignment)) {
                return ResponseEntity.badRequest().body("Alignment must be 'left', 'right', or 'center'");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }

            // Background validation
            if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
                return ResponseEntity.badRequest().body("Background opacity must be between 0 and 1");
            }
            if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Background border width must be non-negative");
            }
            if (backgroundH != null && backgroundH < 0) {
                return ResponseEntity.badRequest().body("Background height must be non-negative");
            }
            if (backgroundW != null && backgroundW < 0) {
                return ResponseEntity.badRequest().body("Background width must be non-negative");
            }
            if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
                return ResponseEntity.badRequest().body("Background border radius must be non-negative");
            }

            // Text border validation
            if (textBorderWidth != null && textBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Text border width must be non-negative");
            }
            if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
                return ResponseEntity.badRequest().body("Text border opacity must be between 0 and 1");
            }

            // Letter spacing validation
            if (letterSpacing != null && letterSpacing < 0) {
                return ResponseEntity.badRequest().body("Letter spacing must be non-negative");
            }

            // Line spacing validation
            if (lineSpacing != null && lineSpacing < 0) {
                return ResponseEntity.badRequest().body("Line spacing must be non-negative");
            }

            // Add text to timeline
            videoEditingService.addTextToTimeline(
                    sessionId, text, layer, timelineStartTime, timelineEndTime,
                    fontFamily, scale, fontColor, backgroundColor, positionX, positionY, opacity, alignment,
                    backgroundOpacity, backgroundBorderWidth, backgroundBorderColor, backgroundH,
                    backgroundW, backgroundBorderRadius,
                    textBorderColor, textBorderWidth, textBorderOpacity,
                    letterSpacing, lineSpacing, rotation); // Added rotation

            // Retrieve the newly added text segment from TimelineState
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            TextSegment addedTextSegment = timelineState.getTextSegments().stream()
                    .filter(t -> t.getText().equals(text) &&
                            Math.abs(t.getTimelineStartTime() - timelineStartTime) < 0.001 &&
                            Math.abs(t.getTimelineEndTime() - timelineEndTime) < 0.001 &&
                            t.getLayer() == layer)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added text segment"));

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("textSegmentId", addedTextSegment.getId());
            response.put("text", addedTextSegment.getText());
            response.put("layer", addedTextSegment.getLayer());
            response.put("timelineStartTime", addedTextSegment.getTimelineStartTime());
            response.put("timelineEndTime", addedTextSegment.getTimelineEndTime());
            response.put("fontFamily", addedTextSegment.getFontFamily());
            response.put("scale", addedTextSegment.getScale());
            response.put("fontColor", addedTextSegment.getFontColor());
            response.put("backgroundColor", addedTextSegment.getBackgroundColor());
            response.put("positionX", addedTextSegment.getPositionX());
            response.put("positionY", addedTextSegment.getPositionY());
            response.put("opacity", addedTextSegment.getOpacity());
            response.put("alignment", addedTextSegment.getAlignment());
            response.put("backgroundOpacity", addedTextSegment.getBackgroundOpacity());
            response.put("backgroundBorderWidth", addedTextSegment.getBackgroundBorderWidth());
            response.put("backgroundBorderColor", addedTextSegment.getBackgroundBorderColor());
            response.put("backgroundH", addedTextSegment.getBackgroundH());
            response.put("backgroundW", addedTextSegment.getBackgroundW());
            response.put("backgroundBorderRadius", addedTextSegment.getBackgroundBorderRadius());
            response.put("textBorderColor", addedTextSegment.getTextBorderColor());
            response.put("textBorderWidth", addedTextSegment.getTextBorderWidth());
            response.put("textBorderOpacity", addedTextSegment.getTextBorderOpacity());
            response.put("letterSpacing", addedTextSegment.getLetterSpacing());
            response.put("lineSpacing", addedTextSegment.getLineSpacing());
            response.put("rotation", addedTextSegment.getRotation()); // Added rotation
            response.put("keyframes", addedTextSegment.getKeyframes() != null ? addedTextSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error adding text to timeline: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-text")
    public ResponseEntity<?> updateTextSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Existing parameters
            String segmentId = (String) request.get("segmentId");
            String text = (String) request.get("text");
            String fontFamily = (String) request.get("fontFamily");
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            String alignment = (String) request.get("alignment");
            Double rotation = request.containsKey("rotation") ? Double.valueOf(request.get("rotation").toString()) : null; // New parameter
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            // Background parameters
            Double backgroundOpacity = request.containsKey("backgroundOpacity") ? Double.valueOf(request.get("backgroundOpacity").toString()) : null;
            Integer backgroundBorderWidth = request.containsKey("backgroundBorderWidth") ? Integer.valueOf(request.get("backgroundBorderWidth").toString()) : null;
            String backgroundBorderColor = (String) request.get("backgroundBorderColor");
            Integer backgroundH = request.containsKey("backgroundH") ? Integer.valueOf(request.get("backgroundH").toString()) : null;
            Integer backgroundW = request.containsKey("backgroundW") ? Integer.valueOf(request.get("backgroundW").toString()) : null;
            Integer backgroundBorderRadius = request.containsKey("backgroundBorderRadius") ? Integer.valueOf(request.get("backgroundBorderRadius").toString()) : null;

            // Text border parameters
            String textBorderColor = (String) request.get("textBorderColor");
            Integer textBorderWidth = request.containsKey("textBorderWidth") ? Integer.valueOf(request.get("textBorderWidth").toString()) : null;
            Double textBorderOpacity = request.containsKey("textBorderOpacity") ? Double.valueOf(request.get("textBorderOpacity").toString()) : null;

            // Letter spacing parameter
            Double letterSpacing = request.containsKey("letterSpacing") ? Double.valueOf(request.get("letterSpacing").toString()) : null;

            // Line spacing parameter
            Double lineSpacing = request.containsKey("lineSpacing") ? Double.valueOf(request.get("lineSpacing").toString()) : null;

            // Parse keyframes
            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    String property = entry.getKey();
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        // Validate keyframe value based on property
                        if (property.equals("rotation") && value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) {
                            return ResponseEntity.badRequest().body("Rotation keyframe value must be a valid number");
                        }
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Existing validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text content cannot be null or empty");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (alignment != null && !Arrays.asList("left", "right", "center").contains(alignment)) {
                return ResponseEntity.badRequest().body("Alignment must be 'left', 'right', or 'center'");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }

            // Background validation
            if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
                return ResponseEntity.badRequest().body("Background opacity must be between 0 and 1");
            }
            if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Background border width must be non-negative");
            }
            if (backgroundH != null && backgroundH < 0) {
                return ResponseEntity.badRequest().body("Background height must be non-negative");
            }
            if (backgroundW != null && backgroundW < 0) {
                return ResponseEntity.badRequest().body("Background width must be non-negative");
            }
            if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
                return ResponseEntity.badRequest().body("Background border radius must be non-negative");
            }

            // Text border validation
            if (textBorderWidth != null && textBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Text border width must be non-negative");
            }
            if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
                return ResponseEntity.badRequest().body("Text border opacity must be between 0 and 1");
            }

            // Letter spacing validation
            if (letterSpacing != null && letterSpacing < 0) {
                return ResponseEntity.badRequest().body("Letter spacing must be non-negative");
            }

            // Line spacing validation
            if (lineSpacing != null && lineSpacing < 0) {
                return ResponseEntity.badRequest().body("Line spacing must be non-negative");
            }

            // Update text segment
            videoEditingService.updateTextSegment(
                    sessionId, segmentId, text, fontFamily, scale,
                    fontColor, backgroundColor, positionX, positionY, opacity, timelineStartTime, timelineEndTime, layer, alignment,
                    backgroundOpacity, backgroundBorderWidth, backgroundBorderColor, backgroundH,
                    backgroundW, backgroundBorderRadius,
                    textBorderColor, textBorderWidth, textBorderOpacity,
                    letterSpacing, lineSpacing, rotation, parsedKeyframes);

            return ResponseEntity.ok().build();
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException e) {
            logger.error("IO error updating text segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating text segment: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error updating text segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }


//    AUDIO FUNCTIONALITY .......................................................................................

    @PostMapping("/{projectId}/upload-audio")
    public ResponseEntity<?> uploadAudio(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("audio") MultipartFile[] audioFiles,
            @RequestParam(value = "audioFileNames", required = false) String[] audioFileNames) {
        try {
            User user = getUserFromToken(token);
            Project updatedProject = videoEditingService.uploadAudioToProject(user, projectId, audioFiles, audioFileNames);

            // Extract audio metadata with waveform JSON paths from audioJson
            List<Map<String, String>> audioFilesMetadata = videoEditingService.getAudio(updatedProject);
            List<Map<String, String>> responseAudioFiles = audioFilesMetadata.stream()
                    .map(audio -> {
                        Map<String, String> audioData = new HashMap<>();
                        audioData.put("audioFileName", audio.get("audioFileName"));
                        audioData.put("audioPath", audio.get("audioPath"));
                        audioData.put("downloadUrl", cloudflareR2Service.generateDownloadUrl(audio.get("audioPath"), 3600)); // Add pre-signed URL
                        audioData.put("waveformJsonPath", audio.get("waveformJsonPath"));
                        return audioData;
                    })
                    .collect(Collectors.toList());

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("project", updatedProject);
            response.put("audioFiles", responseAudioFiles);

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            logger.error("Error uploading audio: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading audio: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-project-audio-to-timeline")
    public ResponseEntity<?> addProjectAudioToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : -1;
            Double startTime = request.get("startTime") != null ? ((Number) request.get("startTime")).doubleValue() : 0.0;
            Double endTime = request.get("endTime") != null ? ((Number) request.get("endTime")).doubleValue() : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;
            String audioFileName = (String) request.get("audioFileName");
            Double volume = request.get("volume") != null ? ((Number) request.get("volume")).doubleValue() : 1.0;

            // Validate parameters
            if (layer >= 0) {
                return ResponseEntity.badRequest().body("Audio layer must be negative");
            }
            if (startTime < 0) {
                return ResponseEntity.badRequest().body("Start time must be non-negative");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (audioFileName == null || audioFileName.isEmpty()) {
                return ResponseEntity.badRequest().body("Audio filename is required");
            }
            if (volume < 0 || volume > 1) {
                return ResponseEntity.badRequest().body("Volume must be between 0 and 1");
            }

            videoEditingService.addAudioToTimelineFromProject(
                    user, sessionId, projectId, layer, startTime, endTime, timelineStartTime, timelineEndTime, audioFileName);

            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment addedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getAudioPath().endsWith(audioFileName) &&
                            Math.abs(a.getTimelineStartTime() - timelineStartTime) < 0.001 &&
                            (endTime == null || Math.abs(a.getEndTime() - endTime) < 0.001))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added audio segment"));

            Map<String, Object> response = new HashMap<>();
            response.put("audioSegmentId", addedAudioSegment.getId());
            response.put("layer", addedAudioSegment.getLayer());
            response.put("timelineStartTime", addedAudioSegment.getTimelineStartTime());
            response.put("timelineEndTime", addedAudioSegment.getTimelineEndTime());
            response.put("startTime", addedAudioSegment.getStartTime());
            response.put("endTime", addedAudioSegment.getEndTime());
            response.put("volume", addedAudioSegment.getVolume());
            response.put("audioPath", addedAudioSegment.getAudioPath());
            response.put("waveformJsonPath", addedAudioSegment.getWaveformJsonPath());
            response.put("extracted", addedAudioSegment.isExtracted());
            response.put("keyframes", addedAudioSegment.getKeyframes() != null ? addedAudioSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException | InterruptedException e) { // Removed B2Exception
            logger.error("IO error adding audio to timeline: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding audio to timeline: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error adding audio to timeline: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }
    @PutMapping("/{projectId}/update-audio")
    public ResponseEntity<?> updateAudioSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String audioSegmentId = (String) request.get("audioSegmentId");
            Double startTime = request.containsKey("startTime") ? Double.valueOf(request.get("startTime").toString()) : null;
            Double endTime = request.containsKey("endTime") ? Double.valueOf(request.get("endTime").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Double volume = request.containsKey("volume") ? Double.valueOf(request.get("volume").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            if (audioSegmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: audioSegmentId");
            }
            if (startTime != null && startTime < 0) {
                return ResponseEntity.badRequest().body("Start time must be non-negative");
            }
            if (timelineStartTime != null && timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (volume != null && (volume < 0 || volume > 15)) {
                return ResponseEntity.badRequest().body("Volume must be between 0 and 15");
            }
            if (layer != null && layer >= 0) {
                return ResponseEntity.badRequest().body("Audio layer must be negative");
            }

            videoEditingService.updateAudioSegment(
                    sessionId, audioSegmentId, startTime, endTime, timelineStartTime, timelineEndTime, volume, layer, parsedKeyframes);

            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment updatedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getId().equals(audioSegmentId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find updated audio segment"));

            Map<String, Object> response = new HashMap<>();
            response.put("audioSegmentId", updatedAudioSegment.getId());
            response.put("layer", updatedAudioSegment.getLayer());
            response.put("timelineStartTime", updatedAudioSegment.getTimelineStartTime());
            response.put("timelineEndTime", updatedAudioSegment.getTimelineEndTime());
            response.put("startTime", updatedAudioSegment.getStartTime());
            response.put("endTime", updatedAudioSegment.getEndTime());
            response.put("volume", updatedAudioSegment.getVolume());
            response.put("audioPath", updatedAudioSegment.getAudioPath());
            response.put("waveformJsonPath", updatedAudioSegment.getWaveformJsonPath());
            response.put("isExtracted", updatedAudioSegment.isExtracted());
            response.put("keyframes", updatedAudioSegment.getKeyframes() != null ? updatedAudioSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException | InterruptedException e) { // Removed B2Exception
            logger.error("IO error updating audio segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating audio segment: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error updating audio segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/waveforms/{filename:.+}")
    public ResponseEntity<Void> serveWaveform(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = getUserFromToken(token);
            }

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Construct R2 path for waveform
            String r2Path = "audio/projects/" + projectId + "/waveforms/" + filename;

            // Verify waveform exists in project metadata (optional, add if metadata is stored)
            List<Map<String, String>> audioFiles = videoEditingService.getAudio(project);
            boolean waveformExists = audioFiles.stream()
                    .anyMatch(audio -> audio.get("waveformJsonPath") != null && audio.get("waveformJsonPath").endsWith(filename));
            if (!waveformExists) {
                logger.warn("Waveform not found in project metadata: {}", r2Path);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Generate pre-signed URL for the waveform
            String preSignedUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600); // 1-hour expiration

            // Redirect to the pre-signed URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, preSignedUrl)
                    .build();
        } catch (IOException e) {
            logger.error("Error generating pre-signed URL for waveform: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            logger.warn("Error serving waveform: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @GetMapping("/{projectId}/waveform-json/{filename:.+}")
    public ResponseEntity<Void> serveWaveformJson(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = getUserFromToken(token);
            }

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Construct R2 path for waveform JSON
            String r2Path = "audio/projects/" + projectId + "/waveforms/" + filename;

            // Verify waveform JSON exists in project metadata (optional, add if metadata is stored)
            List<Map<String, String>> audioFiles = videoEditingService.getAudio(project);
            boolean waveformJsonExists = audioFiles.stream()
                    .anyMatch(audio -> audio.get("waveformJsonPath") != null && audio.get("waveformJsonPath").endsWith(filename));
            if (!waveformJsonExists) {
                logger.warn("Waveform JSON not found in project metadata: {}", r2Path);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Generate pre-signed URL for the waveform JSON
            String preSignedUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600); // 1-hour expiration

            // Redirect to the pre-signed URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, preSignedUrl)
                    .build();
        } catch (IOException e) {
            logger.error("Error generating pre-signed URL for waveform JSON: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            logger.warn("Error serving waveform JSON: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @DeleteMapping("/{projectId}/remove-audio")
    public ResponseEntity<?> removeAudioSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String audioSegmentId) {
        try {
            System.out.println("Received request with token: " + token);
            User user = getUserFromToken(token);
            System.out.println("User authenticated: " + user.getId());

            if (audioSegmentId == null || audioSegmentId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: audioSegmentId");
            }

            videoEditingService.removeAudioSegment(sessionId, audioSegmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing audio segment: " + e.getMessage());
        }
    }

    //    IMAGE FUNCTIONALITY.........................................................................................
    @PostMapping("/{projectId}/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("image") MultipartFile[] imageFiles,
            @RequestParam(value = "imageFileNames", required = false) String[] imageFileNames) {
        try {
            User user = getUserFromToken(token);
            Project updatedProject = videoEditingService.uploadImageToProject(user, projectId, imageFiles, imageFileNames);
            return ResponseEntity.ok(updatedProject);
        } catch (IOException e) { // Removed B2Exception
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading image: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-project-image-to-timeline")
    public ResponseEntity<?> addProjectImageToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : 0;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;
            String imageFileName = (String) request.get("imageFileName");
            Double opacity = request.get("opacity") != null ? ((Number) request.get("opacity")).doubleValue() : null;
            Double rotation = request.get("rotation") != null ? ((Number) request.get("rotation")).doubleValue() : null; // New parameter
            Boolean isElement = request.get("isElement") != null ? Boolean.valueOf(request.get("isElement").toString()) : false;

            if (layer < 0) {
                return ResponseEntity.badRequest().body("Layer must be a non-negative integer");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (timelineEndTime != null && timelineEndTime < timelineStartTime) {
                return ResponseEntity.badRequest().body("Timeline end time must be greater than start time");
            }
            if (imageFileName == null || imageFileName.isEmpty()) {
                return ResponseEntity.badRequest().body("Image or element filename is required");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }

            videoEditingService.addImageToTimelineFromProject(
                    user, sessionId, projectId, layer, timelineStartTime, timelineEndTime, null, imageFileName, opacity, isElement, rotation);

            // Retrieve the updated timeline state to get the newly added segment
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            ImageSegment newSegment = timelineState.getImageSegments().stream()
                    .filter(segment -> segment.getImagePath().endsWith(imageFileName) &&
                            segment.getLayer() == layer &&
                            Math.abs(segment.getTimelineStartTime() - timelineStartTime) < 0.001)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find newly added segment"));

            return ResponseEntity.ok(newSegment);
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException e) {
            logger.error("IO error adding project image to timeline: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding project image or element to timeline: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error adding project image to timeline: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-image")
    public ResponseEntity<?> updateImageSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            Integer customWidth = request.containsKey("customWidth") ? Integer.valueOf(request.get("customWidth").toString()) : null;
            Integer customHeight = request.containsKey("customHeight") ? Integer.valueOf(request.get("customHeight").toString()) : null;
            Boolean maintainAspectRatio = request.containsKey("maintainAspectRatio") ? Boolean.valueOf(request.get("maintainAspectRatio").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Double cropL = request.containsKey("cropL") ? Double.valueOf(request.get("cropL").toString()) : null;
            Double cropR = request.containsKey("cropR") ? Double.valueOf(request.get("cropR").toString()) : null;
            Double cropT = request.containsKey("cropT") ? Double.valueOf(request.get("cropT").toString()) : null;
            Double cropB = request.containsKey("cropB") ? Double.valueOf(request.get("cropB").toString()) : null;
            Double rotation = request.containsKey("rotation") ? Double.valueOf(request.get("rotation").toString()) : null; // New parameter

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        // Validate keyframe value based on property
                        String property = entry.getKey();
                        if (property.equals("rotation") && value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) {
                            return ResponseEntity.badRequest().body("Rotation keyframe value must be a valid number");
                        }
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (customWidth != null && customWidth <= 0) {
                return ResponseEntity.badRequest().body("Custom width must be a positive value");
            }
            if (customHeight != null && customHeight <= 0) {
                return ResponseEntity.badRequest().body("Custom height must be a positive value");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (cropL != null && (cropL < 0 || cropL > 100)) {
                return ResponseEntity.badRequest().body("cropL must be between 0 and 100");
            }
            if (cropR != null && (cropR < 0 || cropR > 100)) {
                return ResponseEntity.badRequest().body("cropR must be between 0 and 100");
            }
            if (cropT != null && (cropT < 0 || cropT > 100)) {
                return ResponseEntity.badRequest().body("cropT must be between 0 and 100");
            }
            if (cropB != null && (cropB < 0 || cropB > 100)) {
                return ResponseEntity.badRequest().body("cropB must be between 0 and 100");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }
            // Validate total crop if static values are provided (not keyframed)
            if (parsedKeyframes == null ||
                    (!parsedKeyframes.containsKey("cropL") && !parsedKeyframes.containsKey("cropR") &&
                            !parsedKeyframes.containsKey("cropT") && !parsedKeyframes.containsKey("cropB"))) {
                double totalHorizontalCrop = (cropL != null ? cropL : 0.0) + (cropR != null ? cropR : 0.0);
                double totalVerticalCrop = (cropT != null ? cropT : 0.0) + (cropB != null ? cropB : 0.0);
                if (totalHorizontalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total horizontal crop (cropL + cropR) cannot be 100% or more");
                }
                if (totalVerticalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total vertical crop (cropT + cropB) cannot be 100% or more");
                }
            }

            videoEditingService.updateImageSegment(
                    sessionId, segmentId, positionX, positionY, scale, opacity, layer,
                    customWidth, customHeight, maintainAspectRatio,
                    timelineStartTime, timelineEndTime, cropL, cropR, cropT, cropB, rotation, parsedKeyframes);
            return ResponseEntity.ok().build();
        } catch (SessionNotFoundException e) {
            logger.warn("Session not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (IOException e) {
            logger.error("IO error updating image segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating image segment: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Forbidden action: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error updating image segment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }


    @DeleteMapping("/{projectId}/remove-image")
    public ResponseEntity<?> removeImageSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            // Validate segmentId
            if (segmentId == null || segmentId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }

            videoEditingService.removeImageSegment(sessionId, segmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing image segment: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/images/{filename:.+}")
    public ResponseEntity<Void> serveImage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = getUserFromToken(token);
            }

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            String r2Path = "image/projects/" + projectId + "/" + filename;
            String elementsR2Path = "elements/" + filename;

            // Determine the correct path based on whether it's a global element
            String targetPath;
            if (filename.startsWith("elements/")) {
                targetPath = elementsR2Path;
            } else {
                // Verify user has access to project-specific images
                if (user != null && !project.getUser().getId().equals(user.getId())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                targetPath = r2Path;
            }

            // Verify image exists in R2
            if (!cloudflareR2Service.fileExists(targetPath)) {
                logger.warn("Image not found in R2: {}", targetPath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Generate pre-signed URL for the image
            String preSignedUrl = cloudflareR2Service.generateDownloadUrl(targetPath, 3600); // 1-hour expiration

            // Redirect to the pre-signed URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, preSignedUrl)
                    .build();
        } catch (RuntimeException e) {
            logger.warn("Error serving image: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping("/{projectId}/add-keyframe")
    public ResponseEntity<?> addKeyframe(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            String segmentType = (String) request.get("segmentType");
            String property = (String) request.get("property");
            Double time = request.containsKey("time") ? Double.valueOf(request.get("time").toString()) : null;
            Object value = request.get("value");
            String interpolationType = (String) request.getOrDefault("interpolationType", "linear");

            if (segmentId == null || segmentType == null || property == null || time == null || value == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, segmentType, property, time, or value");
            }
            if (time < 0) {
                return ResponseEntity.badRequest().body("Time must be non-negative");
            }

            Keyframe keyframe = new Keyframe(time, value, interpolationType);
            videoEditingService.addKeyframeToSegment(sessionId, segmentId, segmentType, property, keyframe);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding keyframe: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/update-keyframe")
    public ResponseEntity<?> updateKeyframe(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            String segmentType = (String) request.get("segmentType");
            String property = (String) request.get("property");
            Double time = request.containsKey("time") ? Double.valueOf(request.get("time").toString()) : null;
            Object value = request.get("value");
            String interpolationType = (String) request.getOrDefault("interpolationType", "linear");

            if (segmentId == null || segmentType == null || property == null || time == null || value == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, segmentType, property, time, or value");
            }
            if (time < 0) {
                return ResponseEntity.badRequest().body("Time must be non-negative");
            }

            Keyframe keyframe = new Keyframe(time, value, interpolationType);
            videoEditingService.updateKeyframeToSegment(sessionId, segmentId, segmentType, property, keyframe);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating keyframe: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/remove-keyframe")
    public ResponseEntity<?> removeKeyframe(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId,
            @RequestParam String segmentType,
            @RequestParam String property,
            @RequestParam Double time) {
        try {
            User user = getUserFromToken(token);

            if (segmentId == null || segmentType == null || property == null || time == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, segmentType, property, or time");
            }
            if (time < 0) {
                return ResponseEntity.badRequest().body("Time must be non-negative");
            }

            videoEditingService.removeKeyframeFromSegment(sessionId, segmentId, segmentType, property, time);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing keyframe: " + e.getMessage());
        }
    }

    // Helper method to determine content type
    private String determineContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".webp")) return "image/webp";
        return "application/octet-stream"; // Default fallback
    }

    @PostMapping("/{projectId}/apply-filter")
    public ResponseEntity<?> applyFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            String filterName = (String) request.get("filterName");
            String filterValue = request.get("filterValue") != null ? request.get("filterValue").toString() : null;

            if (segmentId == null || filterName == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, filterName");
            }

            videoEditingService.applyFilter(sessionId, segmentId, filterName, filterValue);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error applying filter: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-filter")
    public ResponseEntity<Filter> updateFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Filter request) {
        try {
            User user = getUserFromToken(token);

            // Validate required parameters
            if (request.getSegmentId() == null || request.getFilterId() == null ||
                    request.getFilterName() == null || request.getFilterValue() == null) {
                return ResponseEntity.badRequest()
                        .body(null);
            }

            // Call the service method to update the filter
            videoEditingService.updateFilter(
                    sessionId,
                    request.getSegmentId(),
                    request.getFilterId(),
                    request.getFilterName(),
                    request.getFilterValue()
            );

            // Return the updated filter object
            Filter updatedFilter = new Filter();
            updatedFilter.setFilterId(request.getFilterId());
            updatedFilter.setSegmentId(request.getSegmentId());
            updatedFilter.setFilterName(request.getFilterName());
            updatedFilter.setFilterValue(request.getFilterValue());

            return ResponseEntity.ok(updatedFilter);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(null);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // Adding the new GET mapping here, after other segment-related endpoints
    @GetMapping("/{projectId}/segments/{segmentId}/filters")
    public ResponseEntity<List<Filter>> getFiltersForSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String segmentId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token); // Authenticate the user
            // Optionally, you could verify project ownership here:
            // Project project = projectRepository.findByIdAndUser(projectId, user);
            // if (project == null) throw new RuntimeException("Project not found or unauthorized");

            List<Filter> filters = videoEditingService.getFiltersForSegment(sessionId, segmentId);
            return ResponseEntity.ok(filters);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null); // Return 404 if segment not found
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null); // Return 500 for other unexpected errors
        }
    }

    @DeleteMapping("/{projectId}/remove-filter")
    public ResponseEntity<?> removeFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId,
            @RequestParam String filterId) {
        try {
            User user = getUserFromToken(token);

            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: filterId, segmentId");
            }

            videoEditingService.removeFilter(sessionId, segmentId,filterId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing filter: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/remove-all-filters")
    public ResponseEntity<?> removeAllFilters(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }

            videoEditingService.removeAllFilters(sessionId, segmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing all filters: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-transition")
    public ResponseEntity<?> addTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String type = (String) request.get("type");
            Double duration = request.get("duration") != null ? Double.valueOf(request.get("duration").toString()) : null;
            String segmentId = (String) request.get("segmentId");
            Boolean start = request.get("start") != null ? Boolean.valueOf(request.get("start").toString()) : null;
            Boolean end = request.get("end") != null ? Boolean.valueOf(request.get("end").toString()) : null;
            Integer layer = request.get("layer") != null ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, String> parameters = request.containsKey("parameters") ? (Map<String, String>) request.get("parameters") : null;

            // Validate required parameters
            if (type == null || duration == null || segmentId == null || start == null || end == null || layer == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: type, duration, segmentId, start, end, layer");
            }
            if (duration <= 0) {
                return ResponseEntity.badRequest().body("Duration must be positive");
            }
            if (!start && !end) {
                return ResponseEntity.badRequest().body("Transition must be applied at start, end, or both");
            }

            videoEditingService.addTransition(sessionId, type, duration, segmentId, start, end, layer, parameters);

            // Retrieve the newly added transition
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            Transition addedTransition = timelineState.getTransitions().stream()
                    .filter(t -> t.getSegmentId().equals(segmentId) &&
                            t.isStart() == start &&
                            t.isEnd() == end &&
                            t.getLayer() == layer &&
                            t.getType().equals(type) &&
                            Math.abs(t.getDuration() - duration) < 0.001)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added transition"));

            return ResponseEntity.ok(Map.of(
                    "transitionId", addedTransition.getId(),
                    "type", addedTransition.getType(),
                    "duration", addedTransition.getDuration(),
                    "segmentId", addedTransition.getSegmentId(),
                    "start", addedTransition.isStart(),
                    "end", addedTransition.isEnd(),
                    "layer", addedTransition.getLayer(),
                    "timelineStartTime", addedTransition.getTimelineStartTime(),
                    "parameters", addedTransition.getParameters()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding transition: " + e.getMessage());
        }
    }

    // NEW: Endpoint to update a transition
    @PutMapping("/{projectId}/update-transition")
    public ResponseEntity<?> updateTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String transitionId = (String) request.get("transitionId");
            String type = (String) request.get("type");
            Double duration = request.containsKey("duration") ? Double.valueOf(request.get("duration").toString()) : null;
            String segmentId = (String) request.get("segmentId");
            Boolean start = request.containsKey("start") ? Boolean.valueOf(request.get("start").toString()) : null;
            Boolean end = request.containsKey("end") ? Boolean.valueOf(request.get("end").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, String> parameters = request.containsKey("parameters") ? (Map<String, String>) request.get("parameters") : null;

            // Validate required parameters
            if (transitionId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: transitionId");
            }
            if (duration != null && duration <= 0) {
                return ResponseEntity.badRequest().body("Duration must be positive");
            }
            if (start != null && end != null && !start && !end) {
                return ResponseEntity.badRequest().body("Transition must be applied at start, end, or both");
            }

            Transition updatedTransition = videoEditingService.updateTransition(
                    sessionId, transitionId, type, duration, segmentId, start, end, layer, parameters);

            return ResponseEntity.ok(Map.of(
                    "transitionId", updatedTransition.getId(),
                    "type", updatedTransition.getType(),
                    "duration", updatedTransition.getDuration(),
                    "segmentId", updatedTransition.getSegmentId(),
                    "start", updatedTransition.isStart(),
                    "end", updatedTransition.isEnd(),
                    "layer", updatedTransition.getLayer(),
                    "timelineStartTime", updatedTransition.getTimelineStartTime(),
                    "parameters", updatedTransition.getParameters()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating transition: " + e.getMessage());
        }
    }

    // NEW: Endpoint to remove a transition
    @DeleteMapping("/{projectId}/remove-transition")
    public ResponseEntity<?> removeTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String transitionId) {
        try {
            User user = getUserFromToken(token);

            if (transitionId == null || transitionId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: transitionId");
            }

            videoEditingService.removeTransition(sessionId, transitionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing transition: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/transitions")
    public ResponseEntity<List<Transition>> getTransitions(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            return ResponseEntity.ok(timelineState.getTransitions());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // Delete Video Segment from Timeline
    @DeleteMapping("/timeline/video/{sessionId}/{segmentId}")
    public ResponseEntity<String> deleteVideoFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String segmentId) {
        try {
            videoEditingService.deleteVideoFromTimeline(sessionId, segmentId);
            return ResponseEntity.ok("Video segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // Delete Image Segment from Timeline
    @DeleteMapping("/timeline/image/{sessionId}/{imageId}")
    public ResponseEntity<String> deleteImageFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String imageId) {
        try {
            videoEditingService.deleteImageFromTimeline(sessionId, imageId);
            return ResponseEntity.ok("Image segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // Delete Audio Segment from Timeline
    @DeleteMapping("/timeline/audio/{sessionId}/{audioId}")
    public ResponseEntity<String> deleteAudioFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String audioId) {
        try {
            videoEditingService.deleteAudioFromTimeline(sessionId, audioId);
            return ResponseEntity.ok("Audio segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // Delete Text Segment from Timeline
    @DeleteMapping("/timeline/text/{sessionId}/{textId}")
    public ResponseEntity<String> deleteTextFromTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable String sessionId,
            @PathVariable String textId) {
        try {
            User user = getUserFromToken(token); // Authenticate user
            videoEditingService.deleteTextFromTimeline(sessionId, textId);
            return ResponseEntity.ok("Text segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> deleteProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId) {
        try {
            User user = getUserFromToken(token);
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Unauthorized to delete this project");
            }

            // Delete associated files
            videoEditingService.deleteProjectFiles(projectId);
            // Delete project from database
            projectRepository.delete(project);
            return ResponseEntity.ok().body("Project deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting project: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/audio/{filename:.+}")
    public ResponseEntity<Void> serveAudio(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                user = getUserFromToken(token);
            }

            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Construct R2 paths for audio and extracted audio
            String r2Path = "audio/projects/" + projectId + "/" + filename;
            String extractedR2Path = "audio/projects/" + projectId + "/extracted/" + filename;

            // Verify audio exists in project metadata
            List<Map<String, String>> audioFiles = videoEditingService.getAudio(project);
            boolean audioExists = audioFiles.stream()
                    .anyMatch(audio -> audio.get("audioPath").equals(r2Path) || audio.get("audioPath").equals(extractedR2Path));
            if (!audioExists) {
                logger.warn("Audio not found in project metadata: {}", r2Path);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Try primary path first, then extracted path
            String targetPath = cloudflareR2Service.fileExists(r2Path) ? r2Path : extractedR2Path;
            if (!cloudflareR2Service.fileExists(targetPath)) {
                logger.warn("Audio file not found in R2: {}", targetPath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Generate pre-signed URL for the audio
            String preSignedUrl = cloudflareR2Service.generateDownloadUrl(targetPath, 3600); // 1-hour expiration

            // Redirect to the pre-signed URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, preSignedUrl)
                    .build();
        } catch (IOException e) {
            logger.error("Error generating pre-signed URL for audio: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (RuntimeException e) {
            logger.warn("Error serving audio: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    // Helper method to serve Resource
    private ResponseEntity<Resource> serveResource(File file, String filename, String contentType) throws IOException {
        Resource resource = new InputStreamResource(Files.newInputStream(file.toPath())) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public long contentLength() throws IOException {
                return file.length();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
    @GetMapping("/{projectId}/audio-duration/{filename:.+}")
    public ResponseEntity<Double> getAudioDuration(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = getUserFromToken(token);
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));
            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String sanitizedFilename = filename; // Assuming sanitizeFilename is not needed or moved to service
            double duration = videoEditingService.getAudioDuration(projectId, sanitizedFilename);
            return ResponseEntity.ok(duration);
        } catch (IOException | InterruptedException e) { // Removed B2Exception
            logger.error("Error getting audio duration for projectId: {}, filename: {}", projectId, filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        } catch (RuntimeException e) {
            logger.warn("Not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return null;
        return filename.replaceAll("[^a-zA-Z0-9._-]", "");
    }


    // Helper method to determine audio content type
    private String determineAudioContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".wav")) return "audio/wav";
        if (filename.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream"; // Default fallback
    }
    @GetMapping("/check-cdn-availability")
    public ResponseEntity<Boolean> checkCdnAvailability(@RequestParam String cdnUrl) {
        try {
            boolean isAvailable = cloudflareR2Service.isCdnUrlAvailable(cdnUrl);
            logger.debug("CDN availability check for URL {}: {}", cdnUrl, isAvailable);
            return ResponseEntity.ok(isAvailable);
        } catch (Exception e) {
            logger.error("Error checking CDN availability for URL {}: {}", cdnUrl, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(false);
        }
    }

    @DeleteMapping("/{projectId}/remove-segments")
    public ResponseEntity<?> removeMultipleSegments(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract segmentIds from request body
            @SuppressWarnings("unchecked")
            List<String> segmentIds = (List<String>) request.get("segmentIds");

            // Validate input
            if (segmentIds == null || segmentIds.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing or empty required parameter: segmentIds");
            }
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: sessionId");
            }

            // Verify project exists and user has access
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));
            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Unauthorized to modify this project");
            }

            // Call service method to delete segments
            videoEditingService.deleteMultipleSegments(sessionId, segmentIds);

            return ResponseEntity.ok().body("Segments deleted successfully");
        } catch (ClassCastException e) {
            return ResponseEntity.badRequest().body("Invalid segmentIds format: must be a list of strings");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error removing segments: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing segments: " + e.getMessage());
        }
    }

    // New endpoint to check both R2 file existence and CDN availability
    @GetMapping("/check-file-availability")
    public ResponseEntity<Map<String, Boolean>> checkFileAvailability(
            @RequestHeader("Authorization") String token,
            @RequestParam String r2Path,
            @RequestParam String cdnUrl) throws IOException {
        try {
            User user = getUserFromToken(token);
            // Check if file exists in R2
            boolean fileExists = cloudflareR2Service.fileExists(r2Path);
            // Check if CDN URL is available
            boolean cdnAvailable = cloudflareR2Service.isCdnUrlAvailable(cdnUrl);
            Map<String, Boolean> response = new HashMap<>();
            response.put("fileExists", fileExists);
            response.put("cdnAvailable", cdnAvailable);
            logger.debug("File availability check: r2Path={}, cdnUrl={}, fileExists={}, cdnAvailable={}",
                    r2Path, cdnUrl, fileExists, cdnAvailable);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.warn("Unauthorized access for file availability check: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("fileExists", false, "cdnAvailable", false));
        }
    }

    @PostMapping("/{projectId}/subtitles")
    public ResponseEntity<?> addAutoSubtitlesToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody(required = false) Map<String, Object> subtitleProperties) {
        try {
            User user = getUserFromToken(token);
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Unauthorized to modify this project");
            }

            // Call the service method to add subtitles
            videoEditingService.addAutoSubtitlesToTimeline(sessionId, projectId, subtitleProperties);

            // Retrieve the updated timeline state
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            List<TextSegment> addedSubtitles = timelineState.getTextSegments().stream()
                    .filter(t -> t.getLayer() == videoEditingService.findTopmostLayer(timelineState))
                    .collect(Collectors.toList());

            // Prepare response
            List<Map<String, Object>> subtitleData = addedSubtitles.stream().map(t -> {
                Map<String, Object> data = new HashMap<>();
                data.put("textSegmentId", t.getId());
                data.put("text", t.getText());
                data.put("timelineStartTime", t.getTimelineStartTime());
                data.put("timelineEndTime", t.getTimelineEndTime());
                data.put("layer", t.getLayer());
                data.put("positionX", t.getPositionX());
                data.put("positionY", t.getPositionY());
                data.put("fontFamily", t.getFontFamily());
                data.put("fontColor", t.getFontColor());
                data.put("backgroundColor", t.getBackgroundColor());
                data.put("backgroundOpacity", t.getBackgroundOpacity());
                data.put("scale", t.getScale());
                data.put("alignment", t.getAlignment());
                data.put("backgroundH", t.getBackgroundH());
                data.put("backgroundW", t.getBackgroundW());
                data.put("backgroundBorderRadius", t.getBackgroundBorderRadius());
                data.put("letterSpacing", t.getLetterSpacing());
                data.put("lineSpacing", t.getLineSpacing());
                data.put("rotation", t.getRotation());
                data.put("isSubtitle", t.isSubtitle());
                return data;
            }).collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Subtitles added successfully");
            response.put("subtitles", subtitleData);

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding subtitles: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-multiple-text")
    public ResponseEntity<?> updateMultipleTextSegments(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract segmentIds as a list
            @SuppressWarnings("unchecked")
            List<String> segmentIds = (List<String>) request.get("segmentIds");
            String text = (String) request.get("text");
            String fontFamily = (String) request.get("fontFamily");
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            String alignment = (String) request.get("alignment");
            Double rotation = request.containsKey("rotation") ? Double.valueOf(request.get("rotation").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            // Background parameters
            Double backgroundOpacity = request.containsKey("backgroundOpacity") ? Double.valueOf(request.get("backgroundOpacity").toString()) : null;
            Integer backgroundBorderWidth = request.containsKey("backgroundBorderWidth") ? Integer.valueOf(request.get("backgroundBorderWidth").toString()) : null;
            String backgroundBorderColor = (String) request.get("backgroundBorderColor");
            Integer backgroundH = request.containsKey("backgroundH") ? Integer.valueOf(request.get("backgroundH").toString()) : null;
            Integer backgroundW = request.containsKey("backgroundW") ? Integer.valueOf(request.get("backgroundW").toString()) : null;
            Integer backgroundBorderRadius = request.containsKey("backgroundBorderRadius") ? Integer.valueOf(request.get("backgroundBorderRadius").toString()) : null;

            // Text border parameters
            String textBorderColor = (String) request.get("textBorderColor");
            Integer textBorderWidth = request.containsKey("textBorderWidth") ? Integer.valueOf(request.get("textBorderWidth").toString()) : null;
            Double textBorderOpacity = request.containsKey("textBorderOpacity") ? Double.valueOf(request.get("textBorderOpacity").toString()) : null;

            // Letter spacing parameter
            Double letterSpacing = request.containsKey("letterSpacing") ? Double.valueOf(request.get("letterSpacing").toString()) : null;

            // Line spacing parameter
            Double lineSpacing = request.containsKey("lineSpacing") ? Double.valueOf(request.get("lineSpacing").toString()) : null;

            // Parse keyframes
            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    String property = entry.getKey();
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        // Validate keyframe value based on property
                        if (property.equals("rotation") && value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) {
                            return ResponseEntity.badRequest().body("Rotation keyframe value must be a valid number");
                        }
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(property, kfList);
                }
            }

            // Validation
            if (segmentIds == null || segmentIds.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing or empty required parameter: segmentIds");
            }
            // Allow text to be null if not updating text content (e.g., for styling updates)
            if (text != null && text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text content cannot be empty if provided");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (alignment != null && !Arrays.asList("left", "right", "center").contains(alignment)) {
                return ResponseEntity.badRequest().body("Alignment must be 'left', 'right', or 'center'");
            }
            if (rotation != null && !Double.isFinite(rotation)) {
                return ResponseEntity.badRequest().body("Rotation must be a valid number");
            }
            if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
                return ResponseEntity.badRequest().body("Background opacity must be between 0 and 1");
            }
            if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Background border width must be non-negative");
            }
            if (backgroundH != null && backgroundH < 0) {
                return ResponseEntity.badRequest().body("Background height must be non-negative");
            }
            if (backgroundW != null && backgroundW < 0) {
                return ResponseEntity.badRequest().body("Background width must be non-negative");
            }
            if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
                return ResponseEntity.badRequest().body("Background border radius must be non-negative");
            }
            if (textBorderWidth != null && textBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Text border width must be non-negative");
            }
            if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
                return ResponseEntity.badRequest().body("Text border opacity must be between 0 and 1");
            }
            if (letterSpacing != null && letterSpacing < 0) {
                return ResponseEntity.badRequest().body("Letter spacing must be non-negative");
            }
            if (lineSpacing != null && lineSpacing < 0) {
                return ResponseEntity.badRequest().body("Line spacing must be non-negative");
            }

            // Call the service method
            videoEditingService.updateMultipleTextSegments(
                    sessionId, segmentIds, text, fontFamily, scale,
                    fontColor, backgroundColor, positionX, positionY, opacity,
                    timelineStartTime, timelineEndTime, layer, alignment,
                    backgroundOpacity, backgroundBorderWidth, backgroundBorderColor,
                    backgroundH, backgroundW, backgroundBorderRadius,
                    textBorderColor, textBorderWidth, textBorderOpacity,
                    letterSpacing, lineSpacing, rotation, parsedKeyframes);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating multiple text segments: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/generate-ai-audio")
    public ResponseEntity<?> generateAiAudioToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters from the request
            String text = (String) request.get("text");
            String voiceName = (String) request.get("voiceName");
            String languageCode = (String) request.get("languageCode");
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : -1;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;

            // Validate parameters
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text is required and cannot be empty");
            }
            if (voiceName == null || voiceName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Voice name is required");
            }
            if (languageCode == null || languageCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Language code is required");
            }
            if (layer >= 0) {
                return ResponseEntity.badRequest().body("Audio layer must be negative (e.g., -1, -2, -3)");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }

            // Call the service method to generate AI audio and add to timeline
            videoEditingService.generateTtsAndAddToTimeline(
                    sessionId, projectId, text, voiceName, languageCode, layer, timelineStartTime, timelineEndTime);

            // Retrieve the newly added audio segment from TimelineState
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment addedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getAudioPath().contains(projectId + "_tts_") &&
                            Math.abs(a.getTimelineStartTime() - timelineStartTime) < 0.001)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added AI audio segment"));

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("audioSegmentId", addedAudioSegment.getId());
            response.put("layer", addedAudioSegment.getLayer());
            response.put("timelineStartTime", addedAudioSegment.getTimelineStartTime());
            response.put("timelineEndTime", addedAudioSegment.getTimelineEndTime());
            response.put("startTime", addedAudioSegment.getStartTime());
            response.put("endTime", addedAudioSegment.getEndTime());
            response.put("volume", addedAudioSegment.getVolume());
            response.put("audioPath", addedAudioSegment.getAudioPath());
            response.put("waveformJsonPath", addedAudioSegment.getWaveformJsonPath());
            response.put("isExtracted", addedAudioSegment.isExtracted());
            response.put("keyframes", addedAudioSegment.getKeyframes() != null ?
                    addedAudioSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating AI audio: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Unauthorized or project not found: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }
}