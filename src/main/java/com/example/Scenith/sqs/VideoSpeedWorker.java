package com.example.Scenith.sqs;

import com.example.Scenith.service.VideoSpeedService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VideoSpeedWorker {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedWorker.class);

    private final SqsService sqsService;
    private final VideoSpeedService videoSpeedService;
    private final ObjectMapper objectMapper;

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;

    @Scheduled(fixedDelay = 10000)
    public void processQueue() {
        try {
            List<Message> messages = sqsService.receiveMessages(videoExportQueueUrl, 1);
            for (Message message : messages) {
                try {
                    Map<String, String> taskDetails = objectMapper.readValue(message.body(), new TypeReference<>() {});
                    logger.info("Processing speed task: videoId={}", taskDetails.get("videoId"));
                    videoSpeedService.processSpeedTask(taskDetails);
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