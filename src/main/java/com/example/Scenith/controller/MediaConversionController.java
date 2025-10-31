package com.example.Scenith.controller;

import com.example.Scenith.entity.ConvertedMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.MediaConversionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversion")
public class MediaConversionController {

    @Autowired
    private MediaConversionService mediaConversionService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile mediaFile,
            @RequestParam("targetFormat") String targetFormat) {
        try {
            User user = mediaConversionService.getUserFromToken(token);
            ConvertedMedia result = mediaConversionService.uploadMedia(user, mediaFile, targetFormat);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/convert/{mediaId}")
    public ResponseEntity<?> convertMedia(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId) {
        try {
            User user = mediaConversionService.getUserFromToken(token);
            ConvertedMedia result = mediaConversionService.convertMedia(user, mediaId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Conversion failed: " + e.getMessage()));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @PutMapping("/update-target-format/{mediaId}")
    public ResponseEntity<?> updateTargetFormat(
        @RequestHeader("Authorization") String token,
        @PathVariable Long mediaId,
        @RequestParam("newTargetFormat") String newTargetFormat) {
        try {
            User user = mediaConversionService.getUserFromToken(token);
            ConvertedMedia result = mediaConversionService.updateTargetFormat(user, mediaId, newTargetFormat);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to update target format: " + e.getMessage()));
        }
    }

    @GetMapping("/user-media")
    public ResponseEntity<?> getUserConvertedMedia(@RequestHeader("Authorization") String token) {
        try {
            User user = mediaConversionService.getUserFromToken(token);
            List<ConvertedMedia> mediaList = mediaConversionService.getUserConvertedMedia(user);
            return ResponseEntity.ok(mediaList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to retrieve media: " + e.getMessage()));
        }
    }
}