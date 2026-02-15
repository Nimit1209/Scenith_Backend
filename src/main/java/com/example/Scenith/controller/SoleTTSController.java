package com.example.Scenith.controller;

import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import com.example.Scenith.enums.PlanType;
import com.example.Scenith.repository.SoleTTSRepository;
import com.example.Scenith.repository.UserPlanRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.PlanLimitsService;
import com.example.Scenith.service.SoleTTSService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sole-tts")
public class SoleTTSController {

    private final SoleTTSService soleTTSService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SoleTTSRepository soleTTSRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanLimitsService planLimitsService;

    public SoleTTSController(SoleTTSService soleTTSService, JwtUtil jwtUtil, UserRepository userRepository, SoleTTSRepository soleTTSRepository, UserPlanRepository userPlanRepository, PlanLimitsService planLimitsService) {
        this.soleTTSService = soleTTSService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.soleTTSRepository = soleTTSRepository;
        this.userPlanRepository = userPlanRepository;
        this.planLimitsService = planLimitsService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTTS(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            // Authenticate user
            User user = getUserFromToken(token);

            // Extract parameters
            String text = (String) request.get("text");
            String voiceName = (String) request.get("voiceName");
            String languageCode = (String) request.get("languageCode");
            String emotion = (String) request.getOrDefault("emotion", "default");  // NEW

            @SuppressWarnings("unchecked")
            Map<String, String> customConfig = (Map<String, String>) request.get("customConfig");

            // Validate parameters
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text is required and cannot be empty");
            }
            if (voiceName == null || voiceName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Voice name is required");
            }
            if (languageCode == null || languageCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Language code is required");
            }

            // Generate TTS with emotion
            SoleTTS soleTTS = soleTTSService.generateTTS(
                    user, text, voiceName, languageCode, emotion, customConfig
            );

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("id", soleTTS.getId());
            response.put("audioPath", soleTTS.getAudioPath());
            response.put("createdAt", soleTTS.getCreatedAt());

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating TTS audio: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Unauthorized: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    @GetMapping("/user-tts")
    public ResponseEntity<?> getUserTTS(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<SoleTTS> ttsList = soleTTSRepository.findByUser(user);
            return ResponseEntity.ok(ttsList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error fetching TTS: " + e.getMessage()));
        }
    }
    @GetMapping("/usage")
    public ResponseEntity<?> getTtsUsage(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            long monthlyUsage = soleTTSService.getUserTtsUsage(user);
            long monthlyLimit = planLimitsService.getMonthlyTtsLimit(user);
            long monthlyRemaining = monthlyLimit > 0 ? monthlyLimit - monthlyUsage : -1;
            long dailyUsage = soleTTSService.getUserDailyTtsUsage(user);
            long dailyLimit = planLimitsService.getDailyTtsLimit(user);
            long maxCharRequest = planLimitsService.getMaxCharsPerRequest(user);
            long dailyRemaining = dailyLimit > 0 ? dailyLimit - dailyUsage : -1;

            Map<String, Object> response = new HashMap<>();
            response.put("monthly", Map.of(
                    "used", monthlyUsage,
                    "limit", monthlyLimit,
                    "remaining", monthlyRemaining
            ));
            response.put("daily", Map.of(
                    "used", dailyUsage,
                    "limit", dailyLimit,
                    "remaining", dailyRemaining
            ));
            response.put("maxCharRequest", maxCharRequest);
            response.put("role", user.getRole().toString());

            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Unauthorized: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }
    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getTtsHistory(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);

            // Check if user has access to history
            if (!hasHistoryAccess(user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of(
                                "error", "History access denied",
                                "message", "Upgrade to AI Voice PRO or a premium plan to access your generation history"
                        ));
            }

            List<SoleTTS> history = soleTTSService.getUserHistory(user);

            List<Map<String, Object>> historyResponse = history.stream()
                    .map(tts -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("id", tts.getId());
                        item.put("audioPath", tts.getAudioPath());
                        item.put("createdAt", tts.getCreatedAt());
                        return item;
                    })
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "history", historyResponse,
                    "hasAccess", true
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Unauthorized: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    private boolean hasHistoryAccess(User user) {
        // Allow if user is not BASIC
        if (user.getRole() != User.Role.BASIC) {
            return true;
        }

        // If BASIC, check if they have AI_VOICE_PRO plan
        return userPlanRepository.findActiveUserPlan(
                user,
                PlanType.AI_VOICE_PRO,
                LocalDateTime.now()
        ).isPresent();
    }
}