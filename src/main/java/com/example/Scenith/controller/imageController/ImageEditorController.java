package com.example.Scenith.controller.imageController;

import com.example.Scenith.dto.imagedto.CreateImageProjectRequest;
import com.example.Scenith.dto.imagedto.ExportImageRequest;
import com.example.Scenith.dto.imagedto.UpdateImageProjectRequest;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageAsset;
import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.entity.imageentity.ImageProject;
import com.example.Scenith.service.imageService.ElementDownloadService;
import com.example.Scenith.service.imageService.ImageAssetService;
import com.example.Scenith.service.imageService.ImageEditorService;
import com.example.Scenith.service.imageService.ImageElementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/image-editor")
public class ImageEditorController {

    private final ImageEditorService imageEditorService;
    private final ImageAssetService imageAssetService;
    private final ImageElementService imageElementService;
    private final ElementDownloadService elementDownloadService;


    /**
     * Create new project
     * POST /api/image-editor/projects
     */
    @PostMapping("/projects")
    public ResponseEntity<?> createProject(
            @RequestHeader("Authorization") String token,
            @RequestBody CreateImageProjectRequest request) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.createProject(user, request);
            return ResponseEntity.ok(project);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to create project: " + e.getMessage()));
        }
    }

    /**
     * Get all projects for user
     * GET /api/image-editor/projects
     */
    @GetMapping("/projects")
    public ResponseEntity<?> getUserProjects(@RequestHeader("Authorization") String token) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            List<ImageProject> projects = imageEditorService.getUserProjects(user);
            return ResponseEntity.ok(projects);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve projects: " + e.getMessage()));
        }
    }

    /**
     * Get single project
     * GET /api/image-editor/projects/{id}
     */
    @GetMapping("/projects/{id}")
    public ResponseEntity<?> getProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.getProject(user, id);
            return ResponseEntity.ok(project);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve project: " + e.getMessage()));
        }
    }

    /**
     * Update project
     * PUT /api/image-editor/projects/{id}
     */
    @PutMapping("/projects/{id}")
    public ResponseEntity<?> updateProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestBody UpdateImageProjectRequest request) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.updateProject(user, id, request);
            return ResponseEntity.ok(project);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to update project: " + e.getMessage()));
        }
    }

    /**
     * Export project to image
     * POST /api/image-editor/projects/{id}/export
     */
    @PostMapping("/projects/{projectId}/export")
    public ResponseEntity<Map<String, String>> exportProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestBody ExportImageRequest request,
            @RequestParam(required = false) Integer pageIndex) {

        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.exportProject(user, projectId, request, pageIndex);

            return ResponseEntity.ok(Map.of(
                    "message", "Export successful",
                    "exportUrl", project.getLastExportedUrl()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete project
     * DELETE /api/image-editor/projects/{id}
     */
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<?> deleteProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            imageEditorService.deleteProject(user, id);
            return ResponseEntity.ok(Map.of("message", "Project deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to delete project: " + e.getMessage()));
        }
    }

    /**
     * Upload asset
     * POST /api/image-editor/assets/upload
     */
    @PostMapping("/assets/upload")
    public ResponseEntity<?> uploadAsset(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "assetType", defaultValue = "IMAGE") String assetType) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageAsset asset = imageAssetService.uploadAsset(user, file, assetType);
            return ResponseEntity.ok(asset);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Get all assets for user
     * GET /api/image-editor/assets
     */
    @GetMapping("/assets")
    public ResponseEntity<?> getUserAssets(
            @RequestHeader("Authorization") String token,
            @RequestParam(value = "type", required = false) String assetType) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            List<ImageAsset> assets;
            if (assetType != null) {
                assets = imageAssetService.getUserAssetsByType(user, assetType);
            } else {
                assets = imageAssetService.getUserAssets(user);
            }
            return ResponseEntity.ok(assets);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve assets: " + e.getMessage()));
        }
    }

    /**
     * Get single asset
     * GET /api/image-editor/assets/{id}
     */
    @GetMapping("/assets/{id}")
    public ResponseEntity<?> getAsset(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageAsset asset = imageAssetService.getAssetById(user, id);
            return ResponseEntity.ok(asset);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve asset: " + e.getMessage()));
        }
    }

    /**
     * Delete asset
     * DELETE /api/image-editor/assets/{id}
     */
    @DeleteMapping("/assets/{id}")
    public ResponseEntity<?> deleteAsset(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            imageAssetService.deleteAsset(user, id);
            return ResponseEntity.ok(Map.of("message", "Asset deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to delete asset: " + e.getMessage()));
        }
    }

    @GetMapping("/elements")
    public ResponseEntity<?> getElements(
            @RequestParam(value = "category", required = false) String category) {
        try {
            List<ImageElement> elements;
            if (category != null) {
                elements = imageElementService.getElementsByCategory(category);
            } else {
                elements = imageElementService.getAllActiveElements();
            }
            return ResponseEntity.ok(elements);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve elements: " + e.getMessage()));
        }
    }

    /**
     * Remove background from an asset
     * POST /api/image-editor/assets/{id}/remove-background
     */
    @PostMapping("/assets/{id}/remove-background")
    public ResponseEntity<?> removeBackground(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageAsset newAsset = imageAssetService.removeBackground(user, id);
            return ResponseEntity.ok(newAsset);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Background removal failed: " + e.getMessage()));
        }
    }
    /**
     * Apply template to project
     * POST /api/image-editor/projects/{projectId}/apply-template/{templateId}
     */
    @PostMapping("/projects/{projectId}/apply-template/{templateId}")
    public ResponseEntity<?> applyTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable Long templateId) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.applyTemplateToProject(user, projectId, templateId);
            return ResponseEntity.ok(project);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to apply template: " + e.getMessage()));
        }
    }

    /**
     * Download element in specified format
     * GET /api/image-editor/elements/{id}/download
     */
    @GetMapping("/elements/{id}/download")
    public ResponseEntity<Resource> downloadElement(
            @PathVariable Long id,
            @RequestParam String format,
            @RequestParam(required = false) String resolution,
            @RequestHeader(value = "Authorization", required = false) String token,
            HttpServletRequest request) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                try {
                    user = imageEditorService.getUserFromToken(token);
                } catch (Exception e) {
                    // User not logged in, continue as anonymous
                }
            }

            String ipAddress = request.getRemoteAddr();

            ElementDownloadService.DownloadResult result =
                    elementDownloadService.downloadElement(id, format, resolution, user, ipAddress);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getFilename() + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(result.getContentType()))
                    .body(result.getResource());

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get download statistics for element
     * GET /api/image-editor/elements/{id}/stats
     */
    @GetMapping("/elements/{id}/stats")
    public ResponseEntity<?> getElementStats(@PathVariable Long id) {
        try {
            Long downloadCount = elementDownloadService.getDownloadCount(id);
            return ResponseEntity.ok(Map.of("downloads", downloadCount));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve stats"));
        }
    }
}