package com.example.Scenith.controller;

import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.VideoFilterUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class VideoFilterUploadController {

    private final VideoFilterUploadService uploadService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    @PostMapping
    public ResponseEntity<?> uploadVideo(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file) {
        try {
            User user = getUserFromToken(token);
            return ResponseEntity.ok(uploadService.uploadVideo(file, user));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Upload failed: " + e.getMessage());
        }
    }

    @GetMapping("/my")
    public ResponseEntity<?> getUserVideos(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            return ResponseEntity.ok(uploadService.getUserVideos(user));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized: " + e.getMessage());
        }
    }

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7)); // strip "Bearer "
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}