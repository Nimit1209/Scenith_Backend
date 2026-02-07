package com.example.Scenith.controller;

import com.example.Scenith.entity.AspectRatioMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.AspectRatioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/aspect-ratio")
public class AspectRatioController {

    @Autowired
    private AspectRatioService aspectRatioService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile mediaFile) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.uploadMedia(user, mediaFile);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/set-aspect-ratio/{mediaId}")
    public ResponseEntity<?> setAspectRatio(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestBody Map<String, String> requestBody) {
        try {
            String aspectRatio = requestBody.get("aspectRatio");
            if (aspectRatio == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Aspect ratio is required"));
            }
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.setAspectRatio(user, mediaId, aspectRatio);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Aspect ratio setting failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-position-x/{mediaId}")
    public ResponseEntity<?> updatePositionX(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam Integer positionX) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updatePositionX(user, mediaId, positionX);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "PositionX update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-position-y/{mediaId}")
    public ResponseEntity<?> updatePositionY(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam Integer positionY) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updatePositionY(user, mediaId, positionY);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "PositionY update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-scale/{mediaId}")
    public ResponseEntity<?> updateScale(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam Double scale) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updateScale(user, mediaId, scale);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Scale update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-output-width/{mediaId}")
    public ResponseEntity<?> updateOutputWidth(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam Integer width) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updateOutputWidth(user, mediaId, width);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Output width update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-output-height/{mediaId}")
    public ResponseEntity<?> updateOutputHeight(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestParam Integer height) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updateOutputHeight(user, mediaId, height);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Output height update failed: " + e.getMessage()));
        }
    }

    @PutMapping("/update-output-resolution/{mediaId}")
    public ResponseEntity<?> updateOutputResolution(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId,
            @RequestBody Map<String, Integer> requestBody) {
        try {
            Integer width = requestBody.get("width");
            Integer height = requestBody.get("height");

            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.updateOutputResolution(user, mediaId, width, height);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Output resolution update failed: " + e.getMessage()));
        }
    }

    @PostMapping("/process/{mediaId}")
    public ResponseEntity<?> processAspectRatio(
            @RequestHeader("Authorization") String token,
            @PathVariable Long mediaId) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            AspectRatioMedia result = aspectRatioService.initiateProcessing(user, mediaId);
            return ResponseEntity.ok(Map.of(
                    "message", "Processing initiated successfully",
                    "media", result
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Aspect ratio processing initiation failed: " + e.getMessage()));
        }
    }

    @GetMapping("/user-media")
    public ResponseEntity<?> getUserAspectRatioMedia(@RequestHeader("Authorization") String token) {
        try {
            User user = aspectRatioService.getUserFromToken(token);
            List<AspectRatioMedia> mediaList = aspectRatioService.getUserAspectRatioMedia(user);
            return ResponseEntity.ok(mediaList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to retrieve media: " + e.getMessage()));
        }
    }
}