package com.example.Scenith.controller.imageController;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageTemplate;
import com.example.Scenith.service.imageService.ImageEditorService;
import com.example.Scenith.service.imageService.ImageTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/image-editor/templates")
public class ImageTemplateController {

    private final ImageTemplateService templateService;
    private final ImageEditorService editorService;

    /**
     * Get all active templates
     * GET /api/image-editor/templates
     */
    @GetMapping
    public ResponseEntity<?> getAllTemplates(@RequestHeader("Authorization") String token) {
        try {
            User user = editorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }

            List<ImageTemplate> templates = templateService.getAllTemplates();
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve templates: " + e.getMessage()));
        }
    }

    /**
     * Get single template by ID (Admin)
     * GET /api/admin/image-templates/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplateById(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = editorService.getUserFromToken(token);

            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }

            ImageTemplate template = templateService.getTemplateById(id);
            return ResponseEntity.ok(template);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to retrieve template: " + e.getMessage()));
        }
    }

    /**
     * Publish template
     * POST /api/admin/image-templates/{id}/publish
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publishTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = editorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }

            ImageTemplate template = templateService.publishTemplate(id);
            return ResponseEntity.ok(Map.of(
                    "message", "Template published successfully",
                    "template", template
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to publish template: " + e.getMessage()));
        }
    }

    /**
     * Unpublish template (back to draft)
     * POST /api/admin/image-templates/{id}/unpublish
     */
    @PostMapping("/{id}/unpublish")
    public ResponseEntity<?> unpublishTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = editorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }

            ImageTemplate template = templateService.unpublishTemplate(id);
            return ResponseEntity.ok(Map.of(
                    "message", "Template unpublished successfully",
                    "template", template
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to unpublish template: " + e.getMessage()));
        }
    }

    /**
     * Get single template
     * GET /api/image-editor/templates/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            ImageTemplate template = templateService.getTemplateById(id);
            
            // Increment usage count
            templateService.incrementUsageCount(id);
            
            return ResponseEntity.ok(template);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to retrieve template: " + e.getMessage()));
        }
    }
}