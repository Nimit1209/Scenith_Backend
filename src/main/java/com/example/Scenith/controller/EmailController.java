package com.example.Scenith.controller;

import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.service.EmailService;
import com.example.Scenith.service.SesEmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private static final Logger logger = LoggerFactory.getLogger(EmailController.class);

    private final EmailService emailService;
    private final UserRepository userRepository;
    private final SesEmailService sesEmailService;

    public EmailController(
            EmailService emailService,
            UserRepository userRepository,
            @Autowired(required = false) SesEmailService sesEmailService) {
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.sesEmailService = sesEmailService;
    }

    /**
     * ⭐ Send campaign to ALL users
     * Example: POST /api/emails/send-ai-voice-campaign?templateId=value-based-pricing-promo
     */
    @PostMapping("/send-ai-voice-campaign")
    public ResponseEntity<Map<String, Object>> sendAiVoiceCampaign(
            @RequestParam(defaultValue = "ai-voice-promo") String templateId) {

        long totalUsers = userRepository.count();
        String provider = sesEmailService != null ? "Amazon SES" : "Gmail";

        logger.info("📧 Campaign triggered: {} users, using {}", totalUsers, provider);

        emailService.sendAiVoiceCampaignBackground(templateId);

        return ResponseEntity.accepted().body(
                Map.of(
                        "message", "Campaign started successfully",
                        "status", "QUEUED",
                        "totalUsers", totalUsers,
                        "provider", provider,
                        "estimatedTime", totalUsers <= 1000 ? "5-10 minutes" : "15-30 minutes"
                )
        );
    }

    /**
     * Send custom email to single user (transactional)
     */
    @PostMapping("/send-custom")
    public ResponseEntity<?> sendCustomEmail(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String templateName = request.get("templateName");
            String templateId = request.get("templateId");

            if (email == null || templateName == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Email and templateName are required"));
            }

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, String> variables = new HashMap<>();
            variables.put("userName", user.getName() != null ? user.getName() : "Creator");
            variables.put("userEmail", user.getEmail());

            emailService.sendTemplateEmail(email, templateName, templateId, variables);

            return ResponseEntity.ok(Map.of(
                    "message", "Email sent successfully",
                    "provider", "Gmail (Transactional)"
            ));

        } catch (Exception e) {
            logger.error("Error sending custom email: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("message", "Error: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        boolean sesAvailable = sesEmailService != null;

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "providers", Map.of(
                        "gmail", "enabled",
                        "ses", sesAvailable ? "enabled (unlimited)" : "disabled"
                ),
                "sesLimits", sesAvailable ? Map.of(
                        "dailyQuota", "50,000 emails",
                        "rateLimit", "14 emails/second",
                        "actualLimit", "UNLIMITED (respecting rate limits)"
                ) : "N/A"
        ));
    }
}