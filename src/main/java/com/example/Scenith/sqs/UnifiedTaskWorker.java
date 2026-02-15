package com.example.Scenith.sqs;

import com.example.Scenith.service.*;
import com.example.Scenith.service.imageService.ImageEditorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class UnifiedTaskWorker {
    private static final Logger logger = LoggerFactory.getLogger(UnifiedTaskWorker.class);
    private static final int MAX_RETRIES = 3;

    private final SqsService sqsService;
    private final VideoEditingService videoEditingService;
    private final SubtitleService subtitleService;
    private final VideoFilterJobService videoFilterJobService;
    private final VideoSpeedService videoSpeedService;
    private final ImageEditorService imageEditorService;
    private final PodcastClipService podcastClipService;
    private final AspectRatioService aspectRatioService;
    private final ObjectMapper objectMapper;
    private final GlobalProcessingLock processingLock; // ← NEW

    @Value("${sqs.queue.url}")
    private String queueUrl;

    /**
     * Polls SQS queue every 2 seconds and processes messages
     * Fixed delay ensures one poll completes before next starts
     */
    @Scheduled(fixedDelay = 2000)
    public void processQueue() {
        try {
            // Receive up to 5 messages at once for better throughput
            List<Message> messages = sqsService.receiveMessages(queueUrl, 5);

            if (messages.isEmpty()) {
                logger.debug("No messages in queue");
                return;
            }

            logger.info("Received {} messages from queue", messages.size());

            for (Message message : messages) {
                processMessage(message);
            }
        } catch (Exception e) {
            logger.error("Error polling queue: {}", e.getMessage(), e);
        }
    }

    /**
     * Process individual message and route to appropriate service
     */
    private void processMessage(Message message) {
        String taskType = "UNKNOWN";
        String taskId = message.messageId();

        try {
            // Check retry count to prevent infinite loops
            String receiveCountStr = message.attributesAsStrings()
                    .getOrDefault("ApproximateReceiveCount", "1");
            int receiveCount = Integer.parseInt(receiveCountStr);

            if (receiveCount > MAX_RETRIES) {
                logger.error("Message {} exceeded max retries ({}), deleting",
                        taskId, MAX_RETRIES);
                sqsService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }

            // Parse message body
            Map<String, Object> taskDetails = objectMapper.readValue(
                    message.body(),
                    new TypeReference<Map<String, Object>>() {}
            );

            taskType = (String) taskDetails.get("taskType");

            if (taskType == null || taskType.isEmpty()) {
                logger.error("Message missing taskType, deleting: messageId={}", taskId);
                sqsService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }

            logger.info("Processing task: type={}, messageId={}, attempt={}",
                    taskType, taskId, receiveCount);

            // ==================== CRITICAL CHANGE ====================
            // Acquire global processing lock BEFORE executing heavy task
            // This ensures only ONE heavy job runs at a time
            // =========================================================

            boolean lockAcquired = processingLock.acquireLock(taskType, taskId);

            if (!lockAcquired) {
                logger.error("Failed to acquire processing lock for task: type={}, messageId={}",
                        taskType, taskId);
                // Don't delete - let it retry
                return;
            }

            try {
                // Route to appropriate service based on task type
                boolean processed = routeTask(taskType, taskDetails);

                if (processed) {
                    // Delete message only after successful processing
                    sqsService.deleteMessage(message.receiptHandle(), queueUrl);
                    logger.info("✓ Successfully completed task: type={}, messageId={}",
                            taskType, taskId);
                } else {
                    logger.warn("Task type not recognized: {}", taskType);
                    sqsService.deleteMessage(message.receiptHandle(), queueUrl);
                }
            } finally {
                // ALWAYS release lock, even if processing failed
                processingLock.releaseLock(taskType, taskId);
            }

        } catch (Exception e) {
            logger.error("✗ Failed to process message: type={}, messageId={}, error={}",
                    taskType, taskId, e.getMessage(), e);
            // Message will be retried (visibility timeout expires and it becomes visible again)
            // After MAX_RETRIES, it will be deleted or sent to DLQ if configured
        }
    }

    /**
     * Route task to appropriate service based on taskType
     * @return true if task was recognized and processed, false otherwise
     */
    private boolean routeTask(String taskType, Map<String, Object> taskDetails) {
        try {
            switch (taskType) {
                case "VIDEO_EXPORT":
                    handleVideoExport(taskDetails);
                    return true;

                case "PROCESS_SUBTITLES":
                    handleProcessSubtitles(taskDetails);
                    return true;

                case "VIDEO_FILTER":
                    handleVideoFilter(taskDetails);
                    return true;

                case "VIDEO_SPEED":
                    handleVideoSpeed(taskDetails);
                    return true;

                case "PODCAST_CLIP":
                    handlePodcastClip(taskDetails);
                    return true;

                case "ASPECT_RATIO":
                    handleAspectRatio(taskDetails);
                    return true;

                default:
                    return false;
            }
        } catch (Exception e) {
            // Re-throw to be caught by processMessage for proper error handling
            throw new RuntimeException("Failed to process " + taskType + " task", e);
        }
    }

    // ==================== Task Handlers ====================

    private void handleVideoExport(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        Map<String, String> stringMap = convertToStringMap(taskDetails);
        videoEditingService.processExportTask(stringMap);
    }

    private void handleProcessSubtitles(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        Long mediaId = getLongValue(taskDetails, "mediaId");
        Long userId = getLongValue(taskDetails, "userId");
        String quality = getStringValue(taskDetails, "quality");

        subtitleService.processSubtitlesTask(mediaId, userId, quality);
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        return value.toString();
    }

    private void handleVideoFilter(Map<String, Object> taskDetails) {
        Map<String, String> stringMap = convertToStringMap(taskDetails);
        videoFilterJobService.processJobFromSqs(stringMap);
    }

    private void handleVideoSpeed(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        Map<String, String> stringMap = convertToStringMap(taskDetails);
        videoSpeedService.processSpeedTask(stringMap);
    }

    private void handlePodcastClip(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        Map<String, String> stringMap = convertToStringMap(taskDetails);
        podcastClipService.processClipsTask(stringMap);
    }

    private void handleAspectRatio(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        aspectRatioService.processAspectRatioTask(taskDetails);
    }

    // ==================== Helper Methods ====================

    /**
     * Convert Map<String, Object> to Map<String, String>
     * Required for services that expect String values
     */
    private Map<String, String> convertToStringMap(Map<String, Object> source) {
        return source.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> String.valueOf(e.getValue())
                ));
    }

    /**
     * Safely extract Long value from map
     */
    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}