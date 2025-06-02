package com.example.Scenith.service;

import com.example.Scenith.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CleanupService {
    private final UserRepository userRepository;

    public CleanupService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Scheduled(cron = "0 0 0 * * ?") // Daily at midnight
    @Transactional
    public void cleanupUnverifiedUsers() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        userRepository.deleteByIsEmailVerifiedFalseAndCreatedBefore(threshold);
    }
}