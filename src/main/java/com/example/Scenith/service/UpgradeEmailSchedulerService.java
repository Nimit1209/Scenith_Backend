package com.example.Scenith.service;

import com.example.Scenith.entity.ScheduledUpgradeEmail;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ScheduledUpgradeEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UpgradeEmailSchedulerService {
    private static final Logger logger = LoggerFactory.getLogger(UpgradeEmailSchedulerService.class);

    private final ScheduledUpgradeEmailRepository scheduledUpgradeEmailRepository;
    private final EmailService emailService;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    public UpgradeEmailSchedulerService(
            ScheduledUpgradeEmailRepository scheduledUpgradeEmailRepository,
            EmailService emailService) {
        this.scheduledUpgradeEmailRepository = scheduledUpgradeEmailRepository;
        this.emailService = emailService;
    }

    public void scheduleUpgradeEmail(User user, String triggerAction) {
        if (user.getRole() != User.Role.BASIC && user.getRole() != User.Role.CREATOR) {
            logger.debug("User {} has role {}, skipping upgrade email", user.getEmail(), user.getRole());
            return;
        }

        LocalDateTime scheduledTime = LocalDateTime.now().plusHours(1);
        ScheduledUpgradeEmail scheduledEmail = new ScheduledUpgradeEmail(user, scheduledTime, triggerAction);
        scheduledUpgradeEmailRepository.save(scheduledEmail);
        
        logger.info("Scheduled upgrade email for user {} ({}) at {}", 
                user.getEmail(), user.getRole(), scheduledTime);
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void processPendingEmails() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledUpgradeEmail> pendingEmails = 
                scheduledUpgradeEmailRepository.findBySentFalseAndScheduledTimeBefore(now);

        if (pendingEmails.isEmpty()) return;

        logger.info("Processing {} pending upgrade emails", pendingEmails.size());

        for (ScheduledUpgradeEmail scheduledEmail : pendingEmails) {
            try {
                sendUpgradeEmail(scheduledEmail);
                scheduledEmail.setSent(true);
                scheduledEmail.setSentAt(LocalDateTime.now());
                scheduledUpgradeEmailRepository.save(scheduledEmail);
                logger.info("Successfully sent upgrade email to {}", scheduledEmail.getUser().getEmail());
            } catch (Exception e) {
                logger.error("Failed to send upgrade email to {}: {}", 
                        scheduledEmail.getUser().getEmail(), e.getMessage(), e);
            }
        }
    }

    private void sendUpgradeEmail(ScheduledUpgradeEmail scheduledEmail) throws Exception {
        User user = scheduledEmail.getUser();
        Map<String, String> variables = new HashMap<>();
        variables.put("userName", Optional.ofNullable(user.getName()).orElse("Creator"));
        variables.put("upgradeUrl", frontendUrl + "/pricing?utm_source=email&utm_medium=upgrade_prompt&utm_campaign=post_tts");
        variables.put("pricingUrl", frontendUrl + "/pricing?utm_source=email");
        variables.put("supportUrl", frontendUrl + "/support?utm_source=email");

        emailService.sendTemplateEmail(
                user.getEmail(),
                "ai-voice-generation-campaign",
                "unlock-more-features",
                variables
        );
    }
}