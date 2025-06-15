package com.example.Scenith.developer.controller;

import com.example.Scenith.developer.service.GlobalElementService;
import com.example.Scenith.dto.ElementDto;
import com.example.Scenith.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/developer")
public class DeveloperController {
    private final GlobalElementService globalElementService;
    private final JwtUtil jwtUtil;

    private static final Logger logger = LoggerFactory.getLogger(DeveloperController.class);

    public DeveloperController(GlobalElementService globalElementService, JwtUtil jwtUtil) {
        this.globalElementService = globalElementService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/elements/upload")
    public ResponseEntity<?> uploadGlobalElements(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "category", required = false) String category) {
        try {
            logger.info("Received upload request: files={}, title={}, type={}, category={}",
                    files.length, title, type, category);
            String username = jwtUtil.extractEmail(token.replace("Bearer ", ""));
            String role = jwtUtil.extractRole(token.replace("Bearer ", ""));
            logger.debug("Extracted username: {}, role: {}", username, role);

            if (!"DEVELOPER".equals(role)) {
                logger.warn("Access denied for username: {}, role: {}", username, role);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: Developer role required");
            }

            List<ElementDto> elements = globalElementService.uploadGlobalElements(files, title, type, category, username);
            logger.info("Successfully uploaded {} elements for username: {}", elements.size(), username);
            return ResponseEntity.ok(elements);
        } catch (IOException e) {
            logger.error("Error uploading global elements for username: {}: {}",  e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error uploading elements: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Bad request for username: {}: {}",  e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @GetMapping("/elements")
    public ResponseEntity<?> getGlobalElements(
            @RequestHeader("Authorization") String token) {
        try {
            String role = jwtUtil.extractRole(token.replace("Bearer ", ""));
            if (!"DEVELOPER".equals(role)) {
                logger.warn("Access denied for getGlobalElements, role: {}", role);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: Developer role required");
            }

            List<ElementDto> elements = globalElementService.getGlobalElements();
            logger.info("Retrieved {} global elements", elements.size());
            return ResponseEntity.ok(elements);
        } catch (RuntimeException e) {
            logger.error("Error retrieving global elements: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    // In DeveloperController.java
    @DeleteMapping("/elements/{elementId}")
    public ResponseEntity<?> deleteGlobalElement(
            @RequestHeader("Authorization") String token,
            @PathVariable Long elementId) {
        try {
            logger.info("Received delete request for elementId={}", elementId);
            String username = jwtUtil.extractEmail(token.replace("Bearer ", ""));
            String role = jwtUtil.extractRole(token.replace("Bearer ", ""));
            logger.debug("Extracted username: {}, role: {}", username, role);

            if (!"DEVELOPER".equals(role)) {
                logger.warn("Access denied for username: {}, role: {}", username, role);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: Developer role required");
            }

            globalElementService.deleteGlobalElement(elementId, username);
            logger.info("Successfully deleted elementId={} for username: {}", elementId, username);
            return ResponseEntity.ok().body("Element deleted successfully");
        } catch (IOException e) {
            logger.error("Error deleting elementId={} for username: {}: {}", elementId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting element: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Bad request for elementId={} for username: {}: {}", elementId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}