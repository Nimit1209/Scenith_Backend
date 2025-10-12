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
public class SubtitleJobWorker {
  private static final Logger logger = LoggerFactory.getLogger(SubtitleJobWorker.class);
  private static final int MAX_RETRIES = 3; // Maximum number of retries

  private final SqsService sqsService;
  private final SubtitleService subtitleService;
  private final ObjectMapper objectMapper;

  @Value("${sqs.queue.url}")
  private String videoExportQueueUrl;

  public SubtitleJobWorker(SqsService sqsService, SubtitleService subtitleService, ObjectMapper objectMapper) {
    this.sqsService = sqsService;
    this.subtitleService = subtitleService;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 1000) // Poll every second
  public void processQueue() {
    try {
      List<Message> messages = sqsService.receiveMessages(videoExportQueueUrl, 1);
      for (Message message : messages) {
        try {
          // Check retry count
          String receiveCountStr = message.attributesAsStrings().getOrDefault("ApproximateReceiveCount", "1");
          int receiveCount = Integer.parseInt(receiveCountStr);
          if (receiveCount > MAX_RETRIES) {
            logger.error("Message {} exceeded max retries ({}), deleting and skipping", message.messageId(), MAX_RETRIES);
            sqsService.deleteMessage(message.receiptHandle(), videoExportQueueUrl);
            continue;
          }

          Map<String, String> taskDetails = objectMapper.readValue(message.body(), new TypeReference<Map<String, String>>() {});
          String taskType = taskDetails.get("taskType");
          Long mediaId = Long.parseLong(taskDetails.get("mediaId"));
          Long userId = Long.parseLong(taskDetails.get("userId"));

          logger.info("Processing subtitle task: mediaId={}", mediaId);
          if ("GENERATE_SUBTITLES".equals(taskType)) {
            subtitleService.generateSubtitlesTask(mediaId, userId, taskDetails);
          } else if ("PROCESS_SUBTITLES".equals(taskType)) {
            subtitleService.processSubtitlesTask(mediaId, userId);
          } else {
            logger.warn("Unknown task type: {}", taskType);
          }

          sqsService.deleteMessage(message.receiptHandle(), videoExportQueueUrl);
          logger.info("Successfully processed and deleted message: messageId={}", message.messageId());
        } catch (Exception e) {
          logger.error("Failed to process message: messageId={}, error={}", message.messageId(), e.getMessage(), e);
          // Message will be retried until max receives, then deleted above
        }
      }
    } catch (Exception e) {
      logger.error("Error polling queue {}: {}", videoExportQueueUrl, e.getMessage(), e);
    }
  }
}