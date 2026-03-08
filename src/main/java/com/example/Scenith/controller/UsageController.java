package com.example.Scenith.controller;

import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.StandaloneImageService;
import com.example.Scenith.service.imageService.ElementDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    @Autowired
    private ElementDownloadService elementDownloadService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StandaloneImageService standaloneImageService;

    /**
     * GET /api/usage/svg-downloads/me
     * Returns the authenticated user's total SVG/element download counts
     * for today and the current calendar month.
     */
    @GetMapping("/svg-downloads/me")
    public ResponseEntity<?> getSvgDownloadUsage(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.ok(Map.of("dailyCount", 0, "monthlyCount", 0));
            }
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user    = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            int daily   = elementDownloadService.getDailyDownloadCount(user);
            int monthly = elementDownloadService.getMonthlyDownloadCount(user);

            return ResponseEntity.ok(Map.of("dailyCount", daily, "monthlyCount", monthly));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("dailyCount", 0, "monthlyCount", 0));
        }
    }

    /**
     * GET /api/usage/bg-removal/me
     * Returns the authenticated user's background removal usage for the current month.
     * Delegates to StandaloneImageService — no changes needed there.
     *
     * Response:
     * {
     *   "monthlyUsed":  4,
     *   "monthlyLimit": 5,
     *   "remaining":    1,
     *   "maxQuality":   "720p",
     *   "userRole":     "BASIC"
     * }
     *
     * StandaloneImageService.getUserBackgroundRemovalStats() stores the count
     * under "usedThisMonth". We remap it to "monthlyUsed" right here so the
     * frontend fallback  (bg.monthlyUsed ?? bg.currentMonthCount ?? 0)
     * resolves correctly without touching any other file.
     */
    @GetMapping("/bg-removal/me")
    public ResponseEntity<?> getBgRemovalUsage(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.ok(Map.of(
                        "monthlyUsed",  0,
                        "monthlyLimit", 0,
                        "remaining",    0
                ));
            }
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user    = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Map<String, Object> raw = standaloneImageService.getUserBackgroundRemovalStats(user);

            // StandaloneImageService writes "usedThisMonth"; remap to "monthlyUsed"
            // so the dashboard reads it correctly — no service changes required.
            Object used = raw.get("usedThisMonth");

            return ResponseEntity.ok(Map.of(
                    "monthlyUsed",  used != null ? used : 0,
                    "monthlyLimit", raw.getOrDefault("monthlyLimit", 0),
                    "remaining",    raw.getOrDefault("remaining",    0),
                    "maxQuality",   raw.getOrDefault("maxQuality",   "720p"),
                    "userRole",     raw.getOrDefault("userRole",     "BASIC")
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "monthlyUsed",  0,
                    "monthlyLimit", 0,
                    "remaining",    0
            ));
        }
    }
}