package com.example.Scenith.controller;


import com.example.Scenith.dto.VideoFilterJobRequest;
import com.example.Scenith.dto.VideoFilterJobResponse;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.VideoFilterJobService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/filter-jobs")
@RequiredArgsConstructor
public class VideoFilterJobController {
    private static final Logger logger = LoggerFactory.getLogger(VideoFilterJobController.class);

    private final VideoFilterJobService jobService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    // ===================== CREATE FILTER JOB =====================
    @PostMapping("/from-upload/{uploadId}")
    public ResponseEntity<?> createJobFromUpload(
        @RequestHeader("Authorization") String token,
        @PathVariable Long uploadId,
        @RequestBody VideoFilterJobRequest request
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse response = jobService.createJobFromUpload(uploadId, request, user);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to create job: " + e.getMessage());
        }
    }

    // ===================== GET SINGLE JOB =====================
    @GetMapping("/{id}")
    public ResponseEntity<?> getJob(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse job = jobService.getJob(id, user);
            return ResponseEntity.ok(job);
        } catch (RuntimeException e) {
            logger.error("Failed to get job {}: {}", id, e.getMessage(), e);

            // Check if it's an authentication issue
            if (e.getMessage().contains("User not found") ||
                    e.getMessage().contains("Invalid token") ||
                    e.getMessage().contains("Token expired")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Unauthorized: " + e.getMessage());
            }

            // Check if it's a job not found or unauthorized access
            if (e.getMessage().contains("Job not found or not authorized")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Access denied: " + e.getMessage());
            }

            // Default to bad request for other issues
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error getting job {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Internal server error");
        }
    }


    // ===================== LIST FILTER JOBS =====================
    @GetMapping("/my")
    public ResponseEntity<?> getJobsByUser(@RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<VideoFilterJobResponse> jobs = jobService.getJobsByUser(user);
            return ResponseEntity.ok(jobs);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("Unauthorized: " + e.getMessage());
        }
    }

    // ===================== PROCESS FILTER JOB =====================
    @PostMapping("/{id}/process")
    public ResponseEntity<?> processJob(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        try {
            User user = getUserFromToken(token);
            jobService.processJob(id, user);
            return ResponseEntity.ok("Job processing started successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Processing failed: " + e.getMessage());
        }
    }

    // ===================== UPDATE FILTER JOB =====================
    @PutMapping("/{id}")
    public ResponseEntity<?> updateJob(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id,
        @RequestBody VideoFilterJobRequest request
    ) {
        try {
            User user = getUserFromToken(token);
            VideoFilterJobResponse response = jobService.updateJob(id, request, user);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to update job: " + e.getMessage());
        }
    }


    // ===================== HELPER =====================
    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7)); // strip "Bearer "
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }
}