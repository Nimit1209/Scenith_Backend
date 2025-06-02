package com.example.Scenith.developer.controller;


import com.example.Scenith.developer.service.GlobalElementService;
import com.example.Scenith.dto.ElementDto;
import com.example.Scenith.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/developer")
public class DeveloperController {
    private final GlobalElementService globalElementService;
    private final JwtUtil jwtUtil;

    public DeveloperController(GlobalElementService globalElementService, JwtUtil jwtUtil) {
        this.globalElementService = globalElementService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/elements/upload")
    public ResponseEntity<?> uploadGlobalElements(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "title", required = false) String title, // Ignored
            @RequestParam(value = "type", required = false) String type,   // Ignored
            @RequestParam(value = "category", required = false) String category) { // Ignored
        try {
            String username = jwtUtil.extractEmail(token.replace("Bearer ", ""));
            String role = jwtUtil.extractRole(token.replace("Bearer ", ""));
            if (!"DEVELOPER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: Developer role required");
            }

            // Pass null for unused parameters
            List<ElementDto> elements = globalElementService.uploadGlobalElements(files, null, null, null, username);
            return ResponseEntity.ok(elements);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error uploading elements: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @GetMapping("/elements")

    public ResponseEntity<?> getGlobalElements(
            @RequestHeader("Authorization") String token) {
        try {
            String role = jwtUtil.extractRole(token.replace("Bearer ", ""));
            if (!"DEVELOPER".equals(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied: Developer role required");
            }

            List<ElementDto> elements = globalElementService.getGlobalElements();
            return ResponseEntity.ok(elements);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }
}