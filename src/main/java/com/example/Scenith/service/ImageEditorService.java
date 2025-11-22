package com.example.Scenith.service;

import com.example.Scenith.dto.CreateImageProjectRequest;
import com.example.Scenith.dto.ExportImageRequest;
import com.example.Scenith.dto.UpdateImageProjectRequest;
import com.example.Scenith.entity.ImageProject;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ImageProjectRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ImageEditorService {

    private static final Logger logger = LoggerFactory.getLogger(ImageEditorService.class);

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    private final ImageProjectRepository imageProjectRepository;
    private final UserRepository userRepository;
    private final ImageRenderService imageRenderService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final SqsService sqsService;
    private final CloudflareR2Service cloudflareR2Service;

    public ImageEditorService(
            ImageProjectRepository imageProjectRepository,
            UserRepository userRepository,
            ImageRenderService imageRenderService,
            JwtUtil jwtUtil,
            ObjectMapper objectMapper,
            SqsService sqsService,
            CloudflareR2Service cloudflareR2Service) {
        this.imageProjectRepository = imageProjectRepository;
        this.userRepository = userRepository;
        this.imageRenderService = imageRenderService;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.sqsService = sqsService;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    /**
     * Create new image project
     */
    @Transactional
    public ImageProject createProject(User user, CreateImageProjectRequest request) {
        logger.info("Creating new project for user: {}", user.getId());

        // Validate input
        if (request.getProjectName() == null || request.getProjectName().trim().isEmpty()) {
            throw new IllegalArgumentException("Project name is required");
        }
        if (request.getCanvasWidth() == null || request.getCanvasWidth() <= 0) {
            throw new IllegalArgumentException("Invalid canvas width");
        }
        if (request.getCanvasHeight() == null || request.getCanvasHeight() <= 0) {
            throw new IllegalArgumentException("Invalid canvas height");
        }

        ImageProject project = new ImageProject();
        project.setUser(user);
        project.setProjectName(request.getProjectName());
        project.setCanvasWidth(request.getCanvasWidth());
        project.setCanvasHeight(request.getCanvasHeight());
        project.setCanvasBackgroundColor(request.getCanvasBackgroundColor() != null ?
                request.getCanvasBackgroundColor() : "#FFFFFF");
        project.setStatus("DRAFT");

        // Initialize empty design JSON
        try {
            Map<String, Object> initialDesign = Map.of(
                    "version", "1.0",
                    "canvas", Map.of(
                            "width", request.getCanvasWidth(),
                            "height", request.getCanvasHeight(),
                            "backgroundColor", project.getCanvasBackgroundColor()
                    ),
                    "layers", List.of()
            );
            project.setDesignJson(objectMapper.writeValueAsString(initialDesign));
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize design JSON", e);
        }

        imageProjectRepository.save(project);
        logger.info("Project created successfully: {}", project.getId());

        return project;
    }

    /**
     * Update existing project
     */
    @Transactional
    public ImageProject updateProject(User user, Long projectId, UpdateImageProjectRequest request) {
        logger.info("Updating project: {} for user: {}", projectId, user.getId());

        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (request.getProjectName() != null && !request.getProjectName().trim().isEmpty()) {
            project.setProjectName(request.getProjectName());
        }

        if (request.getDesignJson() != null) {
            // Validate JSON format
            try {
                objectMapper.readTree(request.getDesignJson());
                project.setDesignJson(request.getDesignJson());
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid design JSON format", e);
            }
        }

        imageProjectRepository.save(project);
        logger.info("Project updated successfully: {}", projectId);

        return project;
    }

    /**
     * Get project by ID
     */
    public ImageProject getProject(User user, Long projectId) {
        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        // Refresh CDN URL if export exists
        if (project.getLastExportedUrl() != null && project.getOutputVideoPath() != null) {
            try {
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(project.getOutputVideoPath(), 3600);
                project.setLastExportedUrl(cdnUrl);
                imageProjectRepository.save(project);
            } catch (Exception e) {
                logger.warn("Failed to refresh CDN URL for project: {}", projectId);
            }
        }

        return project;
    }

    /**
     * Get all projects for user
     */
    public List<ImageProject> getUserProjects(User user) {
        List<ImageProject> projects = imageProjectRepository.findByUserOrderByUpdatedAtDesc(user);

        // Refresh CDN URLs for projects with exports
        for (ImageProject project : projects) {
            if (project.getLastExportedUrl() != null && project.getOutputVideoPath() != null) {
                try {
                    String cdnUrl = cloudflareR2Service.generateDownloadUrl(project.getOutputVideoPath(), 3600);
                    project.setLastExportedUrl(cdnUrl);
                    imageProjectRepository.save(project);
                } catch (Exception e) {
                    logger.warn("Failed to refresh CDN URL for project: {}", project.getId());
                }
            }
        }

        return projects;
    }

    /**
     * Get projects by status
     */
    public List<ImageProject> getUserProjectsByStatus(User user, String status) {
        return imageProjectRepository.findByUserAndStatus(user, status);
    }

    /**
     * Export project to image - sends to SQS queue for async processing
     */
    @Transactional
    public ImageProject exportProject(User user, Long projectId, ExportImageRequest request)
            throws IOException {

        logger.info("Exporting project: {} for user: {}", projectId, user.getId());

        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (project.getDesignJson() == null || project.getDesignJson().trim().isEmpty()) {
            throw new IllegalArgumentException("Project has no design to export");
        }

        // Validate export format
        String format = request.getFormat() != null ? request.getFormat().toUpperCase() : "PNG";
        if (!format.equals("PNG") && !format.equals("JPG") && !format.equals("JPEG") && !format.equals("PDF")) {
            throw new IllegalArgumentException("Invalid export format. Supported: PNG, JPG, PDF");
        }

        // Set status to PROCESSING
        project.setStatus("PROCESSING");
        project.setProgressPercentage(0);
        project.setLastExportedUrl(null);
        imageProjectRepository.save(project);

        try {
            // Send job to SQS
            Map<String, Object> taskDetails = Map.of(
                    "projectId", projectId,
                    "userId", user.getId(),
                    "format", format,
                    "quality", request.getQuality() != null ? request.getQuality() : 90,
                    "designJson", project.getDesignJson()
            );
            String messageBody = objectMapper.writeValueAsString(taskDetails);
            sqsService.sendMessage(messageBody, videoExportQueueUrl);
            logger.info("Sent image export job {} to SQS queue {}", projectId, videoExportQueueUrl);

            return project;
        } catch (Exception e) {
            logger.error("Failed to send export job to SQS: {}", projectId, e);
            project.setStatus("FAILED");
            project.setProgressPercentage(0);
            imageProjectRepository.save(project);
            throw new IOException("Failed to queue export job: " + e.getMessage(), e);
        }
    }

    /**
     * Process export job from SQS (called by ImageExportJobWorker)
     */
    @Transactional
    public void processExportFromSqs(Map<String, Object> taskDetails) {
        Long projectId = Long.valueOf(taskDetails.get("projectId").toString());
        Long userId = Long.valueOf(taskDetails.get("userId").toString());
        String format = taskDetails.get("format").toString();
        Integer quality = Integer.valueOf(taskDetails.get("quality").toString());
        String designJson = taskDetails.get("designJson").toString();

        ImageProject project = imageProjectRepository.findById(projectId)
                .filter(p -> p.getUser().getId().equals(userId))
                .orElseThrow(() -> new RuntimeException("Project not found or unauthorized"));

        if (!project.getStatus().equals("PROCESSING")) {
            logger.warn("Project {} is not in PROCESSING state, current state: {}", projectId, project.getStatus());
            return;
        }

        try {
            // Render the design
            String r2Path = imageRenderService.renderDesign(
                    designJson,
                    format,
                    quality,
                    userId,
                    projectId
            );

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600);

            // Update project with export info
            project.setOutputVideoPath(r2Path);
            project.setLastExportedUrl(cdnUrl);
            project.setLastExportFormat(format);
            project.setStatus("COMPLETED");
            project.setProgressPercentage(100);

            imageProjectRepository.save(project);
            logger.info("Project exported successfully: {}", projectId);

        } catch (Exception e) {
            logger.error("Failed to export project: {}", projectId, e);
            project.setStatus("FAILED");
            project.setProgressPercentage(0);
            imageProjectRepository.save(project);
            throw new RuntimeException("Export processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Delete project
     */
    @Transactional
    public void deleteProject(User user, Long projectId) {
        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        // Delete exported file from R2 if exists
        if (project.getOutputVideoPath() != null) {
            try {
                cloudflareR2Service.deleteFile(project.getOutputVideoPath());
                logger.info("Deleted exported file from R2: {}", project.getOutputVideoPath());
            } catch (Exception e) {
                logger.warn("Failed to delete exported file from R2: {}", project.getOutputVideoPath());
            }
        }

        imageProjectRepository.delete(project);
        logger.info("Project deleted: {}", projectId);
    }

    /**
     * Get user from JWT token
     */
    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}