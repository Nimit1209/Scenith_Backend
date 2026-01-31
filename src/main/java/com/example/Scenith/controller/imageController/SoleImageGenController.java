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
            // Authenticate user
            User user = getUserFromToken(token);

            // Extract parameters
            String prompt = (String) request.get("prompt");
            String negativePrompt = (String) request.get("negativePrompt");

            // Validate
            if (prompt == null || prompt.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Prompt is required and cannot be empty");
            }

            // Generate images
            List<SoleImageGen> images = soleImageGenService.generateImages(user, prompt, negativePrompt);

            // Prepare response
            List<Map<String, Object>> imagesList = new ArrayList<>();
            for (SoleImageGen img : images) {
                Map<String, Object> imgData = new HashMap<>();
                imgData.put("id", img.getId());
                imgData.put("imagePath", img.getImagePath());
                imgData.put("prompt", img.getPrompt());
                imgData.put("resolution", img.getResolution());
                imgData.put("createdAt", img.getCreatedAt());
                imagesList.add(imgData);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("images", imagesList);
            response.put("count", images.size());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating images: " + e.getMessage());
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

            long monthlyUsage = soleImageGenService.getUserMonthlyImageGenUsage(user);
            long monthlyLimit = planLimitsService.getMonthlyImageGenLimit(user);
            long monthlyRemaining = monthlyLimit > 0 ? monthlyLimit - monthlyUsage : -1;

            long dailyUsage = soleImageGenService.getUserDailyImageGenUsage(user);
            long dailyLimit = planLimitsService.getDailyImageGenLimit(user);
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
            response.put("role", user.getRole().toString());
            response.put("imagesPerRequest", planLimitsService.getImagesPerRequest(user));
            response.put("resolution", planLimitsService.getImageResolution(user));

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