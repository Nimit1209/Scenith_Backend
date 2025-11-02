package com.example.Scenith.service;

import com.example.Scenith.dto.CreateImageProjectRequest;
import com.example.Scenith.dto.ExportImageRequest;
import com.example.Scenith.dto.UpdateImageProjectRequest;
import com.example.Scenith.entity.ImageProject;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ImageProjectRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ImageEditorService {

    private static final Logger logger = LoggerFactory.getLogger(ImageEditorService.class);

    private final ImageProjectRepository imageProjectRepository;
    private final UserRepository userRepository;
    private final ImageRenderService imageRenderService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public ImageEditorService(
            ImageProjectRepository imageProjectRepository,
            UserRepository userRepository,
            ImageRenderService imageRenderService,
            JwtUtil jwtUtil,
            ObjectMapper objectMapper) {
        this.imageProjectRepository = imageProjectRepository;
        this.userRepository = userRepository;
        this.imageRenderService = imageRenderService;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
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
        return imageProjectRepository.findByIdAndUser(projectId, user)
            .orElseThrow(() -> new IllegalArgumentException("Project not found"));
    }

    /**
     * Get all projects for user
     */
    public List<ImageProject> getUserProjects(User user) {
        return imageProjectRepository.findByUserOrderByUpdatedAtDesc(user);
    }

    /**
     * Get projects by status
     */
    public List<ImageProject> getUserProjectsByStatus(User user, String status) {
        return imageProjectRepository.findByUserAndStatus(user, status);
    }

    /**
     * Export project to image
     */
    @Transactional
    public ImageProject exportProject(User user, Long projectId, ExportImageRequest request)
            throws IOException, InterruptedException {
        
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
        imageProjectRepository.save(project);

        try {
            // Render the design
            String relativePath = imageRenderService.renderDesign(
                project.getDesignJson(),
                format,
                request.getQuality(),
                user.getId(),
                projectId
            );

            // Update project with export info
            String cdnUrl = "http://localhost:8080/" + relativePath;
            project.setLastExportedUrl(cdnUrl);
            project.setLastExportFormat(format);
            project.setStatus("COMPLETED");
            
            imageProjectRepository.save(project);
            logger.info("Project exported successfully: {}", projectId);

            return project;

        } catch (Exception e) {
            logger.error("Failed to export project: {}", projectId, e);
            project.setStatus("FAILED");
            imageProjectRepository.save(project);
            throw e;
        }
    }

    /**
     * Delete project
     */
    @Transactional
    public void deleteProject(User user, Long projectId) {
        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
            .orElseThrow(() -> new IllegalArgumentException("Project not found"));

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