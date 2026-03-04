package com.example.Scenith.controller.imageController;


import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.SoleImageGen;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.PlanLimitsService;
import com.example.Scenith.service.imageService.SoleImageGenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sole-image-gen")
public class SoleImageGenController {

    private final SoleImageGenService soleImageGenService;
    private final PlanLimitsService planLimitsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public SoleImageGenController(
            SoleImageGenService soleImageGenService,
            PlanLimitsService planLimitsService,
            JwtUtil jwtUtil,
            UserRepository userRepository) {
        this.soleImageGenService = soleImageGenService;
        this.planLimitsService = planLimitsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateImages(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String prompt         = (String) request.get("prompt");
            String negativePrompt = (String) request.get("negativePrompt");
            String modelName      = (String) request.get("model");

            if (prompt == null || prompt.isBlank())
                return ResponseEntity.badRequest().body("Prompt is required and cannot be empty");
            if (modelName == null || modelName.isBlank())
                return ResponseEntity.badRequest().body("Model is required");

            com.example.Scenith.enums.ImageGenModel model;
            try {
                model = com.example.Scenith.enums.ImageGenModel.valueOf(modelName.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body("Unknown model: " + modelName);
            }

            SoleImageGen image = soleImageGenService.generateImage(user, prompt, negativePrompt, model);

            Map<String, Object> imgData = new HashMap<>();
            imgData.put("id",        image.getId());
            imgData.put("imagePath", image.getImagePath());
            imgData.put("prompt",    image.getPrompt());
            imgData.put("model",     model.getDisplayName());
            imgData.put("credits",   model.getCreditsPerImage());
            imgData.put("createdAt", image.getCreatedAt());

            return ResponseEntity.ok(imgData);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating image: " + e.getMessage());
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
    @GetMapping("/usage")
    public ResponseEntity<?> getImageGenUsage(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);

            int monthlyUsed      = soleImageGenService.getMonthlyCreditsUsed(user);
            int monthlyLimit     = planLimitsService.getMonthlyImageGenCredits(user);
            int dailyUsed        = soleImageGenService.getDailyCreditsUsed(user);
            int dailyLimit       = planLimitsService.getDailyImageGenCredits(user);

            List<Map<String, Object>> models = planLimitsService.getAvailableImageModels(user)
                    .stream()
                    .map(m -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id",            m.name());
                        map.put("displayName",   m.getDisplayName());
                        map.put("creditsPerImage", m.getCreditsPerImage());
                        return map;
                    }).toList();

            Map<String, Object> response = new HashMap<>();
            response.put("monthly", Map.of(
                    "used",      monthlyUsed,
                    "limit",     monthlyLimit,
                    "remaining", monthlyLimit - monthlyUsed
            ));
            response.put("daily", Map.of(
                    "used",      dailyUsed,
                    "limit",     dailyLimit,
                    "remaining", dailyLimit - dailyUsed
            ));
            response.put("availableModels", models);
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

    @GetMapping("/history")
    public ResponseEntity<?> getUserHistory(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<SoleImageGen> generations = soleImageGenService.getUserGenerations(user);

            List<Map<String, Object>> imagesList = new ArrayList<>();
            for (SoleImageGen img : generations) {
                Map<String, Object> imgData = new HashMap<>();
                imgData.put("id", img.getId());
                imgData.put("imagePath", img.getImagePath());
                imgData.put("prompt", img.getPrompt());
                imgData.put("negativePrompt", img.getNegativePrompt());
                imgData.put("resolution", img.getResolution());
                imgData.put("createdAt", img.getCreatedAt());
                imagesList.add(imgData);
            }

            return ResponseEntity.ok(imagesList);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Unauthorized: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}