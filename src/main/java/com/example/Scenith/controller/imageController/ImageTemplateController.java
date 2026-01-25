package com.example.Scenith.controller.imageController;

import com.example.Scenith.entity.imageentity.ImageTemplate;
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

    /**
     * Get all active templates
     * GET /api/image-editor/templates
     */
    @GetMapping
    public ResponseEntity<?> getAllTemplates(
//            @RequestHeader("Authorization") String token,
            @RequestParam(value = "category", required = false) String category) {
        try {
            List<ImageTemplate> templates;
            if (category != null) {
                templates = templateService.getTemplatesByCategory(category);
            } else {
                templates = templateService.getAllActiveTemplates();
            }
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to retrieve templates: " + e.getMessage()));
        }
    }

    /**
     * Get single template
     * GET /api/image-editor/templates/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplate(
//            @RequestHeader("Authorization") String token,
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