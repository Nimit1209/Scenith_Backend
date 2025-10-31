package com.example.Scenith.controller;

import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private static final Logger logger = LoggerFactory.getLogger(EmailController.class);
    
    private final EmailService emailService;
    private final UserRepository userRepository;
    
    public EmailController(EmailService emailService, UserRepository userRepository) {
        this.emailService = emailService;
        this.userRepository = userRepository;
    }
    
    @PostMapping("/send-ai-voice-campaign")
    public ResponseEntity<?> sendAiVoiceCampaign(@RequestParam(defaultValue = "ai-voice-promo") String templateId) {
        try {
            List<User> users = userRepository.findAll();
            int successCount = 0;
            int failureCount = 0;
            
            for (User user : users) {
                try {
                    Map<String, String> variables = new HashMap<>();
                    variables.put("userName", user.getName() != null ? user.getName() : "Creator");
                    variables.put("userEmail", user.getEmail());

                    emailService.sendTemplateEmail(
                            user.getEmail(),
                            "ai-voice-generation-campaign",
                            templateId,
                            variables
                    );
                    successCount++;
                    logger.info("AI Voice campaign email sent to: {}", user.getEmail());
                } catch (Exception e) {
                    failureCount++;
                    logger.error("Failed to send email to {}: {}", user.getEmail(), e.getMessage());
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "AI Voice campaign completed");
            response.put("totalUsers", users.size());
            response.put("successCount", successCount);
            response.put("failureCount", failureCount);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error sending AI Voice campaign: {}", e.getMessage());
            return ResponseEntity.status(500)
                .body(Map.of("message", "Error sending campaign: " + e.getMessage()));
        }
    }
    
    @PostMapping("/send-custom")
    public ResponseEntity<?> sendCustomEmail(
            @RequestBody Map<String, String> request) {
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
            
            return ResponseEntity.ok(Map.of("message", "Email sent successfully"));
        } catch (Exception e) {
            logger.error("Error sending custom email: {}", e.getMessage());
            return ResponseEntity.status(500)
                .body(Map.of("message", "Error sending email: " + e.getMessage()));
        }
    }
}