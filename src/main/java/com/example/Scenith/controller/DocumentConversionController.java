package com.example.Scenith.controller;

import com.example.Scenith.entity.DocumentConversion;
import com.example.Scenith.entity.User;
import com.example.Scenith.service.DocumentConversionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/documents")
public class DocumentConversionController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentConversionService.class);

    @Autowired
    private DocumentConversionService documentConversionService;
    @Autowired
    private ObjectMapper objectMapper;

// ============================================================================
// ADD/UPDATE THESE METHODS IN DocumentConversionController.java
// ============================================================================
//
//    /**
//     * Convert Word to PDF - NOT AVAILABLE (UPDATED)
//     * Replace the existing convertWordToPdf method
//     */
//    @PostMapping("/word-to-pdf")
//    public ResponseEntity<?> convertWordToPdf(
//            @RequestHeader("Authorization") String token,
//            @RequestParam("file") MultipartFile wordFile) {
//        try {
//            User user = documentConversionService.getUserFromToken(token);
//            DocumentConversion result = documentConversionService.convertWordToPdf(user, wordFile);
//            return ResponseEntity.status(501).body(Map.of(
//                    "message", "Word to PDF conversion is not available",
//                    "error", "This feature requires LibreOffice which is not installed. Please convert your Word document to PDF using Microsoft Word, Google Docs, or an online converter before uploading.",
//                    "status", "NOT_IMPLEMENTED",
//                    "availableOperations", Arrays.asList(
//                            "PDF to Word",
//                            "Merge PDFs",
//                            "Split PDF",
//                            "Compress PDF",
//                            "Rotate PDF",
//                            "Images to PDF",
//                            "PDF to Images",
//                            "Add Watermark",
//                            "Lock/Unlock PDF",
//                            "Rearrange PDF Pages"
//                    )
//            ));
//        } catch (Exception e) {
//            return ResponseEntity.status(501).body(Map.of(
//                    "message", "Word to PDF conversion not available",
//                    "error", e.getMessage()
//            ));
//        }
//    }
//
//    /**
//     * Convert PDF to Word - Enhanced (UPDATED)
//     * Replace the existing convertPdfToWord method
//     */
//    @PostMapping("/pdf-to-word")
//    public ResponseEntity<?> convertPdfToWord(
//            @RequestHeader("Authorization") String token,
//            @RequestParam("file") MultipartFile pdfFile) {
//        try {
//            User user = documentConversionService.getUserFromToken(token);
//            DocumentConversion result = documentConversionService.convertPdfToWord(user, pdfFile);
//            return ResponseEntity.ok(Map.of(
//                    "message", "PDF to Word conversion completed successfully",
//                    "data", result,
//                    "note", "Formatting may differ from original PDF. Complex layouts, images, and tables may not be preserved."
//            ));
//        } catch (Exception e) {
//            return ResponseEntity.status(500).body(Map.of(
//                    "message", "PDF to Word conversion failed",
//                    "error", e.getMessage(),
//                    "suggestion", "Make sure your PDF contains extractable text content"
//            ));
//        }
//    }

    /**
     * Merge multiple PDFs with optional page arrangement (UPDATED)
     * Replace the existing mergePdfs method
     */
    @PostMapping("/merge-pdf")
    public ResponseEntity<?> mergePdfs(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> pdfFiles,
            @RequestParam(required = false) String pageOrder) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            if (pageOrder != null && !pageOrder.isEmpty()) {
                // Parse page order JSON array: e.g., "[0,2,1,3]"
                try {
                    List<Integer> orderList = objectMapper.readValue(pageOrder,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
                    options.put("pageOrder", orderList);
                } catch (Exception e) {
                    logger.warn("Failed to parse pageOrder: {}", e.getMessage());
                }
            }

            DocumentConversion result = documentConversionService.mergePdfs(user, pdfFiles, options);
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
     * Convert images to PDF with optional page arrangement (UPDATED)
     * Replace the existing imagesToPdf method
     */
    @PostMapping("/images-to-pdf")
    public ResponseEntity<?> imagesToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("files") List<MultipartFile> imageFiles,
            @RequestParam(required = false) String pageOrder) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            if (pageOrder != null && !pageOrder.isEmpty()) {
                // Parse page order JSON array
                try {
                    List<Integer> orderList = objectMapper.readValue(pageOrder,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
                    options.put("pageOrder", orderList);
                } catch (Exception e) {
                    logger.warn("Failed to parse pageOrder: {}", e.getMessage());
                }
            }

            DocumentConversion result = documentConversionService.imagesToPdf(user, imageFiles, options);
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
    /**
     * Rearrange PDF pages with optional insertions (FIXED)
     */
    @PostMapping("/rearrange-pdf")
    public ResponseEntity<?> rearrangePdfPages(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile pdfFile,
            @RequestParam(required = false) String pageOrder,
            @RequestParam(required = false) List<MultipartFile> insertFiles,
            @RequestParam(required = false) String insertPositions,
            @RequestParam(required = false) String insertTypes) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            // Build options
            Map<String, Object> options = new HashMap<>();

            // Parse page order
            if (pageOrder != null && !pageOrder.isEmpty()) {
                try {
                    List<Integer> orderList = objectMapper.readValue(pageOrder,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
                    options.put("pageOrder", orderList);
                } catch (Exception e) {
                    logger.warn("Failed to parse pageOrder: {}", e.getMessage());
                }
            }

            // Handle insertions if provided - DON'T PUT FILES IN OPTIONS!
            if (insertFiles != null && !insertFiles.isEmpty()) {
                // Parse insert positions
                List<Integer> positions = new ArrayList<>();
                if (insertPositions != null && !insertPositions.isEmpty()) {
                    try {
                        positions = objectMapper.readValue(insertPositions,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
                    } catch (Exception e) {
                        logger.warn("Failed to parse insertPositions: {}", e.getMessage());
                    }
                }

                // Parse insert types
                List<String> types = new ArrayList<>();
                if (insertTypes != null && !insertTypes.isEmpty()) {
                    try {
                        types = objectMapper.readValue(insertTypes,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (Exception e) {
                        logger.warn("Failed to parse insertTypes: {}", e.getMessage());
                    }
                }

                // Store metadata only (not the actual files)
                List<Map<String, Object>> insertionMetadata = new ArrayList<>();
                for (int i = 0; i < insertFiles.size(); i++) {
                    Map<String, Object> insertion = new HashMap<>();
                    insertion.put("position", i < positions.size() ? positions.get(i) : i);
                    insertion.put("type", i < types.size() ? types.get(i) : "image");
                    insertion.put("fileName", insertFiles.get(i).getOriginalFilename());
                    insertionMetadata.add(insertion);
                }

                options.put("insertions", insertionMetadata);
                // NOTE: Don't put insertFiles in options - pass separately!
            }

            // Pass insertFiles separately to the service method
            DocumentConversion result = documentConversionService.rearrangePdfPages(
                    user, pdfFile, insertFiles, options);

            return ResponseEntity.ok(Map.of(
                    "message", "PDF pages rearranged successfully",
                    "data", result
            ));
        } catch (Exception e) {
            logger.error("PDF rearrangement failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF rearrangement failed",
                    "error", e.getMessage()
            ));
        }
    }
}