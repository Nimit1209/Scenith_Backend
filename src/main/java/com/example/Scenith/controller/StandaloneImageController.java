package com.example.Scenith.controller;

import com.example.Scenith.entity.StandaloneImage;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.StandaloneImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
public class StandaloneImageController {

  @Autowired
  private StandaloneImageService standaloneImageService;

  @PostMapping("/api/standalone-images/remove-background")
  public ResponseEntity<?> removeBackground(
      @RequestHeader("Authorization") String token,
      @RequestParam("image") MultipartFile imageFile
  ) {
    try {
      User user = standaloneImageService.getUserFromToken(token);
      StandaloneImage result = standaloneImageService.processStandaloneImageBackgroundRemoval(user, imageFile);
      return ResponseEntity.ok(result);
    } catch (IOException | InterruptedException e) {
      e.printStackTrace(); // ✅ Log full stack trace in backend logs
      return ResponseEntity.status(500).body(Map.of(
          "message", e.getMessage()
      ));
    }
  }


  @GetMapping("/api/standalone-images/user-images")
  public ResponseEntity<List<StandaloneImage>> getUserImages(@RequestHeader("Authorization") String token) {
    try {
      User user = standaloneImageService.getUserFromToken(token);
      List<StandaloneImage> images = standaloneImageService.getUserImages(user);
      return ResponseEntity.ok(images);
    } catch (Exception e) {
      return ResponseEntity.status(500).body(null);
    }
  }

  public String determineContentType(String filename) {
    filename = filename.toLowerCase();
    if (filename.endsWith(".png")) return "image/png";
    if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
    if (filename.endsWith(".gif")) return "image/gif";
    if (filename.endsWith(".webp")) return "image/webp";
    return "application/octet-stream"; // Default fallback
  }

}