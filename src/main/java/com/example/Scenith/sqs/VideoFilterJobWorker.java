package com.example.Scenith.sqs;

import com.example.Scenith.service.VideoFilterJobService;
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
public class VideoFilterJobWorker {
    private static final Logger logger = LoggerFactory.getLogger(VideoFilterJobWorker.class);
    private final SqsService sqsService;
    private final VideoFilterJobService videoFilterJobService;
    private final ObjectMapper objectMapper;

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    public VideoFilterJobWorker(SqsService sqsService, VideoFilterJobService videoFilterJobService, ObjectMapper objectMapper) {
        this.sqsService = sqsService;
        this.videoFilterJobService = videoFilterJobService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000) // Poll every second
    public void processQueue() {
        try {
            List<Message> messages = sqsService.receiveMessages(videoExportQueueUrl, 1);
            for (Message message : messages) {
                try {
                    Map<String, String> taskDetails = objectMapper.readValue(message.body(), new TypeReference<>() {});
                    logger.info("Processing video filter job: jobId={}", taskDetails.get("jobId"));
                    videoFilterJobService.processJobFromSqs(taskDetails);
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