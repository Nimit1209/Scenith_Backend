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
    public DocumentConversion convertWordToPdf(User user, MultipartFile wordFile) throws IOException {
        logger.info("Word to PDF conversion requested for user: {}", user.getId());

        // Create a simple error response without processing
        DocumentConversion conversion = new DocumentConversion();
        conversion.setUser(user);
        conversion.setOperationType("WORD_TO_PDF");
        conversion.setStatus("FAILED");
        conversion.setErrorMessage(
                "Word to PDF conversion is not available. " +
                        "Please convert your Word document to PDF using Microsoft Word, Google Docs, or an online converter " +
                        "before uploading. You can then use our PDF tools for merging, splitting, compressing, and other operations."
        );
        conversion.setCreatedAt(LocalDateTime.now());

        documentConversionRepository.save(conversion);

        throw new IOException(
                "Word to PDF conversion is not available. " +
                        "Please convert to PDF first using Microsoft Word, Google Docs, or an online converter."
        );
    }

//    /**
//     * PDF to Word Conversion - Enhanced with better error handling (UPDATED)
//     * Replace the existing convertPdfToWord method
//     */
//    public DocumentConversion convertPdfToWord(User user, MultipartFile pdfFile) throws IOException, InterruptedException {
//        logger.info("Converting PDF to Word for user: {}", user.getId());
//        try {
//            return processDocumentConversion(user, Collections.singletonList(pdfFile), "PDF_TO_WORD", null);
//        } catch (IOException e) {
//            // Log the error and create a failed conversion record
//            logger.error("PDF to Word conversion failed for user {}: {}", user.getId(), e.getMessage());
//
//            // Create failed conversion record
//            DocumentConversion conversion = new DocumentConversion();
//            conversion.setUser(user);
//            conversion.setOperationType("PDF_TO_WORD");
//            conversion.setOriginalFileNames(objectMapper.writeValueAsString(Collections.singletonList(pdfFile.getOriginalFilename())));
//            conversion.setStatus("FAILED");
//            conversion.setErrorMessage(e.getMessage());
//            conversion.setCreatedAt(LocalDateTime.now());
//
//            documentConversionRepository.save(conversion);
//
//            throw e;
//        }
//    }
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

        return processDocumentConversion(user, List.of(upload), "COMPRESS_PDF", options);
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

                String tempFilePath = workDir + File.separator + "input_" + i + "_" + upload.getFileName();

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
            // Build and execute command
            List<String> command = buildConversionCommand(
                    operationType,
                    inputPaths,
                    outputPath,
                    options
            );

            logger.debug("Executing command: {}", String.join(" ", command));

            // Execute conversion
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("Process output: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("Conversion failed with exit code: {}, output: {}", exitCode, output);
                throw new IOException("Document conversion failed: " + output);
            }

            // Verify output file exists
            if (!outputFile.exists()) {
                logger.error("Output file not created: {}", outputFile.getAbsolutePath());
                throw new IOException("Output file not created");
            }

            // Upload output to R2
            String r2OutputPath = String.format("documents/%s/%s/output/%s", user.getId(), timestamp, outputFileName);
            cloudflareR2Service.uploadFile(r2OutputPath, outputFile);
            logger.info("Uploaded output file to R2: {}", r2OutputPath);

            // Generate URLs
            Map<String, String> outputUrls = cloudflareR2Service.generateUrls(r2OutputPath, 3600);

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

        switch (operationType) {
            case "COMPRESS_PDF":
            case "UNLOCK_PDF":
            case "LOCK_PDF":
            case "PDF_TO_IMAGES":
            case "SPLIT_PDF":
            case "ROTATE_PDF":
            case "ADD_WATERMARK":
            case "REARRANGE_PDF":  // Add this!
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

        return command;
    }

    private String generateOutputFileName(String originalFileName, String operationType) {
        String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
        String timestamp = String.valueOf(System.currentTimeMillis());

        switch (operationType) {
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
                String tempBasePath = tempDir + File.separator + baseUpload.getFileName();
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

                    String tempFilePath = workDir + File.separator + "additional_" + i + "_" + additionalUpload.getFileName();
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

}