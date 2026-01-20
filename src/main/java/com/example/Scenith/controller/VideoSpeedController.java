package com.example.Scenith.controller;

import com.example.Scenith.dto.VideoSpeedRequest;
import com.example.Scenith.dto.VideoSpeedResponse;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoSpeed;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.service.VideoSpeedService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/video-speed")
@RequiredArgsConstructor
public class VideoSpeedController {
    private static final Logger logger = LoggerFactory.getLogger(VideoSpeedController.class);

    private final VideoSpeedService videoSpeedService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/upload")
    public ResponseEntity<VideoSpeedResponse> uploadVideo(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "speed", required = false) Double speed) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.uploadVideo(user, file, speed);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Video uploaded: id={}", video.getId());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            logger.error("Error uploading video: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new VideoSpeedResponse());
        } catch (RuntimeException e) {
            logger.warn("Unauthorized: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new VideoSpeedResponse());
        }
    }

    @PutMapping("/{id}/speed")
    public ResponseEntity<VideoSpeedResponse> updateSpeed(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestBody VideoSpeedRequest request) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.updateSpeed(id, user, request.getSpeed());
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Speed updated: id={}, speed={}", id, request.getSpeed());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid speed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new VideoSpeedResponse());
        } catch (RuntimeException e) {
            logger.warn("Unauthorized or video not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new VideoSpeedResponse());
        }
    }

    @PostMapping("/{id}/export")
    public ResponseEntity<?> initiateExport(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id,
            @RequestParam(required = false) String quality) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.initiateExport(id, user, quality);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Export initiated: id={}, quality={}", id, quality);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            logger.error("Error initiating export: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to initiate export: " + e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("Cannot initiate export for video id={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("Unauthorized or video not found for id={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<VideoSpeedResponse> getVideoStatus(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = getUserFromToken(token);
            VideoSpeed video = videoSpeedService.getVideoStatus(id, user);
            VideoSpeedResponse response = mapToResponse(video);
            logger.info("Retrieved status: id={}, status={}", id, video.getStatus());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.warn("Video not found or unauthorized: id={}, error={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new VideoSpeedResponse());
        }
    }

    @GetMapping("/user-videos")
    public ResponseEntity<List<VideoSpeedResponse>> getUserVideos(
            @RequestHeader("Authorization") String token) {
        try {
            User user = getUserFromToken(token);
            List<VideoSpeed> videos = videoSpeedService.getUserVideos(user);
            List<VideoSpeedResponse> responses = videos.stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
            logger.info("Retrieved {} videos for user", responses.size());
            return ResponseEntity.ok(responses);
        } catch (RuntimeException e) {
            logger.warn("Unauthorized: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(List.of());
        }
    }

    private VideoSpeedResponse mapToResponse(VideoSpeed video) {
        VideoSpeedResponse response = new VideoSpeedResponse();
        response.setId(video.getId());
        response.setStatus(video.getStatus());
        response.setProgress(video.getProgress());
        response.setSpeed(video.getSpeed());
        response.setCdnUrl(video.getCdnUrl());
        response.setOriginalFilePath(video.getOriginalFilePath());
        return response;
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = getUserFromToken(token);
            videoSpeedService.deleteVideo(id, user);
            return ResponseEntity.ok(Map.of("message", "Video deleted successfully"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Delete failed: " + e.getMessage()));
        }
    }
}