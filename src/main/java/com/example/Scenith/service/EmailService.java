package com.example.Scenith.service;

import com.example.Scenith.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${spring.mail.username}")
    private String fromEmail;

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final SesEmailService sesEmailService;

    public EmailService(
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            @Autowired(required = false) SesEmailService sesEmailService) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.sesEmailService = sesEmailService;

        if (sesEmailService != null) {
            logger.info("✅ Email Service initialized with Amazon SES support");
        } else {
            logger.info("⚠️ Email Service initialized with Gmail only (limited to 500/day)");
        }
    }

    // Keep all your existing methods (sendVerificationEmail, sendHtmlEmail, etc.)
    public void sendVerificationEmail(String to, String firstName, String token) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setTo(to);
        helper.setSubject("Welcome to Scenith – Verify Your Email to Start Creating! 🎬");

        String verificationUrl = frontendUrl + "/verify-email?token=" + token;
        String htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: 'Montserrat', Arial, sans-serif; background-color: #FAFAFA; margin: 0; padding: 0; }
                    .container { max-width: 600px; margin: 20px auto; background: #FFFFFF; border-radius: 10px; box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1); overflow: hidden; }
                    .header { background: linear-gradient(90deg, #3F8EFC, #B76CFD); padding: 20px; text-align: center; }
                    .header h1 { color: #FFFFFF; font-size: 24px; margin: 0; font-weight: 700; text-transform: uppercase; letter-spacing: 1.5px; }
                    .content { padding: 30px; color: #333333; }
                    .content p { font-size: 16px; line-height: 1.6; margin: 0 0 20px; }
                    .cta-button { display: inline-block; padding: 12px 24px; background: #B76CFD; color: #FFFFFF; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 16px; transition: background 0.3s; }
                    .cta-button:hover { background: #9446EB; }
                    .footer { background: #F4F4F9; padding: 20px; text-align: center; font-size: 14px; color: #666666; }
                    .footer a { color: #3F8EFC; text-decoration: none; }
                    .footer a:hover { color: #9446EB; }
                    @media (max-width: 600px) {
                        .container { margin: 10px; }
                        .header h1 { font-size: 20px; }
                        .content { padding: 20px; }
                        .content p { font-size: 14px; }
                        .cta-button { padding: 10px 20px; font-size: 14px; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Welcome to Scenith!</h1>
                    </div>
                    <div class="content">
                        <p>Dear %s,</p>
                        <p>Welcome to <strong>Scenith</strong>, where your creative vision reaches its zenith! We're thrilled to have you join our community of storytellers, editors, and creators. To get started on your journey to crafting stunning videos, please verify your email address.</p>
                        <p style="text-align: center;">
                            <a href="%s" class="cta-button">Verify My Email</a>
                        </p>
                        <p>This link will take you to a secure page to complete the verification process. It's quick, easy, and ensures your account is ready to dive into our intuitive timeline, dynamic transitions, and robust editing tools. The link will expire in 24 hours.</p>
                        <p><strong>Why Scenith?</strong><br>At Scenith, we're more than just a video editor—we're your creative partner. Built by creators for creators, our platform empowers you to tell compelling stories with precision and flair.</p>
                        <p><strong>What's Next?</strong><br>Once verified, you'll gain access to your personalized dashboard, where you can:<br>
                            - Start new projects with presets for YouTube, Instagram Reels, TikTok, and more.<br>
                            - Explore our comprehensive toolkit for audio, video, and keyframe editing.<br>
                            - Join a community passionate about visual storytelling.</p>
                        <p>If you didn't sign up for Scenith or have any questions, please contact us at <a href="mailto:scenith.spprt@gmail.com">scenith.spprt@gmail.com</a> </p>
                    </div>
                    <div class="footer">
                        <p><strong>Scenith</strong> – Elevating Visual Storytelling<br>
                        <a href="https://www.scenith.com">www.scenith.com</a> | <a href="mailto:sscenith.spprt@gmail.com">scenith.spprt@gmail.com</a></p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(firstName.isEmpty() ? "Creator" : firstName, verificationUrl);

        helper.setText(htmlContent, true);
        mailSender.send(message);
    }
    /**
     * Send HTML email – prefers SES if available, falls back to Gmail
     */
    public void sendHtmlEmail(String to, String subject, String htmlContent)
            throws MessagingException, UnsupportedEncodingException {

        if (sesEmailService != null) {
            logger.info("Sending via SES: {}", to);
            try {
                sesEmailService.sendEmail(to, subject, htmlContent);
                return;
            } catch (Exception e) {
                logger.error("SES failed for {}, falling back to Gmail: {}", to, e.getMessage());
            }
        }

        // Gmail fallback
        logger.info("Sending via Gmail fallback: {}", to);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

        helper.setFrom(fromEmail, "Scenith AI");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    /**
     * Send templated email – now uses the improved sendHtmlEmail
     */
    public void sendTemplateEmail(String to, String templateName, String templateId, Map<String, String> variables)
            throws IOException, MessagingException {

        Map<String, Object> template = loadSpecificTemplate(templateName, templateId);
        String subject = (String) template.get("subject");
        String htmlContent = generateEmailHtml(template, variables);

        sendHtmlEmail(to, subject, htmlContent);   // ← now smart, uses SES when possible
    }
    public Map<String, Object> loadEmailTemplate(String templateName) throws IOException {
        ClassPathResource resource = new ClassPathResource("email-templates/" + templateName + ".json");
        return objectMapper.readValue(resource.getInputStream(), Map.class);
    }

    public String generateEmailHtml(Map<String, Object> template, Map<String, String> variables) {
        String htmlContent = (String) template.get("htmlContent");

        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                htmlContent = htmlContent.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }

        return htmlContent;
    }

    public Map<String, Object> loadSpecificTemplate(String templateName, String templateId) throws IOException {
        ClassPathResource resource = new ClassPathResource("email-templates/" + templateName + ".json");
        Map<String, Object> data = objectMapper.readValue(resource.getInputStream(), Map.class);

        List<Map<String, Object>> templates = (List<Map<String, Object>>) data.get("templates");
        return templates.stream()
                .filter(t -> templateId.equals(t.get("id")))
                .findFirst()
                .orElseThrow(() -> new IOException("Template with id '" + templateId + "' not found"));
    }



    /**
     * ⭐ UPDATED: Send to ALL users using SES (no limits!)
     */
    @Async("campaignEmailExecutor")
    public CompletableFuture<Object> sendAiVoiceCampaignBackground(String templateId) {
        try {
            // Load template
            Map<String, Object> template = loadSpecificTemplate("ai-voice-generation-campaign", templateId);
            String subject = (String) template.get("subject");
            String baseHtml = (String) template.get("htmlContent");

            // Get ALL users from database
            List<Object[]> usersData = userRepository.findAllEmailAndName();

            logger.info("📧 Campaign requested for {} users", usersData.size());

            // Use SES if available (sends to ALL users), otherwise Gmail (limited to 500)
            if (sesEmailService != null) {
                logger.info("✅ Using Amazon SES - Sending to ALL {} users", usersData.size());
                // Cast to Object to match return type
                return sesEmailService.sendBulkCampaign(subject, baseHtml, usersData)
                        .thenApply(result -> (Object) result);
            } else {
                logger.warn("⚠️ SES not available - Using Gmail (limited to 500 users/day)");
                // Cast to Object to match return type
                return sendViaGmail(subject, baseHtml, usersData)
                        .thenApply(result -> (Object) result);
            }

        } catch (Exception e) {
            logger.error("❌ Campaign failed: {}", e.getMessage(), e);
            CampaignResult errorResult = new CampaignResult();
            errorResult.failureCount = (int) userRepository.count();
            return CompletableFuture.completedFuture((Object) errorResult);
        }
    }

    /**
     * Gmail fallback (limited to 500/day - only used if SES is not available)
     */
    private CompletableFuture<CampaignResult> sendViaGmail(String subject, String baseHtml, List<Object[]> usersData) {
        long start = System.currentTimeMillis();
        CampaignResult result = new CampaignResult();
        result.totalUsers = Math.min(usersData.size(), 500); // Gmail daily limit (FIXED - was 50000)

        if (result.totalUsers == 0) {
            return CompletableFuture.completedFuture(result);
        }

        logger.warn("⚠️ Gmail has a 500/day limit. Only sending to first {} users", result.totalUsers);

        try {
            int batchSize = 80;
            int throttleMs = 1200;

            for (int i = 0; i < result.totalUsers; i += batchSize) {
                int end = Math.min(i + batchSize, result.totalUsers);
                List<Object[]> batch = usersData.subList(i, end);

                List<MimeMessage> messages = new ArrayList<>(batch.size());

                for (Object[] row : batch) {
                    String email = (String) row[0];
                    String name = row[1] != null ? (String) row[1] : "Creator";

                    String html = baseHtml
                            .replace("{{userName}}", StringEscapeUtils.escapeHtml4(name))
                            .replace("{{userEmail}}", StringEscapeUtils.escapeHtml4(email));

                    MimeMessage msg = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());

                    helper.setFrom(fromEmail, "Scenith AI");
                    helper.setTo(email);
                    helper.setSubject(subject);
                    helper.setText(html, true);

                    messages.add(msg);
                }

                try {
                    mailSender.send(messages.toArray(new MimeMessage[0]));
                    result.successCount += messages.size();
                } catch (MailException e) {
                    logger.error("Gmail batch failed: {}", e.getMessage());
                    result.failureCount += messages.size();
                }

                Thread.sleep(throttleMs);
            }
        } catch (Exception e) {
            logger.error("Gmail campaign error: {}", e.getMessage());
            result.failureCount = result.totalUsers - result.successCount;
        }

        result.durationSeconds = (System.currentTimeMillis() - start) / 1000;
        return CompletableFuture.completedFuture(result);
    }
    // Simple DTO for result tracking
    public static class CampaignResult {
        public int totalUsers = 0;
        public int successCount = 0;
        public int failureCount = 0;
        public long durationSeconds = 0;

        @Override
        public String toString() {
            return String.format("CampaignResult{total=%d, success=%d, failed=%d, time=%ds}",
                    totalUsers, successCount, failureCount, durationSeconds);
        }
    }
}