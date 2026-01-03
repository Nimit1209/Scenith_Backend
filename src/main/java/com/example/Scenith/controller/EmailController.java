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
    public ResponseEntity<Map<String, Object>> sendAiVoiceCampaign(
            @RequestParam(defaultValue = "ai-voice-promo") String templateId) {

        emailService.sendAiVoiceCampaignBackground(templateId);

        return ResponseEntity.accepted().body(
                Map.of("message", "Campaign successfully started in background",
                        "status", "QUEUED")
        );
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