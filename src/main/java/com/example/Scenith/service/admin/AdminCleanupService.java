package com.example.Scenith.service.admin;

import com.example.Scenith.entity.*;
import com.example.Scenith.repository.*;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.CloudflareR2Service;
import com.example.Scenith.service.VideoEditingService;
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
public class AdminCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(AdminCleanupService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VideoSpeedRepository videoSpeedRepository;

    @Autowired
    private SubtitleMediaRepository subtitleMediaRepository;

    @Autowired
    private CompressedMediaRepository compressedMediaRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CloudflareR2Service cloudflareR2Service;

    @Autowired
    private VideoEditingService videoEditingService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Clear all data for a specific user across all services
     */
    @Transactional
    public Map<String, Object> clearAllUserData(Long userId) {
        logger.info("Starting complete cleanup for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("userEmail", user.getEmail());

        // Clear Video Speed data
        Map<String, Object> videoSpeedResult = clearVideoSpeedData(user);
        result.put("videoSpeed", videoSpeedResult);

        // Clear Subtitle data
        Map<String, Object> subtitleResult = clearSubtitleData(user);
        result.put("subtitle", subtitleResult);

        // Clear Compression data
        Map<String, Object> compressionResult = clearCompressionData(user);
        result.put("compression", compressionResult);

        // Clear Video Editing Projects data
        Map<String, Object> projectsResult = clearProjectsData(user);
        result.put("projects", projectsResult);

        // Calculate totals
        int totalFilesDeleted = (int) videoSpeedResult.get("filesDeleted") +
                (int) subtitleResult.get("filesDeleted") +
                (int) compressionResult.get("filesDeleted") +
                (int) projectsResult.get("filesDeleted");

        int totalFilesFailed = (int) videoSpeedResult.get("filesFailed") +
                (int) subtitleResult.get("filesFailed") +
                (int) compressionResult.get("filesFailed") +
                (int) projectsResult.get("filesFailed");

        int totalRecordsDeleted = (int) videoSpeedResult.get("recordsDeleted") +
                (int) subtitleResult.get("recordsDeleted") +
                (int) compressionResult.get("recordsDeleted") +
                (int) projectsResult.get("recordsDeleted");

        result.put("totalFilesDeleted", totalFilesDeleted);
        result.put("totalFilesFailed", totalFilesFailed);
        result.put("totalRecordsDeleted", totalRecordsDeleted);

        logger.info("Cleanup completed for user {}: {}", userId, result);
        return result;
    }

    /**
     * Clear Video Speed data for a user
     */
    @Transactional
    public Map<String, Object> clearVideoSpeedData(User user) {
        logger.info("Clearing video speed data for user: {}", user.getId());

        Map<String, Object> result = new HashMap<>();
        int filesDeleted = 0;
        int filesFailed = 0;
        int recordsDeleted = 0;

        try {
            List<VideoSpeed> videos = videoSpeedRepository.findByUser(user);
            logger.info("Found {} video speed records for user {}", videos.size(), user.getId());

            // Delete files from R2
            for (VideoSpeed video : videos) {
                try {
                    if (video.getOriginalFilePath() != null && !video.getOriginalFilePath().isEmpty()) {
                        cloudflareR2Service.deleteFile(video.getOriginalFilePath());
                        filesDeleted++;
                        logger.debug("Deleted original file: {}", video.getOriginalFilePath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete original file: {}", video.getOriginalFilePath(), e);
                }

                try {
                    if (video.getOutputFilePath() != null && !video.getOutputFilePath().isEmpty()) {
                        cloudflareR2Service.deleteFile(video.getOutputFilePath());
                        filesDeleted++;
                        logger.debug("Deleted output file: {}", video.getOutputFilePath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete output file: {}", video.getOutputFilePath(), e);
                }
            }

            // Delete database records
            recordsDeleted = videos.size();
            videoSpeedRepository.deleteAll(videos);
            logger.info("Deleted {} video speed records", recordsDeleted);

            // Delete user's video speed directory
            try {
                String userVideoSpeedPrefix = "speed-videos/" + user.getId() + "/";
                cloudflareR2Service.deleteDirectory(userVideoSpeedPrefix);
                logger.info("Deleted video speed directory: {}", userVideoSpeedPrefix);
            } catch (IOException e) {
                logger.warn("Failed to delete video speed directory: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Error during video speed cleanup for user {}: {}", user.getId(), e.getMessage(), e);
        }

        result.put("filesDeleted", filesDeleted);
        result.put("filesFailed", filesFailed);
        result.put("recordsDeleted", recordsDeleted);
        return result;
    }

    /**
     * Clear Subtitle data for a user
     */
    @Transactional
    public Map<String, Object> clearSubtitleData(User user) {
        logger.info("Clearing subtitle data for user: {}", user.getId());

        Map<String, Object> result = new HashMap<>();
        int filesDeleted = 0;
        int filesFailed = 0;
        int recordsDeleted = 0;

        try {
            List<SubtitleMedia> subtitleMediaList = subtitleMediaRepository.findByUser(user);
            logger.info("Found {} subtitle media records for user {}", subtitleMediaList.size(), user.getId());

            // Delete files from R2
            for (SubtitleMedia media : subtitleMediaList) {
                try {
                    if (media.getOriginalPath() != null && !media.getOriginalPath().isEmpty()) {
                        cloudflareR2Service.deleteFile(media.getOriginalPath());
                        filesDeleted++;
                        logger.debug("Deleted original subtitle file: {}", media.getOriginalPath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete original subtitle file: {}", media.getOriginalPath(), e);
                }

                try {
                    if (media.getProcessedPath() != null && !media.getProcessedPath().isEmpty()) {
                        cloudflareR2Service.deleteFile(media.getProcessedPath());
                        filesDeleted++;
                        logger.debug("Deleted processed subtitle file: {}", media.getProcessedPath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete processed subtitle file: {}", media.getProcessedPath(), e);
                }
            }

            // Delete database records
            recordsDeleted = subtitleMediaList.size();
            subtitleMediaRepository.deleteAll(subtitleMediaList);
            logger.info("Deleted {} subtitle records", recordsDeleted);

            // Delete user's subtitle directory
            try {
                String userSubtitlePrefix = "subtitles/" + user.getId() + "/";
                cloudflareR2Service.deleteDirectory(userSubtitlePrefix);
                logger.info("Deleted subtitle directory: {}", userSubtitlePrefix);
            } catch (IOException e) {
                logger.warn("Failed to delete subtitle directory: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Error during subtitle cleanup for user {}: {}", user.getId(), e.getMessage(), e);
        }

        result.put("filesDeleted", filesDeleted);
        result.put("filesFailed", filesFailed);
        result.put("recordsDeleted", recordsDeleted);
        return result;
    }

    /**
     * Clear Compression data for a user
     */
    @Transactional
    public Map<String, Object> clearCompressionData(User user) {
        logger.info("Clearing compression data for user: {}", user.getId());

        Map<String, Object> result = new HashMap<>();
        int filesDeleted = 0;
        int filesFailed = 0;
        int recordsDeleted = 0;

        try {
            List<CompressedMedia> compressedMediaList = compressedMediaRepository.findByUser(user);
            logger.info("Found {} compressed media records for user {}", compressedMediaList.size(), user.getId());

            // Delete files from R2
            for (CompressedMedia media : compressedMediaList) {
                try {
                    if (media.getOriginalPath() != null && !media.getOriginalPath().isEmpty()) {
                        cloudflareR2Service.deleteFile(media.getOriginalPath());
                        filesDeleted++;
                        logger.debug("Deleted original compressed file: {}", media.getOriginalPath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete original compressed file: {}", media.getOriginalPath(), e);
                }

                try {
                    if (media.getProcessedPath() != null && !media.getProcessedPath().isEmpty()) {
                        cloudflareR2Service.deleteFile(media.getProcessedPath());
                        filesDeleted++;
                        logger.debug("Deleted processed compressed file: {}", media.getProcessedPath());
                    }
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete processed compressed file: {}", media.getProcessedPath(), e);
                }
            }

            // Delete database records
            recordsDeleted = compressedMediaList.size();
            compressedMediaRepository.deleteAll(compressedMediaList);
            logger.info("Deleted {} compression records", recordsDeleted);

            // Delete user's compression directories
            try {
                String uploadedPrefix = "Compression/uploaded/" + user.getId() + "/";
                cloudflareR2Service.deleteDirectory(uploadedPrefix);
                logger.info("Deleted compression uploaded directory: {}", uploadedPrefix);
            } catch (IOException e) {
                logger.warn("Failed to delete compression uploaded directory: {}", e.getMessage());
            }

            try {
                String processedPrefix = "Compression/processed/" + user.getId() + "/";
                cloudflareR2Service.deleteDirectory(processedPrefix);
                logger.info("Deleted compression processed directory: {}", processedPrefix);
            } catch (IOException e) {
                logger.warn("Failed to delete compression processed directory: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Error during compression cleanup for user {}: {}", user.getId(), e.getMessage(), e);
        }

        result.put("filesDeleted", filesDeleted);
        result.put("filesFailed", filesFailed);
        result.put("recordsDeleted", recordsDeleted);
        return result;
    }

    /**
     * Clear Video Editing Projects data for a user
     */
    @Transactional
    public Map<String, Object> clearProjectsData(User user) {
        logger.info("Clearing projects data for user: {}", user.getId());

        Map<String, Object> result = new HashMap<>();
        int filesDeleted = 0;
        int filesFailed = 0;
        int recordsDeleted = 0;

        try {
            List<Project> projects = projectRepository.findByUser(user);
            logger.info("Found {} projects for user {}", projects.size(), user.getId());

            // Delete files from R2 for each project
            for (Project project : projects) {
                try {
                    videoEditingService.deleteProjectFiles(project.getId());
                    filesDeleted++; // Increment for successful project deletion
                    logger.debug("Deleted files for project: {}", project.getId());
                } catch (IOException e) {
                    filesFailed++;
                    logger.error("Failed to delete files for project {}: {}", project.getId(), e.getMessage(), e);
                }
            }

            // Delete database records
            recordsDeleted = projects.size();
            projectRepository.deleteAll(projects);
            logger.info("Deleted {} project records", recordsDeleted);

        } catch (Exception e) {
            logger.error("Error during projects cleanup for user {}: {}", user.getId(), e.getMessage(), e);
        }

        result.put("filesDeleted", filesDeleted);
        result.put("filesFailed", filesFailed);
        result.put("recordsDeleted", recordsDeleted);
        return result;
    }

    /**
     * Get cleanup statistics without deleting
     */
    public Map<String, Object> getCleanupStats(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        Map<String, Object> stats = new HashMap<>();
        stats.put("userId", userId);
        stats.put("userEmail", user.getEmail());

        // Video Speed stats
        int videoSpeedCount = videoSpeedRepository.findByUser(user).size();
        stats.put("videoSpeedRecords", videoSpeedCount);

        // Subtitle stats
        int subtitleCount = subtitleMediaRepository.findByUser(user).size();
        stats.put("subtitleRecords", subtitleCount);

        // Compression stats
        int compressionCount = compressedMediaRepository.findByUser(user).size();
        stats.put("compressionRecords", compressionCount);

        // Projects stats
        int projectsCount = projectRepository.findByUser(user).size();
        stats.put("projectsRecords", projectsCount);

        // Total
        int totalRecords = videoSpeedCount + subtitleCount + compressionCount + projectsCount;
        stats.put("totalRecords", totalRecords);

        return stats;
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