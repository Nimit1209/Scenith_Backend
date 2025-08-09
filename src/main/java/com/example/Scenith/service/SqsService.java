package com.example.Scenith.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

@Service
public class SqsService {
    private static final Logger logger = LoggerFactory.getLogger(SqsService.class);
    private SqsClient sqsClient;

    @Value("${sqs.queue.url}")
    private String queueUrl;

    @Value("${sqs.region}")
    private String region;

    @PostConstruct
    public void init() {
        logger.info("Initializing SqsService with queue URL: {}", queueUrl);
        this.sqsClient = SqsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create()) // Use default credential provider
                .region(Region.of(region))
                .build();
    }

    public String sendMessage(String messageBody) {
        try {
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();
            SendMessageResponse response = sqsClient.sendMessage(request);
            logger.info("Sent message to SQS: messageId={}", response.messageId());
            return response.messageId();
        } catch (SqsException e) {
            logger.error("Failed to send message to SQS: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send message to SQS", e);
        }
    }

    public List<Message> receiveMessages(int maxMessages) {
        try {
            ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(maxMessages)
                    .waitTimeSeconds(20) // Long polling
                    .build();
            List<Message> messages = sqsClient.receiveMessage(request).messages();
            logger.info("Received {} messages from SQS", messages.size());
            return messages;
        } catch (SqsException e) {
            logger.error("Failed to receive messages from SQS: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to receive messages from SQS", e);
        }
    }

    public void deleteMessage(String receiptHandle) {
        try {
            DeleteMessageRequest request = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();
            sqsClient.deleteMessage(request);
            logger.info("Deleted message from SQS: receiptHandle={}", receiptHandle);
        } catch (SqsException e) {
            logger.error("Failed to delete message from SQS: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete message from SQS", e);
        }
    }
}