package com.example.Scenith.service.admin;

import com.example.Scenith.entity.DocumentConversion;
import com.example.Scenith.entity.DocumentUpload;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.DocumentConversionRepository;
import com.example.Scenith.repository.DocumentUploadRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.CloudflareR2Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminDocumentCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(AdminDocumentCleanupService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentUploadRepository documentUploadRepository;

    @Autowired
    private DocumentConversionRepository documentConversionRepository;

    @Autowired
    private CloudflareR2Service cloudflareR2Service;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Clear all document-related data for a specific user
     * This includes:
     * - All uploaded documents from R2 storage
     * - All conversion outputs from R2 storage
     * - All DocumentUpload database records
     * - All DocumentConversion database records
     */
    @Transactional
    public Map<String, Object> clearUserDocumentData(Long userId) {
        logger.info("Starting cleanup for user: {}", userId);

        // Verify user exists
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        Map<String, Object> result = new HashMap<>();
        int uploadFilesDeleted = 0;
        int uploadRecordsDeleted = 0;
        int conversionFilesDeleted = 0;
        int conversionRecordsDeleted = 0;
        int uploadFilesFailed = 0;
        int conversionFilesFailed = 0;

        try {
            // 1. Get all document uploads for this user
            List<DocumentUpload> uploads = documentUploadRepository.findByUserOrderByCreatedAtDesc(user);
            logger.info("Found {} document uploads for user {}", uploads.size(), userId);

            // 2. Delete upload files from R2
            for (DocumentUpload upload : uploads) {
                try {
                    if (upload.getFilePath() != null && !upload.getFilePath().isEmpty()) {
                        cloudflareR2Service.deleteFile(upload.getFilePath());
                        uploadFilesDeleted++;
                        logger.debug("Deleted upload file from R2: {}", upload.getFilePath());
                    }
                } catch (IOException e) {
                    uploadFilesFailed++;
                    logger.error("Failed to delete upload file from R2: {}, error: {}",
                            upload.getFilePath(), e.getMessage());
                }
            }

            // 3. Delete upload records from database
            uploadRecordsDeleted = uploads.size();
            documentUploadRepository.deleteAll(uploads);
            logger.info("Deleted {} upload records from database", uploadRecordsDeleted);

            // 4. Get all document conversions for this user
            List<DocumentConversion> conversions = documentConversionRepository.findByUserOrderByCreatedAtDesc(user);
            logger.info("Found {} document conversions for user {}", conversions.size(), userId);

            // 5. Delete conversion output files from R2
            for (DocumentConversion conversion : conversions) {
                try {
                    if (conversion.getOutputPath() != null && !conversion.getOutputPath().isEmpty()) {
                        cloudflareR2Service.deleteFile(conversion.getOutputPath());
                        conversionFilesDeleted++;
                        logger.debug("Deleted conversion file from R2: {}", conversion.getOutputPath());
                    }
                } catch (IOException e) {
                    conversionFilesFailed++;
                    logger.error("Failed to delete conversion file from R2: {}, error: {}",
                            conversion.getOutputPath(), e.getMessage());
                }
            }

            // 6. Delete conversion records from database
            conversionRecordsDeleted = conversions.size();
            documentConversionRepository.deleteAll(conversions);
            logger.info("Deleted {} conversion records from database", conversionRecordsDeleted);

            // 7. Try to delete the user's document directory from R2 (cleanup any remaining files)
            try {
                String userDocumentPrefix = "documents/" + userId + "/";
                cloudflareR2Service.deleteDirectory(userDocumentPrefix);
                logger.info("Deleted user document directory from R2: {}", userDocumentPrefix);
            } catch (IOException e) {
                logger.warn("Failed to delete user document directory, may not exist: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Error during cleanup for user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Cleanup partially completed with errors: " + e.getMessage(), e);
        }

        // Build result
        result.put("userId", userId);
        result.put("userEmail", user.getEmail());
        result.put("uploadFilesDeleted", uploadFilesDeleted);
        result.put("uploadFilesFailed", uploadFilesFailed);
        result.put("uploadRecordsDeleted", uploadRecordsDeleted);
        result.put("conversionFilesDeleted", conversionFilesDeleted);
        result.put("conversionFilesFailed", conversionFilesFailed);
        result.put("conversionRecordsDeleted", conversionRecordsDeleted);
        result.put("totalFilesDeleted", uploadFilesDeleted + conversionFilesDeleted);
        result.put("totalFilesFailed", uploadFilesFailed + conversionFilesFailed);
        result.put("totalRecordsDeleted", uploadRecordsDeleted + conversionRecordsDeleted);

        logger.info("Cleanup completed for user {}: {}", userId, result);
        return result;
    }

    /**
     * Get cleanup statistics without actually deleting anything
     */
    public Map<String, Object> getCleanupStats(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        List<DocumentUpload> uploads = documentUploadRepository.findByUserOrderByCreatedAtDesc(user);
        List<DocumentConversion> conversions = documentConversionRepository.findByUserOrderByCreatedAtDesc(user);

        long totalUploadSize = uploads.stream()
                .filter(u -> u.getFileSizeBytes() != null)
                .mapToLong(DocumentUpload::getFileSizeBytes)
                .sum();

        long totalConversionSize = conversions.stream()
                .filter(c -> c.getFileSizeBytes() != null)
                .mapToLong(DocumentConversion::getFileSizeBytes)
                .sum();

        Map<String, Object> stats = new HashMap<>();
        stats.put("userId", userId);
        stats.put("userEmail", user.getEmail());
        stats.put("uploadCount", uploads.size());
        stats.put("conversionCount", conversions.size());
        stats.put("totalUploadSizeBytes", totalUploadSize);
        stats.put("totalUploadSizeMB", String.format("%.2f", totalUploadSize / (1024.0 * 1024.0)));
        stats.put("totalConversionSizeBytes", totalConversionSize);
        stats.put("totalConversionSizeMB", String.format("%.2f", totalConversionSize / (1024.0 * 1024.0)));
        stats.put("totalSizeBytes", totalUploadSize + totalConversionSize);
        stats.put("totalSizeMB", String.format("%.2f", (totalUploadSize + totalConversionSize) / (1024.0 * 1024.0)));
        stats.put("totalRecords", uploads.size() + conversions.size());

        return stats;
    }

    /**
     * Clear ALL document data for ALL users (use with extreme caution!)
     */
    @Transactional
    public Map<String, Object> clearAllDocumentData() {
        logger.warn("Starting FULL cleanup of all document data");

        List<User> allUsers = userRepository.findAll();
        Map<String, Object> overallResult = new HashMap<>();

        int totalUploadFilesDeleted = 0;
        int totalUploadRecordsDeleted = 0;
        int totalConversionFilesDeleted = 0;
        int totalConversionRecordsDeleted = 0;
        int totalUploadFilesFailed = 0;
        int totalConversionFilesFailed = 0;
        int usersProcessed = 0;

        for (User user : allUsers) {
            try {
                Map<String, Object> userResult = clearUserDocumentData(user.getId());
                usersProcessed++;

                totalUploadFilesDeleted += (int) userResult.get("uploadFilesDeleted");
                totalUploadFilesFailed += (int) userResult.get("uploadFilesFailed");
                totalUploadRecordsDeleted += (int) userResult.get("uploadRecordsDeleted");
                totalConversionFilesDeleted += (int) userResult.get("conversionFilesDeleted");
                totalConversionFilesFailed += (int) userResult.get("conversionFilesFailed");
                totalConversionRecordsDeleted += (int) userResult.get("conversionRecordsDeleted");

            } catch (Exception e) {
                logger.error("Failed to cleanup user {}: {}", user.getId(), e.getMessage());
            }
        }

        // Try to clean up the entire documents directory
        try {
            cloudflareR2Service.deleteDirectory("documents/");
            logger.info("Deleted entire documents directory from R2");
        } catch (IOException e) {
            logger.warn("Failed to delete documents directory: {}", e.getMessage());
        }

        overallResult.put("usersProcessed", usersProcessed);
        overallResult.put("totalUsers", allUsers.size());
        overallResult.put("uploadFilesDeleted", totalUploadFilesDeleted);
        overallResult.put("uploadFilesFailed", totalUploadFilesFailed);
        overallResult.put("uploadRecordsDeleted", totalUploadRecordsDeleted);
        overallResult.put("conversionFilesDeleted", totalConversionFilesDeleted);
        overallResult.put("conversionFilesFailed", totalConversionFilesFailed);
        overallResult.put("conversionRecordsDeleted", totalConversionRecordsDeleted);
        overallResult.put("totalFilesDeleted", totalUploadFilesDeleted + totalConversionFilesDeleted);
        overallResult.put("totalFilesFailed", totalUploadFilesFailed + totalConversionFilesFailed);
        overallResult.put("totalRecordsDeleted", totalUploadRecordsDeleted + totalConversionRecordsDeleted);

        logger.warn("Full cleanup completed: {}", overallResult);
        return overallResult;
    }

    /**
     * Get user from JWT token
     */
    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}