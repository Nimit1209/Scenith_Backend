package com.example.Scenith.service;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SesEmailService {

    private static final Logger logger = LoggerFactory.getLogger(SesEmailService.class);

    private final SesClient sesClient;

    @Value("${aws.ses.from.email}")
    private String fromEmail;

    @Value("${aws.ses.from.name}")
    private String fromName;

    public SesEmailService(SesClient sesClient) {
        this.sesClient = sesClient;
        logger.info("✅ SES Email Service initialized - Ready to send to unlimited users");
    }

    /**
     * Send single email via SES
     */
    public void sendEmail(String to, String subject, String htmlBody) {
        try {
            Destination destination = Destination.builder()
                    .toAddresses(to)
                    .build();

            Content subjectContent = Content.builder()
                    .data(subject)
                    .charset("UTF-8")
                    .build();

            Content htmlContent = Content.builder()
                    .data(htmlBody)
                    .charset("UTF-8")
                    .build();

            Body body = Body.builder()
                    .html(htmlContent)
                    .build();

            Message message = Message.builder()
                    .subject(subjectContent)
                    .body(body)
                    .build();

            SendEmailRequest emailRequest = SendEmailRequest.builder()
                    .destination(destination)
                    .message(message)
                    .source(fromName + " <" + fromEmail + ">")
                    .build();

            SendEmailResponse response = sesClient.sendEmail(emailRequest);

            logger.debug("✅ Email sent to: {} | MessageId: {}", to, response.messageId());

        } catch (SesException e) {
            logger.error("❌ SES failed to: {} | Error: {}", to, e.awsErrorDetails().errorMessage());
            throw new RuntimeException("SES send failed: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    /**
     * ⭐ Send to ALL users (NO LIMIT)
     */
    @Async("campaignEmailExecutor")
    public CompletableFuture<CampaignResult> sendBulkCampaign(
            String subject,
            String htmlTemplate,
            List<Object[]> recipients) {

        long start = System.currentTimeMillis();
        CampaignResult result = new CampaignResult();
        result.totalUsers = recipients.size();

        logger.info("🚀 Starting SES campaign: Sending to ALL {} users", recipients.size());

        try {
            int batchSize = 50; // Send 50 emails at a time
            int delayMs = 5000; // 5 seconds between batches = ~10 emails/second (safe rate)

            for (int i = 0; i < recipients.size(); i += batchSize) {
                int end = Math.min(i + batchSize, recipients.size());
                List<Object[]> batch = recipients.subList(i, end);

                // Send each email in the batch
                for (Object[] row : batch) {
                    String email = (String) row[0];
                    String name = row[1] != null ? (String) row[1] : "Creator";

                    try {
                        // Personalize email
                        String personalizedHtml = htmlTemplate
                                .replace("{{userName}}", StringEscapeUtils.escapeHtml4(name))
                                .replace("{{userEmail}}", StringEscapeUtils.escapeHtml4(email));

                        sendEmail(email, subject, personalizedHtml);
                        result.successCount++;

                    } catch (Exception e) {
                        logger.error("❌ Failed {}: {}", email, e.getMessage());
                        result.failureCount++;
                    }
                }

                // Throttle between batches to stay under rate limit (14 emails/sec)
                if (end < recipients.size()) {
                    Thread.sleep(delayMs);
                }

                // Log progress every 100 emails
                if ((i + batchSize) % 100 == 0 || end == recipients.size()) {
                    logger.info("📊 Progress: {}/{} sent, {} failed",
                            result.successCount, result.totalUsers, result.failureCount);
                }
            }

        } catch (Exception e) {
            logger.error("❌ Campaign error: {}", e.getMessage(), e);
            result.failureCount += (result.totalUsers - result.successCount - result.failureCount);
        }

        result.durationSeconds = (System.currentTimeMillis() - start) / 1000;
        logger.info("✅ Campaign completed: {}", result);

        return CompletableFuture.completedFuture(result);
    }

    // Result DTO
    public static class CampaignResult {
        public int totalUsers = 0;
        public int successCount = 0;
        public int failureCount = 0;
        public long durationSeconds = 0;

        @Override
        public String toString() {
            return String.format("total=%d, success=%d, failed=%d, time=%ds",
                    totalUsers, successCount, failureCount, durationSeconds);
        }
    }
}