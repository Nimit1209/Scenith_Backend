package com.example.Scenith.controller;

import com.example.Scenith.dto.SubtitleDTO;
import com.example.Scenith.entity.SubtitleMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.SubtitleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subtitles")
public class SubtitleController {

    @Autowired
    private SubtitleService subtitleService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile mediaFile) {
        try {
            User user = subtitleService.getUserFromToken(token);
            SubtitleMedia result = subtitleService.uploadMedia(user, mediaFile);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/generate/{mediaId}")
    public ResponseEntity<?> generateSubtitles(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestBody(required = false) Map<String, String> styleParams) {
        try {
            User user = subtitleService.getUserFromToken(token);
            subtitleService.generateSubtitles(user, mediaId, styleParams); // Updated method call
            return ResponseEntity.ok(Map.of("message", "Subtitle generation queued successfully for mediaId: " + mediaId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Subtitle generation queueing failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update/{mediaId}")
    public ResponseEntity<?> updateMultipleSubtitles(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestBody List<SubtitleDTO> subtitles) {
        try {
            User user = subtitleService.getUserFromToken(token);
            SubtitleMedia result = subtitleService.updateMultipleSubtitles(user, mediaId, subtitles);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Subtitle update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update/{mediaId}/{subtitleId}")
    public ResponseEntity<?> updateSingleSubtitle(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @PathVariable String subtitleId,
            @RequestBody SubtitleDTO subtitle) {
        try {
            User user = subtitleService.getUserFromToken(token);
            SubtitleMedia result = subtitleService.updateSingleSubtitle(user, mediaId, subtitleId, subtitle);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Subtitle update failed: " + e.getMessage()));
        }
    }

    @PostMapping("/process/{mediaId}")
    public ResponseEntity<?> processSubtitles(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId) {
        try {
            User user = subtitleService.getUserFromToken(token);
            subtitleService.queueProcessSubtitles(user, mediaId); // Updated method call
            return ResponseEntity.ok(Map.of("message", "Subtitle processing queued successfully for mediaId: " + mediaId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Subtitle processing queueing failed: " + e.getMessage()));
        }
    }

    @GetMapping("/user-media")
    public ResponseEntity<?> getUserSubtitleMedia(@RequestHeader("Authorization") String token) {
        try {
            User user = subtitleService.getUserFromToken(token);
            List<SubtitleMedia> mediaList = subtitleService.getUserSubtitleMedia(user);
            return ResponseEntity.ok(mediaList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to retrieve media: " + e.getMessage()));
        }
    }
}