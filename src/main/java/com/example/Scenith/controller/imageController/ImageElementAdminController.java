package com.example.Scenith.controller.imageController;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.service.imageService.ImageEditorService;
import com.example.Scenith.service.imageService.ImageElementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/image-elements")
public class ImageElementAdminController {

    @Autowired
    private ImageElementService imageElementService;
    @Autowired
    private ImageEditorService imageEditorService;

    /**
     * Upload new element
     * POST /api/admin/image-elements/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadElement(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "category", defaultValue = "general") String category,
            @RequestParam(value = "tags", required = false) String tags) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            if (files == null || files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "No files provided"));
            }

            List<ImageElement> savedElements = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;

                String originalName = file.getOriginalFilename();
                String elementName = originalName != null
                        ? originalName.replaceFirst("[.][^.]+$", "")  // remove extension
                        : "unnamed_" + UUID.randomUUID().toString().substring(0, 8);

                ImageElement element = imageElementService.uploadElement(
                        file,
                        elementName,           // ← original name without extension
                        category,
                        tags
                );
                savedElements.add(element);
            }
            if (savedElements.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "No valid files uploaded"));
            }
            return ResponseEntity.ok(savedElements);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }
    /**
     * Get all elements (including inactive)
     * GET /api/admin/image-elements
     */
    @GetMapping
    public ResponseEntity<?> getAllElements(@RequestHeader("Authorization") String token) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            // TODO: Add admin role check
            List<ImageElement> elements = imageElementService.getAllElements();
            return ResponseEntity.ok(elements);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to retrieve elements: " + e.getMessage()));
        }
    }

    /**
     * Update element
     * PUT /api/admin/image-elements/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateElement(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "displayOrder", required = false) Integer displayOrder) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            // TODO: Add admin role check
            ImageElement element = imageElementService.updateElement(id, name, category, tags, isActive, displayOrder);
            return ResponseEntity.ok(element);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Update failed: " + e.getMessage()));
        }
    }

    /**
     * Delete element
     * DELETE /api/admin/image-elements/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteElement(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = imageEditorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            // TODO: Add admin role check
            imageElementService.deleteElement(id);
            return ResponseEntity.ok(Map.of("message", "Element deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to delete element: " + e.getMessage()));
        }
    }
}