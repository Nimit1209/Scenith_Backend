package com.example.Scenith.controller.admin;

import com.example.Scenith.entity.User;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.admin.AdminDocumentCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

    private static final Logger logger = LoggerFactory.getLogger(AdminDocumentController.class);

    @Autowired
    private AdminDocumentCleanupService adminCleanupService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Clear all document-related data for a specific user (Admin only)
     * DELETE /api/admin/documents/cleanup/{userId}
     */
    @DeleteMapping("/cleanup/{userId}")
    public ResponseEntity<?> clearUserDocumentData(
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
            Map<String, Object> result = adminCleanupService.clearUserDocumentData(userId);

            logger.info("Cleanup completed for user {}: {}", userId, result);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up document data for user " + userId,
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
     * Get cleanup statistics for a user without deleting (Admin only)
     * GET /api/admin/documents/cleanup-stats/{userId}
     */
    @GetMapping("/cleanup-stats/{userId}")
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

    /**
     * Clear all document data for ALL users (Super Admin - use with extreme caution)
     * DELETE /api/admin/documents/cleanup-all
     */
    @DeleteMapping("/cleanup-all")
    public ResponseEntity<?> clearAllDocumentData(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = true) String confirmToken) {

        try {
            // Verify admin access
            User adminUser = getUserFromToken(authHeader);
            if (!adminUser.isAdmin()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(createErrorResponse("Access denied. Admin privileges required."));
            }

            // Require confirmation token to prevent accidental deletion
            if (!"CONFIRM_DELETE_ALL_DOCUMENTS".equals(confirmToken)) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Invalid confirmation token. Use confirmToken=CONFIRM_DELETE_ALL_DOCUMENTS"));
            }

            logger.warn("Admin {} initiating FULL cleanup of all document data", adminUser.getId());

            Map<String, Object> result = adminCleanupService.clearAllDocumentData();

            logger.warn("Full cleanup completed by admin {}: {}", adminUser.getId(), result);

            return ResponseEntity.ok(createSuccessResponse(
                    "Successfully cleaned up ALL document data",
                    result
            ));

        } catch (Exception e) {
            logger.error("Error during full cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to cleanup all data: " + e.getMessage()));
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