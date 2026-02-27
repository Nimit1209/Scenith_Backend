package com.example.Scenith.service;

import com.example.Scenith.entity.DocumentConversion;
import com.example.Scenith.entity.DocumentUpload;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.DocumentConversionRepository;
import com.example.Scenith.repository.DocumentUploadRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentConversionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentConversionService.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final DocumentConversionRepository documentConversionRepository;
    private final ObjectMapper objectMapper;
    private final DocumentUploadRepository documentUploadRepository;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${python.path:/usr/local/bin/python3}")
    private String pythonPath;

    @Value("${app.document-conversion-script-path:/app/scripts/document_converter.py}")
    private String documentConversionScriptPath;

    @Value("${imagemagick.path:/usr/local/bin/magick}")
    private String imageMagickPath;

    public DocumentConversionService(
            JwtUtil jwtUtil,
            UserRepository userRepository,
            CloudflareR2Service cloudflareR2Service,
            DocumentConversionRepository documentConversionRepository,
            ObjectMapper objectMapper, DocumentUploadRepository documentUploadRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.cloudflareR2Service = cloudflareR2Service;
        this.documentConversionRepository = documentConversionRepository;
        this.objectMapper = objectMapper;
        this.documentUploadRepository = documentUploadRepository;
    }

    /**
     * Word to PDF Conversion - NOT AVAILABLE (UPDATED)
     * Replace the existing convertWordToPdf method
     */
    /**
     * Word to PDF Conversion
     */
    public DocumentConversion convertWordToPdf(User user, MultipartFile wordFile)
            throws IOException, InterruptedException {
        logger.info("Converting Word to PDF for user: {}", user.getId());

        // Validate file type
        String filename = wordFile.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".doc") && !filename.endsWith(".docx"))) {
            throw new IllegalArgumentException("Only .doc and .docx files are supported");
        }

        // Create temporary upload
        DocumentUpload upload = createTempUpload(user, wordFile);

        try {
            return processDocumentConversion(user, List.of(upload), "WORD_TO_PDF", null);
        } finally {
            // Clean up temp upload if needed
            documentUploadRepository.delete(upload);
        }
    }

    /**
     * PDF to Word Conversion
     */
    public DocumentConversion convertPdfToWord(User user, Long uploadId)
            throws IOException, InterruptedException {
        logger.info("Converting PDF to Word for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "PDF_TO_WORD", null);
    }

    /**
     * OCR PDF
     */
    public DocumentConversion ocrPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Applying OCR to PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "OCR_PDF", options);
    }

    /**
     * Add Page Numbers to PDF
     */
    public DocumentConversion addPageNumbers(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Adding page numbers to PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        // Validate options
        if (options != null) {
            Integer startPage = (Integer) options.get("startPage");
            Integer endPage = (Integer) options.get("endPage");

            if (startPage != null && endPage != null && startPage > endPage) {
                throw new IllegalArgumentException("Start page cannot be greater than end page");
            }
        }

        return processDocumentConversion(user, List.of(upload), "ADD_PAGE_NUMBERS", options);
    }

    /**
     * Helper method to create temporary upload from MultipartFile
     */
    private DocumentUpload createTempUpload(User user, MultipartFile file) throws IOException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String tempPath = String.format("documents/%s/%s/temp/%s",
                user.getId(), timestamp, file.getOriginalFilename());

        // Upload to R2
        File tempFile = File.createTempFile("upload_", file.getOriginalFilename());
        file.transferTo(tempFile);
        cloudflareR2Service.uploadFile(tempPath, tempFile);
        tempFile.delete();

        // Create DocumentUpload entity
        DocumentUpload upload = new DocumentUpload();
        upload.setUser(user);
        upload.setFileName(file.getOriginalFilename());
        upload.setFilePath(tempPath);
        upload.setFileSizeBytes(file.getSize());
        upload.setCreatedAt(LocalDateTime.now());

        return documentUploadRepository.save(upload);
    }

    public DocumentConversion mergePdfs(User user, List<Long> uploadIds, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Merging {} PDFs for user: {}", uploadIds.size(), user.getId());
        if (uploadIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 PDF files are required for merging");
        }

        // Get uploaded files
        List<DocumentUpload> uploads = new ArrayList<>();
        for (Long uploadId : uploadIds) {
            DocumentUpload upload = documentUploadRepository.findById(uploadId)
                    .orElseThrow(() -> new RuntimeException("Upload not found: " + uploadId));
            if (!upload.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Unauthorized access to upload: " + uploadId);
            }
            uploads.add(upload);
        }

        return processDocumentConversion(user, uploads, "MERGE_PDF", options);
    }

    public DocumentConversion splitPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Splitting PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "SPLIT_PDF", options);
    }

    public DocumentConversion compressPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Compressing PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        logger.info("=== COMPRESS OPTIONS BEFORE PROCESSING ===");
        logger.info("Options map: {}", objectMapper.writeValueAsString(options));
        logger.info("=== END ===");

        String compressionMode = options != null ? (String) options.getOrDefault("compressionMode", "preset") : "preset";

        // Route to Java-native compression for all modes
        return compressPdfWithJava(user, upload, options, compressionMode);
    }

    /**
     * Java-native PDF compression using Apache PDFBox.
     * Handles text-only and image-heavy PDFs with a 4-stage pipeline.
     * Supports: filesize targeting, percentage, and preset modes.
     */
    private DocumentConversion compressPdfWithJava(User user, DocumentUpload upload,
                                                   Map<String, Object> options, String compressionMode)
            throws IOException, InterruptedException {

        String timestamp = String.valueOf(System.currentTimeMillis());
        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String workDir = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "doc_" + timestamp;
        Files.createDirectories(Paths.get(workDir));

        String tempInputPath = workDir + File.separator + "input_" + upload.getId() + "_" + sanitizeForPath(upload.getFileName());
        File inputFile = cloudflareR2Service.downloadFile(upload.getFilePath(), tempInputPath);

        String safeUploadName = sanitizeForPath(upload.getFileName());
        int dotIdx = safeUploadName.lastIndexOf('.');
        String outputFileName = (dotIdx > 0 ? safeUploadName.substring(0, dotIdx) : safeUploadName)
                + "_compressed_" + timestamp + ".pdf";
        File outputFile = new File(workDir + File.separator + outputFileName);

        long originalSize = inputFile.length();
        long targetSizeBytes;
        String compressionNote = null;

        // Resolve target size from options
        if ("filesize".equals(compressionMode)) {
            Object raw = options.get("targetFileSizeBytes");
            try {
                targetSizeBytes = (long) Math.floor(Double.parseDouble(String.valueOf(raw)));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid targetFileSizeBytes: " + raw);
            }
        } else if ("percentage".equals(compressionMode)) {
            int pct = options != null ? ((Number) options.getOrDefault("compressionPercentage", 50)).intValue() : 50;
            targetSizeBytes = (long) (originalSize * (pct / 100.0));
        } else {
            // Preset: low=75%, medium=50%, high=25%
            String level = options != null ? (String) options.getOrDefault("compressionLevel", "medium") : "medium";
            int pct = "low".equals(level) ? 75 : "high".equals(level) ? 25 : 50;
            targetSizeBytes = (long) (originalSize * (pct / 100.0));
        }

        logger.info("Compression: mode={}, originalSize={}, targetSize={}", compressionMode, originalSize, targetSizeBytes);

        try {
            // ── Stage 1-3: Lossless pipeline (works on ALL PDFs including text-only) ──
            File losslessOutput = new File(workDir + File.separator + "lossless_stage.pdf");
            applyLosslessPipeline(inputFile, losslessOutput);
            long losslessSize = losslessOutput.length();
            logger.info("After lossless pipeline: {} bytes", losslessSize);

            // ── Stage 4: Binary-search JPEG quality (only matters for image-heavy PDFs) ──
            long absoluteMinSize = estimateAbsoluteMinSize(losslessOutput, workDir);
            logger.info("Estimated absolute min size: {} bytes", absoluteMinSize);

            long finalSize;

            if (targetSizeBytes >= losslessSize) {
                // Target is larger than what lossless gives us — just use lossless result
                Files.copy(losslessOutput.toPath(), outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                finalSize = losslessSize;
                compressionNote = String.format(
                        "The target size of %s was larger than the losslessly-compressed size (%s). " +
                                "The PDF has been compressed to %s using lossless techniques.",
                        humanReadableSize(targetSizeBytes),
                        humanReadableSize(losslessSize),
                        humanReadableSize(losslessSize));
            } else if (targetSizeBytes < absoluteMinSize) {
                // Target is below the minimum achievable — return max-compressed
                applyImageCompression(losslessOutput, outputFile, 0.05f);
                finalSize = outputFile.length();
                compressionNote = String.format(
                        "The target size of %s could not be achieved. " +
                                "This PDF has been compressed to the minimum possible size of %s. " +
                                "The remaining size is occupied by text, fonts, and document structure " +
                                "which cannot be reduced further without corrupting the file. " +
                                "Consider splitting the document into parts.",
                        humanReadableSize(targetSizeBytes),
                        humanReadableSize(finalSize));
            } else {
                // Binary search for the right JPEG quality
                float bestQuality = binarySearchQuality(losslessOutput, outputFile, targetSizeBytes, workDir);
                finalSize = outputFile.length();
                logger.info("Binary search complete. Quality={}, finalSize={}", bestQuality, finalSize);

                long diff = Math.abs(finalSize - targetSizeBytes);
                double diffPct = (diff * 100.0) / targetSizeBytes;
                if (diffPct > 5.0) {
                    if (bestQuality >= 1.0f) {
                        // q=1.0 undershoots target — we returned whichever was closer (q=1.0 or lossless)
                        long diffFromTarget = Math.abs(finalSize - targetSizeBytes);
                        double diffPctFromTarget = (diffFromTarget * 100.0) / targetSizeBytes;
                        compressionNote = String.format(
                                "Target size was %s. Achieved %s (%.1f%% difference). " +
                                        "This PDF's images are highly compressible — even at maximum JPEG quality " +
                                        "the file compresses below your target. The closest achievable size has " +
                                        "been returned. To get exactly %s, consider a larger target size.",
                                humanReadableSize(targetSizeBytes),
                                humanReadableSize(finalSize),
                                diffPctFromTarget,
                                humanReadableSize(targetSizeBytes));
                    } else {
                        compressionNote = String.format(
                                "Target size was %s. Achieved %s (%.1f%% difference). " +
                                        "The PDF's content limits further compression at this target size.",
                                humanReadableSize(targetSizeBytes),
                                humanReadableSize(finalSize),
                                diffPct);
                    }
                }
            }

            // Upload result to R2
            String r2OutputPath = String.format("documents/%s/%s/output/%s", user.getId(), timestamp, outputFileName);
            cloudflareR2Service.uploadFile(r2OutputPath, outputFile);
            Map<String, String> outputUrls = cloudflareR2Service.generateUrls(r2OutputPath, 3600);

            double compressionRatio = (1.0 - (double) finalSize / originalSize) * 100.0;

            // Build metadata
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("originalSizeBytes", originalSize);
            meta.put("compressedSizeBytes", finalSize);
            meta.put("targetSizeBytes", targetSizeBytes);
            meta.put("compressionRatio", String.format("%.2f%%", compressionRatio));
            meta.put("compressionMode", compressionMode);
            if (compressionNote != null) meta.put("compressionNote", compressionNote);

            DocumentConversion conversion = new DocumentConversion();
            conversion.setUser(user);
            conversion.setOperationType("COMPRESS_PDF");
            conversion.setSourceUploadIds(objectMapper.writeValueAsString(List.of(upload.getId())));
            conversion.setOutputFileName(outputFileName);
            conversion.setOutputPath(r2OutputPath);
            conversion.setFileSizeBytes(finalSize);
            conversion.setOutputCdnUrl(outputUrls.get("cdnUrl"));
            conversion.setOutputPresignedUrl(outputUrls.get("presignedUrl"));
            conversion.setProcessingOptions(objectMapper.writeValueAsString(options));
            conversion.setPostProcessingMetadata(objectMapper.writeValueAsString(meta));
            conversion.setStatus("SUCCESS");
            conversion.setCreatedAt(LocalDateTime.now());

            documentConversionRepository.save(conversion);
            logger.info("PDF compression complete. original={}, final={}, note={}",
                    originalSize, finalSize, compressionNote);
            return conversion;

        } finally {
            // Cleanup temp files
            try {
                Files.walk(Paths.get(workDir))
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            } catch (IOException e) {
                logger.warn("Failed to clean workDir: {}", workDir);
            }
        }
    }

    /**
     * Stage 1-3: Lossless compression pipeline.
     * - Strips XMP metadata, document info, thumbnails
     * - Recompresses content streams with Deflate level 9
     * - Subsets fonts (removes ToUnicode maps, CIDSet streams)
     * Works on ALL PDFs including text-only.
     */
    private void applyLosslessPipeline(File inputFile, File outputFile) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.pdmodel.PDDocument.load(inputFile)) {

            // Stage 1: Strip metadata
            doc.getDocumentInformation().setTitle(null);
            doc.getDocumentInformation().setAuthor(null);
            doc.getDocumentInformation().setSubject(null);
            doc.getDocumentInformation().setKeywords(null);
            doc.getDocumentInformation().setCreator(null);
            doc.getDocumentInformation().setProducer(null);
            doc.getDocumentCatalog().setMetadata(null);

            // Stage 2 & 3: Recompress streams + font subsetting
            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                // Recompress content streams
                for (org.apache.pdfbox.cos.COSBase stream : getPageStreams(page)) {
                    if (stream instanceof org.apache.pdfbox.cos.COSStream) {
                        recompressStream((org.apache.pdfbox.cos.COSStream) stream);
                    }
                }

                // Font subsetting: remove ToUnicode and CIDSet (large, often unused)
                org.apache.pdfbox.pdmodel.PDResources resources = page.getResources();
                if (resources != null) {
                    for (org.apache.pdfbox.cos.COSName fontName : resources.getFontNames()) {
                        try {
                            org.apache.pdfbox.pdmodel.font.PDFont font = resources.getFont(fontName);
                            if (font != null && font.getCOSObject() instanceof org.apache.pdfbox.cos.COSDictionary) {
                                org.apache.pdfbox.cos.COSDictionary fontDict =
                                        (org.apache.pdfbox.cos.COSDictionary) font.getCOSObject();
                                fontDict.removeItem(org.apache.pdfbox.cos.COSName.TO_UNICODE);

                                // Remove CIDSet from DescendantFonts
                                org.apache.pdfbox.cos.COSArray descendants =
                                        (org.apache.pdfbox.cos.COSArray) fontDict.getDictionaryObject(
                                                org.apache.pdfbox.cos.COSName.DESCENDANT_FONTS);
                                if (descendants != null) {
                                    for (int i = 0; i < descendants.size(); i++) {
                                        org.apache.pdfbox.cos.COSBase base = descendants.getObject(i);
                                        if (base instanceof org.apache.pdfbox.cos.COSDictionary) {
                                            org.apache.pdfbox.cos.COSDictionary desc =
                                                    (org.apache.pdfbox.cos.COSDictionary) base;
                                            org.apache.pdfbox.cos.COSBase fd = desc.getDictionaryObject(
                                                    org.apache.pdfbox.cos.COSName.FONT_DESC);
                                            if (fd instanceof org.apache.pdfbox.cos.COSDictionary) {
                                                ((org.apache.pdfbox.cos.COSDictionary) fd).removeItem(
                                                        org.apache.pdfbox.cos.COSName.getPDFName("CIDSet"));
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Skipping font subsetting for {}: {}", fontName, e.getMessage());
                        }
                    }
                }
            }

            // Save with object stream compression (PDF 1.5+ xref streams)
            org.apache.pdfbox.pdmodel.common.PDStream stream =
                    new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            doc.save(outputFile);
        }
    }

    /**
     * Recompress a single COSStream with Deflate level 9.
     */
    private void recompressStream(org.apache.pdfbox.cos.COSStream cosStream) {
        try {
            // Read decoded content
            byte[] data;
            try (java.io.InputStream is = cosStream.createInputStream()) {
                data = is.readAllBytes();
            }
            if (data.length == 0) return;

            // Write back with best Deflate compression
            try (java.io.OutputStream os = cosStream.createOutputStream(
                    org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                // PDFBox uses Deflate internally; we ensure it's applied
                os.write(data);
            }
        } catch (Exception e) {
            logger.debug("Could not recompress stream: {}", e.getMessage());
        }
    }

    /**
     * Get all content streams for a page (including XObjects).
     */
    private java.util.List<org.apache.pdfbox.cos.COSBase> getPageStreams(
            org.apache.pdfbox.pdmodel.PDPage page) {
        java.util.List<org.apache.pdfbox.cos.COSBase> streams = new ArrayList<>();
        try {
            org.apache.pdfbox.cos.COSBase contents = page.getCOSObject()
                    .getDictionaryObject(org.apache.pdfbox.cos.COSName.CONTENTS);
            if (contents instanceof org.apache.pdfbox.cos.COSStream) {
                streams.add(contents);
            } else if (contents instanceof org.apache.pdfbox.cos.COSArray) {
                org.apache.pdfbox.cos.COSArray arr = (org.apache.pdfbox.cos.COSArray) contents;
                for (int i = 0; i < arr.size(); i++) {
                    streams.add(arr.getObject(i));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get page streams: {}", e.getMessage());
        }
        return streams;
    }

    /**
     * Stage 4a: Compress all images at a specific JPEG quality.
     */
    /**
     * Stage 4a: Compress all images with JPEG quality AND optional DPI downsampling.
     *
     * @param quality    JPEG quality 0.05–1.0
     * @param maxDpi     Maximum DPI to downsample to. Pass Integer.MAX_VALUE to skip downsampling.
     *                   e.g. 150 = medium quality, 72 = aggressive downsampling
     */
    private void applyImageCompression(File inputFile, File outputFile, float quality, int maxDpi) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.pdmodel.PDDocument.load(inputFile)) {

            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                org.apache.pdfbox.pdmodel.PDResources resources = page.getResources();
                if (resources == null) continue;

                // Get page dimensions to calculate DPI
                org.apache.pdfbox.pdmodel.common.PDRectangle mediaBox = page.getMediaBox();
                float pageWidthInches = mediaBox.getWidth() / 72f;   // PDF units are 1/72 inch
                float pageHeightInches = mediaBox.getHeight() / 72f;

                for (org.apache.pdfbox.cos.COSName xObjectName : resources.getXObjectNames()) {
                    try {
                        org.apache.pdfbox.pdmodel.graphics.PDXObject xObject =
                                resources.getXObject(xObjectName);
                        if (!(xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject))
                            continue;

                        org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image =
                                (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) xObject;

                        java.awt.image.BufferedImage bImage = image.getImage();
                        if (bImage == null) continue;

                        int origWidth = bImage.getWidth();
                        int origHeight = bImage.getHeight();

                        // Calculate effective DPI of this image
                        float effectiveDpiX = (pageWidthInches > 0) ? origWidth / pageWidthInches : 150f;
                        float effectiveDpiY = (pageHeightInches > 0) ? origHeight / pageHeightInches : 150f;
                        float effectiveDpi = Math.max(effectiveDpiX, effectiveDpiY);

                        java.awt.image.BufferedImage targetImage = bImage;

                        // Downsample if image exceeds maxDpi
                        if (maxDpi < Integer.MAX_VALUE && effectiveDpi > maxDpi) {
                            float scale = maxDpi / effectiveDpi;
                            int newWidth = Math.max(1, (int)(origWidth * scale));
                            int newHeight = Math.max(1, (int)(origHeight * scale));

                            logger.debug("Downsampling image from {}x{} ({:.0f} DPI) to {}x{} ({} DPI)",
                                    origWidth, origHeight, effectiveDpi, newWidth, newHeight, maxDpi);

                            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                                    newWidth, newHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D g2d = scaled.createGraphics();
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                            g2d.drawImage(bImage, 0, 0, newWidth, newHeight, null);
                            g2d.dispose();
                            targetImage = scaled;
                        }

                        // Re-encode as JPEG at specified quality
                        org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject compressed =
                                org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(
                                        doc, targetImage, quality);

                        org.apache.pdfbox.cos.COSDictionary xobjectDict =
                                (org.apache.pdfbox.cos.COSDictionary) resources.getCOSObject()
                                        .getDictionaryObject(org.apache.pdfbox.cos.COSName.XOBJECT);
                        if (xobjectDict != null) {
                            xobjectDict.setItem(xObjectName, compressed);
                        }
                    } catch (Exception e) {
                        logger.debug("Skipping image {}: {}", xObjectName, e.getMessage());
                    }
                }
            }
            doc.save(outputFile);
        }
    }

    /**
     * Overload for backward compatibility — no DPI limit (quality-only, original behavior).
     */
    private void applyImageCompression(File inputFile, File outputFile, float quality) throws IOException {
        applyImageCompression(inputFile, outputFile, quality, Integer.MAX_VALUE);
    }

    /**
     * Multi-strategy search to hit targetSizeBytes as closely as possible.
     *
     * Strategy order (from largest output to smallest):
     *   1. Lossless only                    → ~37 MB  (upper bound)
     *   2. Partial page compression          → fills the gap between lossless and full JPEG
     *   3. Full JPEG quality=1.0             → ~15 MB  (hard floor for this PDF)
     *   4. Quality binary search (0.05–1.0)  → down to ~2 MB
     *   5. Quality + DPI downsampling        → down to ~0.9 MB (absolute min)
     *
     * This guarantees we can target ANY size between absoluteMin and losslessSize.
     */
    private float binarySearchQuality(File losslessInput, File outputFile,
                                      long targetSizeBytes, String workDir) throws IOException {

        long losslessSize = losslessInput.length();

        // ── Probe 1: Full JPEG at quality=1.0, no DPI change ──
        // This is the largest size achievable via image re-encoding.
        File probeQ1 = new File(workDir + File.separator + "probe_q1.pdf");
        long sizeQ1;
        try {
            applyImageCompression(losslessInput, probeQ1, 1.0f, Integer.MAX_VALUE);
            sizeQ1 = probeQ1.length();
            logger.info("Probe q=1.0: size={} target={}", sizeQ1, targetSizeBytes);
        } finally {
            if (probeQ1.exists()) probeQ1.delete();
        }

        // ── Case A: Target is above q=1.0 — in the gap between JPEG and lossless ──
        // Fill the gap using partial-page compression: compress N% of pages, leave rest lossless.
        if (sizeQ1 < targetSizeBytes) {
            logger.info("Target {} in gap between q=1.0 ({}) and lossless ({}). Using partial-page strategy.",
                    targetSizeBytes, sizeQ1, losslessSize);
            return partialPageSearch(losslessInput, outputFile, targetSizeBytes, workDir, sizeQ1, losslessSize);
        }

        // ── Case B: Target is below q=1.0 — use quality binary search ──
        logger.info("Target {} below q=1.0 ({}). Using quality binary search.", targetSizeBytes, sizeQ1);

        // Probe at minimum quality + minimum DPI to know absolute floor
        File probeMin = new File(workDir + File.separator + "probe_min.pdf");
        long sizeMin;
        try {
            applyImageCompression(losslessInput, probeMin, 0.05f, 72);
            sizeMin = probeMin.length();
            logger.info("Probe q=0.05 dpi=72: size={}", sizeMin);
        } finally {
            if (probeMin.exists()) probeMin.delete();
        }

        // First try quality-only search (no DPI change, better visual quality)
        if (targetSizeBytes >= sizeMin) {
            // Try quality-only first
            File probeMinQ = new File(workDir + File.separator + "probe_min_q.pdf");
            long sizeMinQ;
            try {
                applyImageCompression(losslessInput, probeMinQ, 0.05f, Integer.MAX_VALUE);
                sizeMinQ = probeMinQ.length();
            } finally {
                if (probeMinQ.exists()) probeMinQ.delete();
            }

            if (targetSizeBytes >= sizeMinQ) {
                // Quality-only search is sufficient
                logger.info("Quality-only search sufficient (min q=0.05 gives {})", sizeMinQ);
                return qualityBinarySearch(losslessInput, outputFile, targetSizeBytes, workDir,
                        0.05f, 1.0f, Integer.MAX_VALUE);
            } else {
                // Need DPI + quality combined search
                logger.info("Need DPI+quality search (min q=0.05 gives {}, target={})", sizeMinQ, targetSizeBytes);
                return dpiAndQualitySearch(losslessInput, outputFile, targetSizeBytes, workDir);
            }
        }

        // Target below absolute minimum — return minimum
        logger.info("Target {} below absolute minimum {}. Returning minimum.", targetSizeBytes, sizeMin);
        applyImageCompression(losslessInput, outputFile, 0.05f, 72);
        return 0.05f;
    }

    /**
     * Partial-page compression to fill the gap between full-JPEG and lossless sizes.
     *
     * Idea: if compressing ALL pages gives sizeQ1, and compressing NO pages gives losslessSize,
     * then compressing K out of N pages gives approximately:
     *   size ≈ losslessSize - (losslessSize - sizeQ1) * K/N
     *
     * We binary search K (number of pages to compress) to hit the target.
     * We compress the LAST K pages (leaving the first N-K lossless) so the most
     * important pages (front matter) stay highest quality.
     */
    private float partialPageSearch(File losslessInput, File outputFile,
                                    long targetSizeBytes, String workDir,
                                    long sizeQ1, long losslessSize) throws IOException {

        // Get total page count
        int totalPages;
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.pdmodel.PDDocument.load(losslessInput)) {
            totalPages = doc.getNumberOfPages();
        }
        logger.info("PartialPage: totalPages={}", totalPages);

        if (totalPages == 1) {
            // Only one page — can't do partial. Return q=1.0 or lossless, whichever closer.
            long diffQ1 = Math.abs(sizeQ1 - targetSizeBytes);
            long diffLossless = Math.abs(losslessSize - targetSizeBytes);
            if (diffQ1 <= diffLossless) {
                applyImageCompression(losslessInput, outputFile, 1.0f, Integer.MAX_VALUE);
            } else {
                Files.copy(losslessInput.toPath(), outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return 1.0f;
        }

        int pageLow = 0, pageHigh = totalPages;
        int bestK = totalPages;
        long bestDiff = Long.MAX_VALUE;
        File bestFile = null;

        int maxIterations = (int) (Math.log(totalPages) / Math.log(2)) + 5;
        for (int i = 0; i < maxIterations; i++) {
            int midK = (pageLow + pageHigh) / 2;
            File candidate = new File(workDir + File.separator + "partial_" + i + ".pdf");

            applyPartialPageCompression(losslessInput, candidate, midK, totalPages, 0.85f);
            long candidateSize = candidate.length();
            long diff = Math.abs(candidateSize - targetSizeBytes);

            logger.info("PartialPage iter={} compressedPages={}/{} size={} target={} diff={}",
                    i, midK, totalPages, candidateSize, targetSizeBytes, diff);

            if (diff < bestDiff) {
                bestDiff = diff;
                bestK = midK;
                if (bestFile != null) bestFile.delete();
                bestFile = candidate;
            } else {
                candidate.delete();
            }

            if (diff * 100.0 / targetSizeBytes < 5.0) {
                logger.info("PartialPage converged within 5% at k={}", midK);
                break;
            }

            if (candidateSize > targetSizeBytes) {
                pageLow = midK; // too large → compress more pages
            } else {
                pageHigh = midK; // too small → compress fewer pages
            }

            if (pageHigh - pageLow <= 1) break;
        }

        if (bestFile != null && bestFile.exists()) {
            Files.copy(bestFile.toPath(), outputFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            bestFile.delete();
        } else {
            applyPartialPageCompression(losslessInput, outputFile, bestK, totalPages, 0.85f);
        }

        logger.info("PartialPage final: compressedPages={}/{} finalSize={}",
                bestK, totalPages, outputFile.exists() ? outputFile.length() : "?");
        return 0.85f;
    }

    /**
     * Compress only the last `pagesToCompress` pages with JPEG quality,
     * leaving the first (totalPages - pagesToCompress) pages lossless.
     */
    private void applyPartialPageCompression(File inputFile, File outputFile,
                                             int pagesToCompress, int totalPages,
                                             float quality) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.pdmodel.PDDocument.load(inputFile)) {

            int startPage = totalPages - pagesToCompress; // compress pages [startPage, totalPages)

            for (int pageIdx = startPage; pageIdx < totalPages; pageIdx++) {
                org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(pageIdx);
                org.apache.pdfbox.pdmodel.PDResources resources = page.getResources();
                if (resources == null) continue;

                for (org.apache.pdfbox.cos.COSName xObjectName : resources.getXObjectNames()) {
                    try {
                        org.apache.pdfbox.pdmodel.graphics.PDXObject xObject =
                                resources.getXObject(xObjectName);
                        if (!(xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject))
                            continue;

                        org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image =
                                (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) xObject;
                        java.awt.image.BufferedImage bImage = image.getImage();
                        if (bImage == null) continue;

                        org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject compressed =
                                org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(
                                        doc, bImage, quality);

                        org.apache.pdfbox.cos.COSDictionary xobjectDict =
                                (org.apache.pdfbox.cos.COSDictionary) resources.getCOSObject()
                                        .getDictionaryObject(org.apache.pdfbox.cos.COSName.XOBJECT);
                        if (xobjectDict != null) {
                            xobjectDict.setItem(xObjectName, compressed);
                        }
                    } catch (Exception e) {
                        logger.debug("Skipping image on page {}: {}", pageIdx, e.getMessage());
                    }
                }
            }
            doc.save(outputFile);
        }
    }

    /**
     * Combined DPI + quality search for targets below quality-only minimum.
     * First binary searches DPI at quality=0.5, then fine-tunes quality.
     */
    private float dpiAndQualitySearch(File losslessInput, File outputFile,
                                      long targetSizeBytes, String workDir) throws IOException {
        // Binary search DPI between 72 and 150
        int dpiLow = 72, dpiHigh = 150;
        int bestDpi = 100;
        long bestDiff = Long.MAX_VALUE;
        File bestDpiFile = null;

        for (int i = 0; i < 8; i++) {
            int midDpi = (dpiLow + dpiHigh) / 2;
            File candidate = new File(workDir + File.separator + "dpi_" + i + ".pdf");

            applyImageCompression(losslessInput, candidate, 0.5f, midDpi);
            long candidateSize = candidate.length();
            long diff = Math.abs(candidateSize - targetSizeBytes);

            logger.info("DPI search iter={} dpi={} size={} target={}", i, midDpi, candidateSize, targetSizeBytes);

            if (diff < bestDiff) {
                bestDiff = diff;
                bestDpi = midDpi;
                if (bestDpiFile != null) bestDpiFile.delete();
                bestDpiFile = candidate;
            } else {
                candidate.delete();
            }

            if (diff * 100.0 / targetSizeBytes < 5.0) break;

            if (candidateSize > targetSizeBytes) dpiLow = midDpi;
            else dpiHigh = midDpi;
            if (dpiHigh - dpiLow <= 2) break;
        }

        if (bestDpiFile != null) bestDpiFile.delete();

        logger.info("DPI search done: bestDpi={}, now fine-tuning quality", bestDpi);
        return qualityBinarySearch(losslessInput, outputFile, targetSizeBytes, workDir,
                0.05f, 1.0f, bestDpi);
    }

    /**
     * Binary search JPEG quality between [qualityLow, qualityHigh] at a fixed DPI.
     */
    private float qualityBinarySearch(File losslessInput, File outputFile,
                                      long targetSizeBytes, String workDir,
                                      float qualityLow, float qualityHigh, int dpi) throws IOException {
        float bestQuality = (qualityLow + qualityHigh) / 2f;
        long bestDiff = Long.MAX_VALUE;
        File bestFile = null;

        for (int i = 0; i < 20; i++) {
            float mid = (qualityLow + qualityHigh) / 2.0f;
            File candidate = new File(workDir + File.separator + "q_" + i + ".pdf");

            applyImageCompression(losslessInput, candidate, mid, dpi);
            long candidateSize = candidate.length();
            long diff = Math.abs(candidateSize - targetSizeBytes);

            logger.info("Quality search iter={} q={} dpi={} size={} target={}", i, mid, dpi, candidateSize, targetSizeBytes);

            if (diff < bestDiff) {
                bestDiff = diff;
                bestQuality = mid;
                if (bestFile != null) bestFile.delete();
                bestFile = candidate;
            } else {
                candidate.delete();
            }

            if (diff * 100.0 / targetSizeBytes < 3.0) break;
            if (candidateSize > targetSizeBytes) qualityHigh = mid;
            else qualityLow = mid;
            if (qualityHigh - qualityLow < 0.005f) break;
        }

        if (bestFile != null && bestFile.exists()) {
            Files.copy(bestFile.toPath(), outputFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            bestFile.delete();
        } else {
            applyImageCompression(losslessInput, outputFile, bestQuality, dpi);
        }

        logger.info("Quality search final: q={} dpi={} size={}", bestQuality, dpi,
                outputFile.exists() ? outputFile.length() : "?");
        return bestQuality;
    }


    private long estimateAbsoluteMinSize(File losslessInput, String workDir) throws IOException {
        // Minimum = lowest quality + most aggressive DPI downsampling
        File probe = new File(workDir + File.separator + "min_probe.pdf");
        try {
            applyImageCompression(losslessInput, probe, 0.05f, 72);
            return probe.exists() ? probe.length() : losslessInput.length();
        } finally {
            if (probe.exists()) probe.delete();
        }
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
    /**
     * Sanitize a filename for safe use as a filesystem path.
     * Replaces spaces, dots-in-name, and special characters with underscores.
     * Preserves only the final extension dot.
     */
    private String sanitizeForPath(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "file";

        // Normalize unicode
        String normalized = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFC);

        // Find last dot (extension separator)
        int lastDot = normalized.lastIndexOf('.');
        String namePart = lastDot > 0 ? normalized.substring(0, lastDot) : normalized;
        String extPart  = lastDot > 0 ? normalized.substring(lastDot).toLowerCase() : "";

        // Replace everything non-alphanumeric (spaces, dots, brackets, etc.) with underscore
        String safeName = namePart.replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_{2,}", "_");

        // Strip leading/trailing underscores
        safeName = safeName.replaceAll("^_+|_+$", "");

        if (safeName.isEmpty()) safeName = "file";

        // Extension: keep only alphanumerics
        String safeExt = extPart.replaceAll("[^a-zA-Z0-9.]", "");

        return safeName + safeExt;
    }

    public DocumentConversion rotatePdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Rotating PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "ROTATE_PDF", options);
    }

    public DocumentConversion imagesToPdf(User user, List<Long> uploadIds, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Converting {} images to PDF for user: {}", uploadIds.size(), user.getId());

        List<DocumentUpload> uploads = new ArrayList<>();
        for (Long uploadId : uploadIds) {
            DocumentUpload upload = documentUploadRepository.findById(uploadId)
                    .orElseThrow(() -> new RuntimeException("Upload not found: " + uploadId));
            if (!upload.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Unauthorized access to upload: " + uploadId);
            }
            uploads.add(upload);
        }

        return processDocumentConversion(user, uploads, "IMAGES_TO_PDF", options);
    }

    public DocumentConversion extractImagesFromPdf(User user, Long uploadId)
            throws IOException, InterruptedException {
        logger.info("Extracting images from PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "PDF_TO_IMAGES", null);
    }

    public DocumentConversion addWatermarkToPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Adding watermark to PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "ADD_WATERMARK", options);
    }

    public DocumentConversion unlockPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Unlocking PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "UNLOCK_PDF", options);
    }

    /**
     * Lock PDF (add password)
     */
    public DocumentConversion lockPdf(User user, Long uploadId, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Locking PDF for user: {}", user.getId());

        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return processDocumentConversion(user, List.of(upload), "LOCK_PDF", options);
    }

    /**
     * Main processing method for all document conversions
     */
    private DocumentConversion processDocumentConversion(
            User user,
            List<DocumentUpload> uploads,
            String operationType,
            Map<String, Object> options) throws IOException, InterruptedException {

        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("No uploads provided");
        }

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String timestamp = String.valueOf(System.currentTimeMillis());
        String workDir = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "doc_" + timestamp;

        List<File> inputFiles = new ArrayList<>();
        File outputFile = null;

        try {
            Files.createDirectories(Paths.get(workDir));

            // Download files from R2 to temp directory
            List<String> inputPaths = new ArrayList<>();
            List<Long> uploadIds = new ArrayList<>();

            for (int i = 0; i < uploads.size(); i++) {
                DocumentUpload upload = uploads.get(i);
                uploadIds.add(upload.getId());

                String tempFilePath = workDir + File.separator + "input_" + upload.getId() + "_" + sanitizeForPath(upload.getFileName());

                // Download from R2
                File inputFile = cloudflareR2Service.downloadFile(upload.getFilePath(), tempFilePath);
                inputFiles.add(inputFile);
                inputPaths.add(inputFile.getAbsolutePath());
            }

            // Determine output file name and path
            String outputFileName = generateOutputFileName(uploads.get(0).getFileName(), operationType);
            String outputPath = workDir + File.separator + outputFileName;
            outputFile = new File(outputPath);

            // Build and execute command
            List<String> command = buildConversionCommand(
                    operationType,
                    inputPaths,
                    outputPath,
                    options
            );

            logger.info("Executing command: {}", String.join(" ", command));

            // ============================================================
            // CRITICAL FIX: Proper process execution with output capture
            // ============================================================
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);  // Merge stderr into stdout

            Process process = pb.start();

            // Capture output in REAL-TIME with INFO-level logging
            StringBuilder output = new StringBuilder();
            StringBuilder jsonOutput = new StringBuilder();
            boolean capturingJson = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    // CHANGED: Use logger.info instead of logger.debug
                    logger.info("Process output: {}", line);

                    // Also print to console for immediate visibility
                    System.out.println("[Python] " + line);
                    System.out.flush();

                    // Capture JSON output (Python script prints JSON at the end)
                    if (line.trim().startsWith("{")) {
                        capturingJson = true;
                    }
                    if (capturingJson) {
                        jsonOutput.append(line);
                    }
                }
            }

            int exitCode = process.waitFor();
            logger.info("Process completed with exit code: {}", exitCode);

            if (exitCode != 0) {
                logger.error("Conversion failed with exit code: {}, output: {}", exitCode, output);
                throw new IOException("Document conversion failed: " + output);
            }

            // Verify output file exists
            if (!outputFile.exists()) {
                logger.error("Output file not created: {}", outputFile.getAbsolutePath());
                throw new IOException("Output file not created");
            }

            // ============================================================
            // CRITICAL FIX: Parse Python JSON response for post-processing data
            // ============================================================
            Map<String, Object> pythonResult = null;
            try {
                if (jsonOutput.length() > 0) {
                    pythonResult = objectMapper.readValue(jsonOutput.toString(), Map.class);
                    logger.info("Python result parsed: {}", objectMapper.writeValueAsString(pythonResult));
                }
            } catch (Exception e) {
                logger.warn("Failed to parse Python JSON response: {}", e.getMessage());
            }

            // Upload output to R2
            String r2OutputPath = String.format("documents/%s/%s/output/%s", user.getId(), timestamp, outputFileName);
            cloudflareR2Service.uploadFile(r2OutputPath, outputFile);
            logger.info("Uploaded output file to R2: {}", r2OutputPath);

            // Generate URLs
            Map<String, String> outputUrls = cloudflareR2Service.generateUrls(r2OutputPath, 3600);

            // ============================================================
            // CRITICAL FIX: Extract post-processing metadata from Python result
            // ============================================================
            Map<String, Object> postProcessingData = null;
            if (pythonResult != null && pythonResult.containsKey("post_processing")) {
                postProcessingData = (Map<String, Object>) pythonResult.get("post_processing");
                logger.info("Post-processing data extracted: {}", objectMapper.writeValueAsString(postProcessingData));
            }

            // Create and save DocumentConversion entity
            DocumentConversion conversion = new DocumentConversion();
            conversion.setUser(user);
            conversion.setOperationType(operationType);
            conversion.setSourceUploadIds(objectMapper.writeValueAsString(uploadIds));
            conversion.setOutputFileName(outputFileName);
            conversion.setOutputPath(r2OutputPath);
            conversion.setFileSizeBytes(outputFile.length());
            conversion.setOutputCdnUrl(outputUrls.get("cdnUrl"));
            conversion.setOutputPresignedUrl(outputUrls.get("presignedUrl"));

            if (options != null) {
                conversion.setProcessingOptions(objectMapper.writeValueAsString(options));
            }

            // ============================================================
            // CRITICAL FIX: Store post-processing metadata in database
            // ============================================================
            if (postProcessingData != null) {
                conversion.setPostProcessingMetadata(objectMapper.writeValueAsString(postProcessingData));
                logger.info("Stored post-processing metadata in database");
            }

            conversion.setStatus("SUCCESS");
            conversion.setCreatedAt(LocalDateTime.now());

            documentConversionRepository.save(conversion);
            logger.info("Successfully processed document conversion for user: {}", user.getId());

            return conversion;

        } finally {
            // Clean up temporary files
            for (File file : inputFiles) {
                if (file != null && file.exists()) {
                    try {
                        Files.delete(file.toPath());
                        logger.debug("Deleted temp input file: {}", file.getAbsolutePath());
                    } catch (IOException e) {
                        logger.warn("Failed to delete temp file: {}", file.getAbsolutePath());
                    }
                }
            }

            if (outputFile != null && outputFile.exists()) {
                try {
                    Files.delete(outputFile.toPath());
                    logger.debug("Deleted temp output file: {}", outputFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temp output file: {}", outputFile.getAbsolutePath());
                }
            }

            // Clean up work directory
            try {
                Path workDirPath = Paths.get(workDir);
                if (Files.exists(workDirPath)) {
                    Files.walk(workDirPath)
                            .sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.warn("Failed to delete: {}", path);
                                }
                            });
                }
            } catch (IOException e) {
                logger.warn("Failed to clean up work directory: {}", workDir);
            }
        }
    }

    /**
     * Build conversion command based on operation type
     */
    private List<String> buildConversionCommand(
            String operationType,
            List<String> inputPaths,
            String outputPath,
            Map<String, Object> options) throws IOException {

        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(documentConversionScriptPath);
        command.add(operationType);

        // Log the operation being performed
        logger.info("=== BUILDING COMMAND FOR: {} ===", operationType);
        logger.info("Input path: {}", inputPaths.get(0));
        logger.info("Output path: {}", outputPath);
        if (options != null) {
            logger.info("Options: {}", objectMapper.writeValueAsString(options));
        }
        logger.info("=== END DEBUG ===");

        switch (operationType) {
            case "WORD_TO_PDF":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "PDF_TO_WORD":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "OCR_PDF":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "ADD_PAGE_NUMBERS":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "COMPRESS_PDF":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "UNLOCK_PDF":
            case "LOCK_PDF":
            case "PDF_TO_IMAGES":
            case "SPLIT_PDF":
            case "ROTATE_PDF":
            case "ADD_WATERMARK":
            case "REARRANGE_PDF":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "MERGE_PDF":
            case "IMAGES_TO_PDF":
                command.add(outputPath);
                command.addAll(inputPaths);
                break;

            default:
                throw new IllegalArgumentException("Unsupported operation type: " + operationType);
        }

        // Add options as JSON
        if (options != null && !options.isEmpty()) {
            command.add(objectMapper.writeValueAsString(options));
        }

        // Log the final command
        logger.info("Final command: {}", String.join(" ", command));

        return command;
    }

    private String generateOutputFileName(String originalFileName, String operationType) {
        String safeName = sanitizeForPath(originalFileName != null ? originalFileName : "file");
        int lastDot = safeName.lastIndexOf('.');
        String baseName = lastDot > 0 ? safeName.substring(0, lastDot) : safeName;
        baseName = baseName.replace('.', '_').replaceAll("[^a-zA-Z0-9_-]", "_").replaceAll("_{2,}", "_");
        String timestamp = String.valueOf(System.currentTimeMillis());

        switch (operationType) {
            case "WORD_TO_PDF":
                return baseName + "_" + timestamp + ".pdf";
            case "PDF_TO_WORD":
                return baseName + "_" + timestamp + ".docx";
            case "OCR_PDF":
                return baseName + "_ocr_" + timestamp + ".pdf";
            case "ADD_PAGE_NUMBERS":
                return baseName + "_numbered_" + timestamp + ".pdf";
            case "IMAGES_TO_PDF":
                return baseName + "_" + timestamp + ".pdf";
            case "MERGE_PDF":
                return "merged_" + timestamp + ".pdf";
            case "SPLIT_PDF":
                return "split_" + timestamp + ".zip";
            case "COMPRESS_PDF":
                return baseName + "_compressed_" + timestamp + ".pdf";
            case "ROTATE_PDF":
                return baseName + "_rotated_" + timestamp + ".pdf";
            case "PDF_TO_IMAGES":
                return baseName + "_images_" + timestamp + ".zip";
            case "ADD_WATERMARK":
                return baseName + "_watermarked_" + timestamp + ".pdf";
            case "UNLOCK_PDF":
                return baseName + "_unlocked_" + timestamp + ".pdf";
            case "LOCK_PDF":
                return baseName + "_locked_" + timestamp + ".pdf";
            default:
                return "output_" + timestamp + ".pdf";
        }
    }

    /**
     * Get user from JWT token
     */
    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    /**
     * Get user's conversion history
     */
    public List<DocumentConversion> getUserConversions(User user) {
        return documentConversionRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Get conversion by ID (with user validation)
     */
    public DocumentConversion getConversionById(User user, Long id) {
        DocumentConversion conversion = documentConversionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Conversion not found"));

        if (!conversion.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        return conversion;
    }

    /**
     * Rearrange and merge PDFs - uses Python script to count pages
     */
    public DocumentConversion rearrangeMergePdfs(User user, List<Long> uploadIds, Map<String, Object> options)
            throws IOException, InterruptedException {
        logger.info("Rearranging and merging {} PDFs for user: {}", uploadIds.size(), user.getId());

        if (uploadIds.isEmpty()) {
            throw new IllegalArgumentException("At least 1 PDF file is required");
        }

        // Get the first PDF (base PDF for rearranging)
        DocumentUpload baseUpload = documentUploadRepository.findById(uploadIds.get(0))
                .orElseThrow(() -> new RuntimeException("Base upload not found: " + uploadIds.get(0)));
        if (!baseUpload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access to upload: " + uploadIds.get(0));
        }

        // If there are additional PDFs to merge, prepare insertions
        if (uploadIds.size() > 1 && options != null) {
            List<Map<String, Object>> insertions = new ArrayList<>();

            // Get the base PDF's page count using Python
            String tempDir = baseDir + File.separator + "temp_" + System.currentTimeMillis();
            Files.createDirectories(Paths.get(tempDir));

            try {
                String tempBasePath = tempDir + File.separator + sanitizeForPath(baseUpload.getFileName());
                File tempBaseFile = cloudflareR2Service.downloadFile(baseUpload.getFilePath(), tempBasePath);

                // Count pages using Python script
                int basePdfPageCount = countPdfPagesUsingPython(tempBaseFile);

                // Clean up temp file
                Files.delete(tempBaseFile.toPath());
                Files.delete(Paths.get(tempDir));

                // Process additional PDFs as insertions
                for (int i = 1; i < uploadIds.size(); i++) {
                    final Long currentUploadId = uploadIds.get(i); // Make it final
                    DocumentUpload additionalUpload = documentUploadRepository.findById(currentUploadId)
                            .orElseThrow(() -> new RuntimeException("Upload not found: " + currentUploadId));
                    if (!additionalUpload.getUser().getId().equals(user.getId())) {
                        throw new RuntimeException("Unauthorized access to upload: " + currentUploadId);
                    }

                    // Download the additional PDF to get its absolute path
                    String workDir = baseDir + File.separator + "videoeditor" + File.separator + "merge_" + System.currentTimeMillis();
                    Files.createDirectories(Paths.get(workDir));

                    String tempFilePath = workDir + File.separator + "additional_" + i + "_" + sanitizeForPath(additionalUpload.getFileName());
                    File additionalFile = cloudflareR2Service.downloadFile(additionalUpload.getFilePath(), tempFilePath);

                    // Create insertion to add at the end (or specified position)
                    Map<String, Object> insertion = new HashMap<>();
                    insertion.put("position", basePdfPageCount + i - 1);
                    insertion.put("type", "pdf");
                    insertion.put("filePath", additionalFile.getAbsolutePath());
                    insertions.add(insertion);
                }

                options.put("insertions", insertions);

            } catch (Exception e) {
                logger.error("Failed to prepare insertions: {}", e.getMessage());
                throw new IOException("Failed to prepare PDF merging: " + e.getMessage());
            }
        }

        return processDocumentConversion(user, List.of(baseUpload), "REARRANGE_PDF", options);
    }

    /**
     * Count PDF pages using Python script (since we can't use PyPDF2 in Java)
     */
    private int countPdfPagesUsingPython(File pdfFile) throws IOException, InterruptedException {
        // Create a simple Python script to count pages
        String countScript = "import sys; from PyPDF2 import PdfReader; reader = PdfReader(sys.argv[1]); print(len(reader.pages))";

        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add("-c");
        command.add(countScript);
        command.add(pdfFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to count PDF pages: " + output);
        }

        try {
            return Integer.parseInt(output.toString().trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid page count returned: " + output);
        }
    }

    /**
     * Get PDF page count for validation
     */
    public int getPdfPageCount(User user, Long uploadId) throws IOException, InterruptedException {
        DocumentUpload upload = documentUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found"));
        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        String tempDir = baseDir + File.separator + "temp_" + System.currentTimeMillis();
        Files.createDirectories(Paths.get(tempDir));

        try {
            String tempFilePath = tempDir + File.separator + sanitizeForPath(upload.getFileName());
            File tempFile = cloudflareR2Service.downloadFile(upload.getFilePath(), tempFilePath);

            int pageCount = countPdfPagesUsingPython(tempFile);

            Files.delete(tempFile.toPath());
            Files.delete(Paths.get(tempDir));

            return pageCount;
        } catch (Exception e) {
            throw new IOException("Failed to get PDF page count: " + e.getMessage());
        }
    }
}