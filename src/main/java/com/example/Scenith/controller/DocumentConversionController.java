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

    private final DocumentConversionService documentConversionService;
    private final ObjectMapper objectMapper;

    @Autowired
    public DocumentConversionController(DocumentConversionService documentConversionService, ObjectMapper objectMapper) {
        this.documentConversionService = documentConversionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Merge multiple PDFs with optional page arrangement
     */
    @PostMapping("/merge-pdf")
    public ResponseEntity<?> mergePdfs(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String pageMapping) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();

            // Parse page mapping if provided
            if (pageMapping != null && !pageMapping.isEmpty()) {
                try {
                    List<Map<String, Object>> mappingList = objectMapper.readValue(pageMapping,
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                            ));
                    options.put("pageMapping", mappingList);

                    // Extract unique uploadIds from pageMapping
                    Set<Long> uniqueUploadIds = new HashSet<>();
                    for (Map<String, Object> mapping : mappingList) {
                        Object uploadIdObj = mapping.get("uploadId");
                        if (uploadIdObj instanceof Number) {
                            uniqueUploadIds.add(((Number) uploadIdObj).longValue());
                        }
                    }
                    List<Long> uploadIds = new ArrayList<>(uniqueUploadIds);

                    DocumentConversion result = documentConversionService.mergePdfs(user, uploadIds, options);
                    return ResponseEntity.ok(Map.of(
                            "message", "PDFs merged successfully",
                            "data", result
                    ));
                } catch (Exception e) {
                    logger.warn("Failed to parse pageMapping: {}", e.getMessage());
                    return ResponseEntity.status(400).body(Map.of(
                            "message", "Invalid page mapping format",
                            "error", e.getMessage()
                    ));
                }
            }

            return ResponseEntity.status(400).body(Map.of(
                    "message", "Page mapping is required",
                    "error", "No page mapping provided"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF merge failed",
                    "error", e.getMessage()
            ));
        }
    }


    /**
     * Split PDF - ENHANCED with multiple range support
     */
    @PostMapping("/split-pdf")
    public ResponseEntity<?> splitPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam(required = false) String splitType,
            @RequestParam(required = false) String ranges) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("splitType", splitType != null ? splitType : "all");

            // Parse multiple ranges if provided
            if (ranges != null && !ranges.isEmpty()) {
                try {
                    // Expected format: [{"from":1,"to":4},{"from":8,"to":12}]
                    List<Map<String, Integer>> rangeList = objectMapper.readValue(ranges,
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Integer.class)
                            ));
                    options.put("ranges", rangeList);
                } catch (Exception e) {
                    logger.warn("Failed to parse ranges: {}", e.getMessage());
                    return ResponseEntity.status(400).body(Map.of(
                            "message", "Invalid ranges format",
                            "error", e.getMessage()
                    ));
                }
            }

            DocumentConversion result = documentConversionService.splitPdf(user, uploadId, options);
            return ResponseEntity.ok(Map.of(
                    "message", "PDF split successfully",
                    "data", result
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF split failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Compress PDF - ENHANCED with percentage support
     */
    @PostMapping("/compress-pdf")
    public ResponseEntity<?> compressPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam(required = false, defaultValue = "preset") String compressionMode,
            @RequestParam(required = false, defaultValue = "medium") String compressionLevel,
            @RequestParam(required = false) Integer customPercentage,
            @RequestParam(required = false) String targetFileSizeBytes) {  // Changed to String
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("compressionMode", compressionMode);

            if ("filesize".equals(compressionMode)) {
                // Exact file size compression - Parse String to Long (handle decimals from frontend)
                if (targetFileSizeBytes == null || targetFileSizeBytes.trim().isEmpty()) {
                    return ResponseEntity.status(400).body(Map.of(
                            "message", "Invalid target file size",
                            "error", "Target file size must be provided"
                    ));
                }

                try {
                    // Parse as double first (in case frontend sends decimal), then convert to long
                    double targetSizeDouble = Double.parseDouble(targetFileSizeBytes.trim());
                    long targetSizeLong = (long) Math.floor(targetSizeDouble);  // Round down to nearest byte

                    if (targetSizeLong <= 0) {
                        return ResponseEntity.status(400).body(Map.of(
                                "message", "Invalid target file size",
                                "error", "Target file size must be greater than 0"
                        ));
                    }

                    options.put("targetFileSizeBytes", targetSizeLong);

                } catch (NumberFormatException e) {
                    return ResponseEntity.status(400).body(Map.of(
                            "message", "Invalid target file size format",
                            "error", "Target file size must be a valid number: " + e.getMessage()
                    ));
                }
            }
            else if ("percentage".equals(compressionMode)) {
                // Percentage-based compression
                if (customPercentage == null || customPercentage < 10 || customPercentage > 95) {
                    return ResponseEntity.status(400).body(Map.of(
                            "message", "Invalid compression percentage",
                            "error", "Percentage must be between 10 and 95"
                    ));
                }
                options.put("compressionPercentage", customPercentage);

            } else {
                // Preset compression levels
                String level = compressionLevel != null ? compressionLevel.toLowerCase() : "medium";
                int percentage;
                switch (level) {
                    case "low":
                        percentage = 75;
                        break;
                    case "high":
                        percentage = 25;
                        break;
                    case "medium":
                    default:
                        percentage = 50;
                        level = "medium";
                }
                options.put("compressionLevel", level);
                options.put("compressionPercentage", percentage);
            }

            DocumentConversion result = documentConversionService.compressPdf(user, uploadId, options);
            return ResponseEntity.ok(Map.of(
                    "message", "PDF compressed successfully",
                    "data", result
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF compression failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Rotate PDF pages - simplified with 4 directions
     */
    @PostMapping("/rotate-pdf")
    public ResponseEntity<?> rotatePdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam(defaultValue = "right") String direction,
            @RequestParam(required = false) String pages) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            // Map direction to degrees
            int degrees;
            switch (direction.toLowerCase()) {
                case "right":
                    degrees = 90;
                    break;
                case "left":
                    degrees = -90;
                    break;
                case "top":
                    degrees = 180;
                    break;
                case "bottom":
                    degrees = 180;
                    break;
                default:
                    degrees = 90;
            }

            Map<String, Object> options = new HashMap<>();
            options.put("degrees", degrees);
            options.put("pages", pages != null ? pages : "all");

            DocumentConversion result = documentConversionService.rotatePdf(user, uploadId, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF rotation failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Convert images to PDF with optional page arrangement
     */
    @PostMapping("/images-to-pdf")
    public ResponseEntity<?> imagesToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadIds") List<Long> uploadIds,
            @RequestParam(required = false) String pageOrder) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            if (pageOrder != null && !pageOrder.isEmpty()) {
                try {
                    List<Integer> orderList = objectMapper.readValue(pageOrder,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
                    options.put("pageOrder", orderList);
                } catch (Exception e) {
                    logger.warn("Failed to parse pageOrder: {}", e.getMessage());
                }
            }

            DocumentConversion result = documentConversionService.imagesToPdf(user, uploadIds, options);
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
            @RequestParam("uploadId") Long uploadId) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.extractImagesFromPdf(user, uploadId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Image extraction from PDF failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Add watermark to PDF - simplified
     */
    @PostMapping("/add-watermark")
    public ResponseEntity<?> addWatermarkToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam("watermarkText") String watermarkText) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("watermarkText", watermarkText);
            options.put("opacity", 0.5f);
            options.put("position", "center");

            DocumentConversion result = documentConversionService.addWatermarkToPdf(user, uploadId, options);
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
            @RequestParam("uploadId") Long uploadId,
            @RequestParam("password") String password) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("password", password);

            DocumentConversion result = documentConversionService.unlockPdf(user, uploadId, options);
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
            @RequestParam("uploadId") Long uploadId,
            @RequestParam("password") String password,
            @RequestParam(required = false) String ownerPassword) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("password", password);
            if (ownerPassword != null) {
                options.put("ownerPassword", ownerPassword);
            }

            DocumentConversion result = documentConversionService.lockPdf(user, uploadId, options);
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
     * Rearrange and merge PDFs with custom page ordering
     */
    @PostMapping("/rearrange-merge-pdf")
    public ResponseEntity<?> rearrangeMergePdfs(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadIds") List<Long> uploadIds,
            @RequestParam(required = false) String pageOrder,
            @RequestParam(required = false) String insertions) {
        try {
            User user = documentConversionService.getUserFromToken(token);

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

            // Parse insertions for adding pages from other PDFs
            if (insertions != null && !insertions.isEmpty()) {
                try {
                    List<Map<String, Object>> insertionList = objectMapper.readValue(insertions,
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                            ));
                    options.put("insertions", insertionList);
                } catch (Exception e) {
                    logger.warn("Failed to parse insertions: {}", e.getMessage());
                }
            }

            DocumentConversion result = documentConversionService.rearrangeMergePdfs(user, uploadIds, options);
            return ResponseEntity.ok(Map.of(
                    "message", "PDFs rearranged and merged successfully",
                    "data", result
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF rearrange-merge failed",
                    "error", e.getMessage()
            ));
        }
    }
    /**
     * Get PDF page count for frontend validation
     */
    @GetMapping("/page-count/{uploadId}")
    public ResponseEntity<?> getPdfPageCount(
            @RequestHeader("Authorization") String token,
            @PathVariable Long uploadId) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            int pageCount = documentConversionService.getPdfPageCount(user, uploadId);
            return ResponseEntity.ok(Map.of(
                    "uploadId", uploadId,
                    "pageCount", pageCount
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Failed to get page count",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Convert Word to PDF
     * POST /api/documents/word-to-pdf
     */
    @PostMapping("/word-to-pdf")
    public ResponseEntity<?> convertWordToPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile wordFile) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            // Validate file
            if (wordFile.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of(
                        "message", "No file uploaded",
                        "error", "Please select a Word document (.doc or .docx)"
                ));
            }

            DocumentConversion result = documentConversionService.convertWordToPdf(user, wordFile);
            return ResponseEntity.ok(Map.of(
                    "message", "Word document converted to PDF successfully",
                    "data", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of(
                    "message", "Invalid file type",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Word to PDF conversion failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Word to PDF conversion failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Convert PDF to Word
     * POST /api/documents/pdf-to-word
     */
    @PostMapping("/pdf-to-word")
    public ResponseEntity<?> convertPdfToWord(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId) {
        try {
            User user = documentConversionService.getUserFromToken(token);
            DocumentConversion result = documentConversionService.convertPdfToWord(user, uploadId);

            return ResponseEntity.ok(Map.of(
                    "message", "PDF converted to Word successfully",
                    "data", result,
                    "note", "Best results for text-based PDFs. Scanned PDFs or complex layouts may lose some formatting."
            ));
        } catch (Exception e) {
            logger.error("PDF to Word conversion failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "PDF to Word conversion failed",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Apply OCR to PDF (make scanned PDF searchable)
     * POST /api/documents/ocr-pdf
     */
    @PostMapping("/ocr-pdf")
    public ResponseEntity<?> ocrPdf(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam(required = false, defaultValue = "eng") String language) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            options.put("language", language);

            DocumentConversion result = documentConversionService.ocrPdf(user, uploadId, options);
            return ResponseEntity.ok(Map.of(
                    "message", "OCR applied successfully - PDF is now searchable",
                    "data", result,
                    "languageUsed", language
            ));
        } catch (Exception e) {
            logger.error("OCR failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "OCR processing failed",
                    "error", e.getMessage(),
                    "suggestion", "Make sure the PDF contains clear, readable scanned images"
            ));
        }
    }

    /**
     * Add page numbers to PDF
     * POST /api/documents/add-page-numbers
     */
    @PostMapping("/add-page-numbers")
    public ResponseEntity<?> addPageNumbers(
            @RequestHeader("Authorization") String token,
            @RequestParam("uploadId") Long uploadId,
            @RequestParam(required = false) Integer startPage,
            @RequestParam(required = false) Integer endPage,
            @RequestParam(required = false, defaultValue = "1") Integer startNumber,
            @RequestParam(required = false, defaultValue = "bottom-center") String position,
            @RequestParam(required = false, defaultValue = "10") Integer fontSize,
            @RequestParam(required = false, defaultValue = "true") Boolean removeExisting) {
        try {
            User user = documentConversionService.getUserFromToken(token);

            Map<String, Object> options = new HashMap<>();
            if (startPage != null) options.put("startPage", startPage);
            if (endPage != null) options.put("endPage", endPage);
            options.put("startNumber", startNumber);
            options.put("position", position);
            options.put("fontSize", fontSize);
            options.put("removeExisting", removeExisting);

            DocumentConversion result = documentConversionService.addPageNumbers(user, uploadId, options);
            return ResponseEntity.ok(Map.of(
                    "message", "Page numbers added successfully",
                    "data", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of(
                    "message", "Invalid parameters",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Add page numbers failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "message", "Adding page numbers failed",
                    "error", e.getMessage()
            ));
        }
    }
}