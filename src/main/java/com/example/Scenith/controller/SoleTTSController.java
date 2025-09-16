package com.example.Scenith.controller;

import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.SoleTTSRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.SoleTTSService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
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

    public SoleTTSController(SoleTTSService soleTTSService, JwtUtil jwtUtil, UserRepository userRepository, SoleTTSRepository soleTTSRepository) {
        this.soleTTSService = soleTTSService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.soleTTSRepository = soleTTSRepository;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateTTS(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, String> request) {
        try {
            // Authenticate user
            User user = getUserFromToken(token);

            // Extract parameters
            String text = request.get("text");
            String voiceName = request.get("voiceName");
            String languageCode = request.get("languageCode");

            // Validate parameters
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Text is required and cannot be empty"));
            }
            if (voiceName == null || voiceName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Voice name is required"));
            }
            if (languageCode == null || languageCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Language code is required"));
            }

            // Generate TTS
            SoleTTS soleTTS = soleTTSService.generateTTS(user, text, voiceName, languageCode);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("id", soleTTS.getId());
            response.put("audioPath", soleTTS.getAudioPath());
            response.put("cdnUrl", soleTTS.getCdnUrl());
            response.put("presignedUrl", soleTTS.getPresignedUrl());
            response.put("createdAt", soleTTS.getCreatedAt());
            // response.put("waveformPath", soleTTS.getWaveformPath()); // Uncomment if added

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error generating TTS audio: " + e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Unauthorized: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unexpected error: " + e.getMessage()));
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

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}