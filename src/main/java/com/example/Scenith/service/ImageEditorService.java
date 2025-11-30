package com.example.Scenith.service;

import com.example.Scenith.dto.imagedto.CreateImageProjectRequest;
import com.example.Scenith.dto.imagedto.ExportImageRequest;
import com.example.Scenith.dto.imagedto.UpdateImageProjectRequest;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageProject;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.repository.imagerepository.ImageProjectRepository;
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
    private final CloudflareR2Service cloudflareR2Service;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public ImageEditorService(
            ImageProjectRepository imageProjectRepository,
            UserRepository userRepository,
            ImageRenderService imageRenderService,
            CloudflareR2Service cloudflareR2Service,
            JwtUtil jwtUtil,
            ObjectMapper objectMapper) {
        this.imageProjectRepository = imageProjectRepository;
        this.userRepository = userRepository;
        this.imageRenderService = imageRenderService;
        this.cloudflareR2Service = cloudflareR2Service;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * Create new image project
     */
    @Transactional
    public ImageProject createProject(User user, CreateImageProjectRequest request) {
        logger.info("Creating new project for user: {}", user.getId());

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

        // Initialize with pages array containing one page
        try {
            Map<String, Object> initialDesign = Map.of(
                    "version", "1.0",
                    "pages", List.of(
                            Map.of(
                                    "id", "page-" + System.currentTimeMillis(),
                                    "canvas", Map.of(
                                            "width", request.getCanvasWidth(),
                                            "height", request.getCanvasHeight(),
                                            "backgroundColor", project.getCanvasBackgroundColor()
                                    ),
                                    "layers", List.of()
                            )
                    )
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
    public ImageProject exportProject(User user, Long projectId, ExportImageRequest request, Integer pageIndex)
            throws IOException, InterruptedException {

        logger.info("Exporting project: {} page: {} for user: {}", projectId, pageIndex, user.getId());

        ImageProject project = imageProjectRepository.findByIdAndUser(projectId, user)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (project.getDesignJson() == null || project.getDesignJson().trim().isEmpty()) {
            throw new IllegalArgumentException("Project has no design to export");
        }

        // Parse the design JSON to extract specific page
        Map<String, Object> fullDesign = objectMapper.readValue(project.getDesignJson(), Map.class);
        List<Map<String, Object>> pages = (List<Map<String, Object>>) fullDesign.get("pages");

        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("No pages found in project");
        }

        // Get the specific page to export (default to 0 if not specified)
        int targetPageIndex = pageIndex != null ? pageIndex : 0;
        if (targetPageIndex < 0 || targetPageIndex >= pages.size()) {
            throw new IllegalArgumentException("Invalid page index");
        }

        Map<String, Object> targetPage = pages.get(targetPageIndex);

        // Create a single-page design for export
        Map<String, Object> exportDesign = Map.of(
                "version", fullDesign.get("version"),
                "canvas", targetPage.get("canvas"),
                "layers", targetPage.get("layers")
        );

        String format = request.getFormat() != null ? request.getFormat().toUpperCase() : "PNG";
        if (!format.equals("PNG") && !format.equals("JPG") && !format.equals("JPEG") && !format.equals("PDF")) {
            throw new IllegalArgumentException("Invalid export format. Supported: PNG, JPG, PDF");
        }

        project.setStatus("PROCESSING");
        imageProjectRepository.save(project);

        try {
            String exportR2Path = imageRenderService.renderDesign(
                    objectMapper.writeValueAsString(exportDesign),
                    format,
                    request.getQuality(),
                    user.getId(),
                    projectId
            );

            // Generate URLs for the exported image
            Map<String, String> urls = cloudflareR2Service.generateUrls(exportR2Path, 3600);
            
            project.setLastExportedUrl(urls.get("cdnUrl"));
            project.setLastExportFormat(format);
            project.setStatus("COMPLETED");

            imageProjectRepository.save(project);
            logger.info("Project page {} exported successfully: {}", targetPageIndex, projectId);

            return project;

        } catch (Exception e) {
            logger.error("Failed to export project page: {}", projectId, e);
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

        // Delete last exported image from R2 if exists
        if (project.getLastExportedUrl() != null) {
            try {
                // Extract R2 path from CDN URL
                String cdnBaseUrl = cloudflareR2Service.generateUrls("dummy", 0).get("cdnUrl");
                String baseUrl = cdnBaseUrl.substring(0, cdnBaseUrl.lastIndexOf("/"));
                String r2Path = project.getLastExportedUrl().replace(baseUrl + "/", "");
                
                cloudflareR2Service.deleteFile(r2Path);
                logger.info("Deleted exported image from R2: {}", r2Path);
            } catch (Exception e) {
                logger.warn("Failed to delete exported image from R2: {}", project.getLastExportedUrl(), e);
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