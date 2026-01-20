package com.example.Scenith.controller.admin;

import com.example.Scenith.entity.User;
import com.example.Scenith.service.admin.AdminCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/cleanup")
public class AdminCleanupController {

    private static final Logger logger = LoggerFactory.getLogger(AdminCleanupController.class);

    @Autowired
    private AdminCleanupService adminCleanupService;

    /**
     * Clear all data for a specific user (Admin only)
     * DELETE /api/admin/cleanup/user/{userId}
     */
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<?> clearUserData(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            // Verify admin access
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                logger.warn("Non-admin user {} attempted to access cleanup endpoint", adminUser.getId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            logger.info("Admin {} initiating cleanup for user {}", adminUser.getId(), userId);

            // Perform cleanup
            Map<String, Object> result = adminCleanupService.clearAllUserData(userId);

            logger.info("Cleanup completed for user {}: {}", userId, result);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up all data for user " + userId,
                    result
            ));

        } catch (IllegalArgumentException e) {
            logger.error("Invalid request for cleanup: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error during cleanup for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup user data: " + e.getMessage()));
        }
    }

    /**
     * Clear only Video Speed data for a user (Admin only)
     * DELETE /api/admin/cleanup/user/{userId}/video-speed
     */
    @DeleteMapping("/user/{userId}/video-speed")
    public ResponseEntity<?> clearVideoSpeedData(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            User user = adminCleanupService.getUserFromToken("Bearer " + userId);
            Map<String, Object> result = adminCleanupService.clearVideoSpeedData(user);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up video speed data for user " + userId,
                    result
            ));

        } catch (Exception e) {
            logger.error("Error during video speed cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup video speed data: " + e.getMessage()));
        }
    }

    /**
     * Clear only Subtitle data for a user (Admin only)
     * DELETE /api/admin/cleanup/user/{userId}/subtitle
     */
    @DeleteMapping("/user/{userId}/subtitle")
    public ResponseEntity<?> clearSubtitleData(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            User targetUser = adminCleanupService.getUserFromToken("Bearer " + userId);
            Map<String, Object> result = adminCleanupService.clearSubtitleData(targetUser);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up subtitle data for user " + userId,
                    result
            ));

        } catch (Exception e) {
            logger.error("Error during subtitle cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup subtitle data: " + e.getMessage()));
        }
    }

    /**
     * Clear only Compression data for a user (Admin only)
     * DELETE /api/admin/cleanup/user/{userId}/compression
     */
    @DeleteMapping("/user/{userId}/compression")
    public ResponseEntity<?> clearCompressionData(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            User targetUser = adminCleanupService.getUserFromToken("Bearer " + userId);
            Map<String, Object> result = adminCleanupService.clearCompressionData(targetUser);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up compression data for user " + userId,
                    result
            ));

        } catch (Exception e) {
            logger.error("Error during compression cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup compression data: " + e.getMessage()));
        }
    }

    /**
     * Clear only Projects data for a user (Admin only)
     * DELETE /api/admin/cleanup/user/{userId}/projects
     */
    @DeleteMapping("/user/{userId}/projects")
    public ResponseEntity<?> clearProjectsData(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            User targetUser = adminCleanupService.getUserFromToken("Bearer " + userId);
            Map<String, Object> result = adminCleanupService.clearProjectsData(targetUser);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up projects data for user " + userId,
                    result
            ));

        } catch (Exception e) {
            logger.error("Error during projects cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup projects data: " + e.getMessage()));
        }
    }

    /**
     * Get cleanup statistics for a user without deleting (Admin only)
     * GET /api/admin/cleanup/user/{userId}/stats
     */
    @GetMapping("/user/{userId}/stats")
    public ResponseEntity<?> getCleanupStats(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId) {
        
        try {
            // Verify admin access
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                logger.warn("Non-admin user {} attempted to access cleanup stats", adminUser.getId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            Map<String, Object> stats = adminCleanupService.getCleanupStats(userId);

            return ResponseEntity.ok(createSuccessResponse(
                    "Cleanup statistics for user " + userId,
                    stats
            ));

        } catch (IllegalArgumentException e) {
            logger.error("Invalid request for stats: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error getting cleanup stats for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to get cleanup stats: " + e.getMessage()));
        }
    }

    private User getUserFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid authorization header");
        }
        return adminCleanupService.getUserFromToken(authHeader);
    }

    private Map<String, Object> createSuccessResponse(String message, Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", message);
        return response;
    }
}