package com.example.Scenith.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    public VideoExportWorker(SqsService sqsService, VideoEditingService videoEditingService, ObjectMapper objectMapper) {
        this.sqsService = sqsService;
        this.videoEditingService = videoEditingService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 10000) // Poll every second
    public void processQueue() {
        try {
            List<Message> messages = sqsService.receiveMessages(videoExportQueueUrl, 1);
            for (Message message : messages) {
                try {
                    Map<String, String> taskDetails = objectMapper.readValue(message.body(), new TypeReference<>() {});
                    String sessionId = taskDetails.get("sessionId");
                    logger.info("Processing export task: sessionId={}", sessionId);
                    videoEditingService.processExportTask(taskDetails);
                    sqsService.deleteMessage(message.receiptHandle(), videoExportQueueUrl);
                    logger.info("Successfully processed and deleted message: messageId={}", message.messageId());
                } catch (Exception e) {
                    logger.error("Failed to process message: messageId={}, error={}", message.messageId(), e.getMessage(), e);
                    // Message will go to DLQ after max receives
                }
            }
        } catch (Exception e) {
            logger.error("Error polling queue {}: {}", videoExportQueueUrl, e.getMessage(), e);
        }
    }
}