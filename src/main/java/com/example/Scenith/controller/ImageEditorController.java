package com.example.Scenith.controller;

import com.example.Scenith.dto.CreateImageProjectRequest;
import com.example.Scenith.dto.ExportImageRequest;
import com.example.Scenith.dto.UpdateImageProjectRequest;
import com.example.Scenith.entity.ImageAsset;
import com.example.Scenith.entity.ImageProject;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.ImageAssetService;
import com.example.Scenith.service.ImageEditorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/image-editor")
public class ImageEditorController {

    @Autowired
    private ImageEditorService imageEditorService;

    @Autowired
    private ImageAssetService imageAssetService;

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
     * Export project to image (async via SQS)
     * POST /api/image-editor/projects/{id}/export
     */
    @PostMapping("/projects/{id}/export")
    public ResponseEntity<?> exportProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestBody ExportImageRequest request) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            ImageProject project = imageEditorService.exportProject(user, id, request);
            return ResponseEntity.ok(Map.of(
                    "message", "Export job queued successfully",
                    "status", project.getStatus(),
                    "projectId", project.getId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Export failed: " + e.getMessage()));
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
}