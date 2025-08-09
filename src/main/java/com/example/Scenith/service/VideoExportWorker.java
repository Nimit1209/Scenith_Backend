package com.example.Scenith.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;

@Component
public class VideoExportWorker {
    private static final Logger logger = LoggerFactory.getLogger(VideoExportWorker.class);
    private final SqsService sqsService;
    private final VideoEditingService videoEditingService;
    private final ObjectMapper objectMapper;

    public VideoExportWorker(SqsService sqsService, VideoEditingService videoEditingService, ObjectMapper objectMapper) {
        this.sqsService = sqsService;
        this.videoEditingService = videoEditingService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000) // Poll every second
    public void processQueue() {
        try {
            List<Message> messages = sqsService.receiveMessages(1); // Process one message at a time
            for (Message message : messages) {
                try {
                    Map<String, String> taskDetails = objectMapper.readValue(message.body(), new TypeReference<Map<String, String>>() {});
                    logger.info("Processing SQS message: messageId={}, sessionId={}", message.messageId(), taskDetails.get("sessionId"));
                    videoEditingService.processExportTask(taskDetails);
                    sqsService.deleteMessage(message.receiptHandle());
                    logger.info("Successfully processed and deleted SQS message: messageId={}", message.messageId());
                } catch (Exception e) {
                    logger.error("Failed to process SQS message: messageId={}, error={}", message.messageId(), e.getMessage(), e);
                    // Message will automatically go to DLQ after configured retries
                }
            }
        } catch (Exception e) {
            logger.error("Error polling SQS queue: {}", e.getMessage(), e);
        }
    }
}