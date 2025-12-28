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
@RequestMapping("/api/admin/image-templates")
public class ImageTemplateAdminController {

    private final ImageTemplateService templateService;
    private final ImageEditorService editorService;

    /**
     * Create new template
     * POST /api/admin/image-templates
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(
            @RequestHeader("Authorization") String token,
            @RequestParam("templateName") String templateName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", defaultValue = "general") String category,
            @RequestParam("canvasWidth") Integer canvasWidth,
            @RequestParam("canvasHeight") Integer canvasHeight,
            @RequestParam("designJson") String designJson,
            @RequestParam(value = "tags", required = false) String tags) {
        try {
            // TODO: Add admin role check using token
            User user = editorService.getUserFromToken(token);

            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            
            ImageTemplate template = templateService.createTemplate(
                user, templateName, description, category,
                canvasWidth, canvasHeight, designJson, tags
            );
            
            return ResponseEntity.ok(template);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to create template: " + e.getMessage()));
        }
    }

    /**
     * Get all templates (including inactive)
     * GET /api/admin/image-templates
     */
    @GetMapping
    public ResponseEntity<?> getAllTemplates(@RequestHeader("Authorization") String token) {
        try {
            // TODO: Add admin role check
            List<ImageTemplate> templates = templateService.getAllTemplates();
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to retrieve templates: " + e.getMessage()));
        }
    }

    /**
     * Update template
     * PUT /api/admin/image-templates/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestParam(value = "templateName", required = false) String templateName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "designJson", required = false) String designJson,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "isActive", required = false) Boolean isActive,
            @RequestParam(value = "isPremium", required = false) Boolean isPremium,
            @RequestParam(value = "displayOrder", required = false) Integer displayOrder) {
        try {
            User user = editorService.getUserFromToken(token);

            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }

            // TODO: Add admin role check
            ImageTemplate template = templateService.updateTemplate(
                id, templateName, description, category, designJson,
                tags, isActive, isPremium, displayOrder
            );
            return ResponseEntity.ok(template);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Update failed: " + e.getMessage()));
        }
    }

    /**
     * Delete template
     * DELETE /api/admin/image-templates/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTemplate(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = editorService.getUserFromToken(token);
            if (!user.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Access denied: Admin role required"));
            }
            // TODO: Add admin role check
            templateService.deleteTemplate(id);
            return ResponseEntity.ok(Map.of("message", "Template deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to delete template: " + e.getMessage()));
        }
    }
}