package com.example.Scenith.controller;

import com.example.Scenith.entity.CompressedMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.CompressionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/compression")
public class CompressionController {

    @Autowired
    private CompressionService compressionService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile mediaFile,
            @RequestParam("targetSize") String targetSize
    ) {
        try {
            User user = compressionService.getUserFromToken(token);
            CompressedMedia result = compressionService.uploadMedia(user, mediaFile, targetSize);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/compress/{mediaId}")
    public ResponseEntity<?> compressMedia(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId
    ) {
        try {
            User user = compressionService.getUserFromToken(token);
            CompressedMedia result = compressionService.compressMedia(user, mediaId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Compression failed: " + e.getMessage()));
        }
    }

    @GetMapping("/user-media")
    public ResponseEntity<?> getUserCompressedMedia(@RequestHeader("Authorization") String token) {
        try {
            User user = compressionService.getUserFromToken(token);
            List<CompressedMedia> mediaList = compressionService.getUserCompressedMedia(user);
            return ResponseEntity.ok(mediaList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to retrieve media: " + e.getMessage()));
        }
    }

    @PatchMapping("/update-target-size/{mediaId}")
    public ResponseEntity<?> updateTargetSize(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam("targetSize") String targetSize
    ) {
        try {
            User user = compressionService.getUserFromToken(token);
            CompressedMedia result = compressionService.updateTargetSize(user, mediaId, targetSize);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to update target size: " + e.getMessage()));
        }
    }
}