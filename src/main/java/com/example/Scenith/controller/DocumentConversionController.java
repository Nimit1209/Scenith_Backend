package com.example.Scenith.controller;

import com.example.Scenith.entity.DocumentConversion;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.DocumentConversionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentConversionController {

    @Autowired
    private DocumentConversionService documentConversionService;

    /**
     * Convert Word to PDF
     */
    @PostMapping("/word-to-pdf")
    public ResponseEntity<?> convertWordToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile wordFile) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.convertWordToPdf(user, wordFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Word to PDF conversion failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Convert PDF to Word
     */
    @PostMapping("/pdf-to-word")
    public ResponseEntity<?> convertPdfToWord(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.convertPdfToWord(user, pdfFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF to Word conversion failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Merge multiple PDFs
     */
    @PostMapping("/merge-pdf")
    public ResponseEntity<?> mergePdfs(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> pdfFiles) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.mergePdfs(user, pdfFiles);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF merge failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Split PDF
     */
    @PostMapping("/split-pdf")
    public ResponseEntity<?> splitPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam(required = false) String splitType,
            @RequestParam(required = false) String pages) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            
            Map<String, Object> options = new HashMap<>();
            options.put("splitType", splitType != null ? splitType : "all"); // all, range, extract
            if (pages != null) {
                options.put("pages", pages); // e.g., "1-5,7,9-12"
            }
            
            DocumentConversion result = documentConversionService.splitPdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF split failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Compress PDF
     */
    @PostMapping("/compress-pdf")
    public ResponseEntity<?> compressPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam(required = false, defaultValue = "medium") String compressionLevel) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            
            Map<String, Object> options = new HashMap<>();
            options.put("compressionLevel", compressionLevel); // low, medium, high
            
            DocumentConversion result = documentConversionService.compressPdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF compression failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Rotate PDF pages
     */
    @PostMapping("/rotate-pdf")
    public ResponseEntity<?> rotatePdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam(defaultValue = "90") int degrees,
            @RequestParam(required = false) String pages) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            
            Map<String, Object> options = new HashMap<>();
            options.put("degrees", degrees); // 90, 180, 270
            options.put("pages", pages != null ? pages : "all"); // all, or specific pages like "1,3,5"
            
            DocumentConversion result = documentConversionService.rotatePdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF rotation failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Convert images to PDF
     */
    @PostMapping("/images-to-pdf")
    public ResponseEntity<?> imagesToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> imageFiles) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.imagesToPdf(user, imageFiles);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Images to PDF conversion failed",
                    "error", e.getMessage()
            ));
        }
    }
    /**
     * Extract images from PDF
     */
    @PostMapping("/pdf-to-images")
    public ResponseEntity<?> extractImagesFromPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.extractImagesFromPdf(user, pdfFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Image extraction from PDF failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Add watermark to PDF
     */
    @PostMapping("/add-watermark")
    public ResponseEntity<?> addWatermarkToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam("watermarkText") String watermarkText,
            @RequestParam(required = false, defaultValue = "0.3") float opacity,
            @RequestParam(required = false, defaultValue = "center") String position) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("watermarkText", watermarkText);
            options.put("opacity", opacity);
            options.put("position", position); // center, top-left, top-right, bottom-left, bottom-right

            DocumentConversion result = documentConversionService.addWatermarkToPdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Adding watermark failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Unlock PDF (remove password)
     */
    @PostMapping("/unlock-pdf")
    public ResponseEntity<?> unlockPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam("password") String password) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("password", password);

            DocumentConversion result = documentConversionService.unlockPdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Unlocking PDF failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Lock PDF (add password)
     */
    @PostMapping("/lock-pdf")
    public ResponseEntity<?> lockPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam("password") String password,
            @RequestParam(required = false) String ownerPassword) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("password", password);
            if (ownerPassword != null) {
                options.put("ownerPassword", ownerPassword);
            }

            DocumentConversion result = documentConversionService.lockPdf(user, pdfFile, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Locking PDF failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get user's conversion history
     */
    @GetMapping("/history")
    public ResponseEntity<?> getUserConversions(@RequestHeader("Authorization") String token) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            List<DocumentConversion> conversions = documentConversionService.getUserConversions(user);
            return ResponseEntity.ok(conversions);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to fetch conversion history",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get specific conversion by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getConversionById(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion conversion = documentConversionService.getConversionById(user, id);
            return ResponseEntity.ok(conversion);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to fetch conversion",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Delete conversion
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteConversion(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            documentConversionService.deleteConversion(user, id);
            return ResponseEntity.ok(Map.of("message", "Conversion deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to delete conversion",
                    "error", e.getMessage()
            ));
        }
    }
}