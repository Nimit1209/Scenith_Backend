package com.example.Scenith.controller;

import com.example.Scenith.dto.imagedto.CreateImageProjectRequest;
import com.example.Scenith.dto.imagedto.ExportImageRequest;
import com.example.Scenith.dto.imagedto.UpdateImageProjectRequest;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageAsset;
import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.entity.imageentity.ImageProject;
import com.example.Scenith.service.ImageAssetService;
import com.example.Scenith.service.ImageEditorService;
import com.example.Scenith.service.ImageElementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
            @RequestHeader("Authorization") String token,
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
}