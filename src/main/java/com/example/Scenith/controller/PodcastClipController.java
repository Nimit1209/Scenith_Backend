package com.example.Scenith.controller;

import com.example.Scenith.entity.PodcastClipMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.PodcastClipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/podcast-clips")
public class PodcastClipController {

    @Autowired
    private PodcastClipService podcastClipService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(
            @RequestHeader("Authorization") String token,
            @RequestParam(value = "file", required = false) MultipartFile mediaFile,
            @RequestParam(value = "youtubeUrl", required = false) String youtubeUrl) {
        try {
            User user = podcastClipService.getUserFromToken(token);
            PodcastClipMedia result = podcastClipService.uploadMedia(user, mediaFile, youtubeUrl);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/generate/{mediaId}")
    public ResponseEntity<?> generateClips(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId) {
        try {
            User user = podcastClipService.getUserFromToken(token);
            PodcastClipMedia result = podcastClipService.initiateProcessing(user, mediaId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Clip generation failed: " + e.getMessage()));
        }
    }

    @GetMapping("/user-media")
    public ResponseEntity<?> getUserPodcastClipMedia(@RequestHeader("Authorization") String token) {
        try {
            User user = podcastClipService.getUserFromToken(token);
            List<PodcastClipMedia> mediaList = podcastClipService.getUserPodcastClipMedia(user);
            return ResponseEntity.ok(mediaList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to retrieve media: " + e.getMessage()));
        }
    }
}