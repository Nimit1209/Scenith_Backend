package com.example.Scenith.sqs;

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

    @Value("${sqs.region}")
    private String region;

    @PostConstruct
    public void init() {
        logger.info("Initializing SqsService with region: {}", region);
        this.sqsClient = SqsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .build();
    }

    public String sendMessage(String messageBody, String queueUrl) {
        try {
            SendMessageRequest sendMsgRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();
            SendMessageResponse response = sqsClient.sendMessage(sendMsgRequest);
            logger.info("Message sent to queue {}: messageId={}", queueUrl, response.messageId());
            return response.messageId();
        } catch (SqsException e) {
            logger.error("Failed to send message to queue {}: {}", queueUrl, e.getMessage(), e);
            throw e;
        }
    }

    public List<Message> receiveMessages(String queueUrl, int maxMessages) {
        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(maxMessages)
                    .waitTimeSeconds(20) // Long polling for efficiency
                    .visibilityTimeout(30)
                    .build();
            List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();
            logger.debug("Received {} messages from queue {}", messages.size(), queueUrl);
            return messages;
        } catch (SqsException e) {
            logger.error("Failed to receive messages from queue {}: {}", queueUrl, e.getMessage(), e);
            throw e;
        }
    }

    public void deleteMessage(String receiptHandle, String queueUrl) {
        try {
            DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();
            sqsClient.deleteMessage(deleteRequest);
            logger.debug("Deleted message from queue {}: receiptHandle={}", queueUrl, receiptHandle);
        } catch (SqsException e) {
            logger.error("Failed to delete message from queue {}: {}", queueUrl, e.getMessage(), e);
            throw e;
        }
    }
}