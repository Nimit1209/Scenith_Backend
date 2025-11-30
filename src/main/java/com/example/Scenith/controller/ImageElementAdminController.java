package com.example.Scenith.controller;

import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.service.ImageElementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/image-elements")
public class ImageElementAdminController {

    @Autowired
    private ImageElementService imageElementService;

    /**
     * Upload new element
     * POST /api/admin/image-elements/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadElement(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", defaultValue = "general") String category,
            @RequestParam(value = "tags", required = false) String tags) {
        try {
            // TODO: Add admin role check using token
            ImageElement element = imageElementService.uploadElement(file, name, category, tags);
            return ResponseEntity.ok(element);
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