package com.example.Scenith.controller;

import com.example.Scenith.entity.DocumentUpload;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.DocumentUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents/uploads")
public class DocumentUploadController {

    @Autowired
    private DocumentUploadService documentUploadService;

    /**
     * Upload single or multiple documents
     */
    @PostMapping
    public ResponseEntity<?> uploadDocuments(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> files) {
        try {
            User user = documentUploadService.getUserFromToken(token);
            List<DocumentUpload> uploads = documentUploadService.uploadDocuments(user, files);
            return ResponseEntity.ok(Map.of(
                    "message", "Files uploaded successfully",
                    "uploads", uploads,
                    "count", uploads.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "File upload failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get user's uploaded documents
     */
    @GetMapping
    public ResponseEntity<?> getUserDocuments(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String fileType) {
        try {
            User user = documentUploadService.getUserFromToken(token);
            List<DocumentUpload> uploads = documentUploadService.getUserDocuments(user, fileType);
            return ResponseEntity.ok(Map.of(
                    "uploads", uploads,
                    "count", uploads.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to fetch documents",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Delete uploaded document
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = documentUploadService.getUserFromToken(token);
            documentUploadService.deleteDocument(user, id);
            return ResponseEntity.ok(Map.of("message", "Document deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to delete document",
                    "error", e.getMessage()
            ));
        }
    }
}