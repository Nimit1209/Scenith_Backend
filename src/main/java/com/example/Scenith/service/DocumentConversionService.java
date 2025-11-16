package com.example.Scenith.service;

import com.example.Scenith.entity.DocumentConversion;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.DocumentConversionRepository;
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
            ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.cloudflareR2Service = cloudflareR2Service;
        this.documentConversionRepository = documentConversionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Word to PDF Conversion
     */
    public DocumentConversion convertWordToPdf(User user, MultipartFile wordFile) throws IOException, InterruptedException {
        logger.info("Converting Word to PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(wordFile), "WORD_TO_PDF", null);
    }

    /**
     * PDF to Word Conversion
     */
    public DocumentConversion convertPdfToWord(User user, MultipartFile pdfFile) throws IOException, InterruptedException {
        logger.info("Converting PDF to Word for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "PDF_TO_WORD", null);
    }

    /**
     * Merge multiple PDFs
     */
    public DocumentConversion mergePdfs(User user, List<MultipartFile> pdfFiles) throws IOException, InterruptedException {
        logger.info("Merging {} PDFs for user: {}", pdfFiles.size(), user.getId());
        if (pdfFiles.size() < 2) {
            throw new IllegalArgumentException("At least 2 PDF files are required for merging");
        }
        return processDocumentConversion(user, pdfFiles, "MERGE_PDF", null);
    }

    /**
     * Split PDF into multiple files
     */
    public DocumentConversion splitPdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Splitting PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "SPLIT_PDF", options);
    }

    /**
     * Compress PDF
     */
    public DocumentConversion compressPdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Compressing PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "COMPRESS_PDF", options);
    }

    /**
     * Rotate PDF pages
     */
    public DocumentConversion rotatePdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Rotating PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "ROTATE_PDF", options);
    }

    /**
     * Convert images to PDF
     */
    public DocumentConversion imagesToPdf(User user, List<MultipartFile> imageFiles) throws IOException, InterruptedException {
        logger.info("Converting {} images to PDF for user: {}", imageFiles.size(), user.getId());
        return processDocumentConversion(user, imageFiles, "IMAGES_TO_PDF", null);
    }

    /**
     * Extract images from PDF
     */
    public DocumentConversion extractImagesFromPdf(User user, MultipartFile pdfFile) throws IOException, InterruptedException {
        logger.info("Extracting images from PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "PDF_TO_IMAGES", null);
    }

    /**
     * Add watermark to PDF
     */
    public DocumentConversion addWatermarkToPdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Adding watermark to PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "ADD_WATERMARK", options);
    }

    /**
     * Unlock PDF (remove password)
     */
    public DocumentConversion unlockPdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Unlocking PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "UNLOCK_PDF", options);
    }

    /**
     * Lock PDF (add password)
     */
    public DocumentConversion lockPdf(User user, MultipartFile pdfFile, Map<String, Object> options) throws IOException, InterruptedException {
        logger.info("Locking PDF for user: {}", user.getId());
        return processDocumentConversion(user, Collections.singletonList(pdfFile), "LOCK_PDF", options);
    }

    /**
     * Main processing method for all document conversions
     */
    private DocumentConversion processDocumentConversion(
            User user,
            List<MultipartFile> files,
            String operationType,
            Map<String, Object> options) throws IOException, InterruptedException {

        // Validate input
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files provided");
        }

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String timestamp = String.valueOf(System.currentTimeMillis());
        String workDir = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "doc_" + timestamp;

        List<File> inputFiles = new ArrayList<>();
        File outputFile = null;

        try {
            // Create working directory
            Files.createDirectories(Paths.get(workDir));

            // Save input files
            List<String> inputPaths = new ArrayList<>();
            List<String> originalFileNames = new ArrayList<>();
            List<String> r2OriginalPaths = new ArrayList<>();

            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String fileName = file.getOriginalFilename();
                String tempFilePath = workDir + File.separator + "input_" + i + "_" + fileName;
                
                File inputFile = cloudflareR2Service.saveMultipartFileToTemp(file, tempFilePath);
                inputFiles.add(inputFile);
                inputPaths.add(inputFile.getAbsolutePath());
                originalFileNames.add(fileName);

                // Upload original to R2
                String r2Path = String.format("documents/%s/%s/original/%s", user.getId(), timestamp, fileName);
                cloudflareR2Service.uploadFile(r2Path, inputFile);
                r2OriginalPaths.add(r2Path);
                logger.info("Uploaded original file to R2: {}", r2Path);
            }

            // Determine output file name and path
            String outputFileName = generateOutputFileName(originalFileNames.get(0), operationType);
            String outputPath = workDir + File.separator + outputFileName;
            outputFile = new File(outputPath);

            // Verify Python script exists
            File scriptFile = new File(documentConversionScriptPath);
            if (!scriptFile.exists()) {
                logger.error("Python script not found: {}", scriptFile.getAbsolutePath());
                throw new IOException("Document conversion script not found");
            }

            // Build command
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

            // Verify output file(s) exist
            if (!outputFile.exists()) {
                logger.error("Output file not created: {}", outputFile.getAbsolutePath());
                throw new IOException("Output file not created");
            }

            // Upload output to R2
            String r2OutputPath = String.format("documents/%s/%s/output/%s", user.getId(), timestamp, outputFileName);
            cloudflareR2Service.uploadFile(r2OutputPath, outputFile);
            logger.info("Uploaded output file to R2: {}", r2OutputPath);

            // Generate URLs
            List<Map<String, String>> originalUrls = new ArrayList<>();
            for (String r2Path : r2OriginalPaths) {
                originalUrls.add(cloudflareR2Service.generateUrls(r2Path, 3600));
            }

            Map<String, String> outputUrls = cloudflareR2Service.generateUrls(r2OutputPath, 3600);

            // Create and save DocumentConversion entity
            DocumentConversion conversion = new DocumentConversion();
            conversion.setUser(user);
            conversion.setOperationType(operationType);
            conversion.setOriginalFileNames(objectMapper.writeValueAsString(originalFileNames));
            conversion.setOriginalPaths(objectMapper.writeValueAsString(r2OriginalPaths));
            conversion.setOutputFileName(outputFileName);
            conversion.setOutputPath(r2OutputPath);
            conversion.setFileSizeBytes(outputFile.length());
            
            // Store original URLs as JSON
            List<String> cdnUrls = originalUrls.stream().map(u -> u.get("cdnUrl")).collect(Collectors.toList());
            List<String> presignedUrls = originalUrls.stream().map(u -> u.get("presignedUrl")).collect(Collectors.toList());
            conversion.setOriginalCdnUrls(objectMapper.writeValueAsString(cdnUrls));
            conversion.setOriginalPresignedUrls(objectMapper.writeValueAsString(presignedUrls));
            
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
            case "WORD_TO_PDF":
            case "PDF_TO_WORD":
            case "COMPRESS_PDF":
            case "UNLOCK_PDF":
            case "LOCK_PDF":
            case "PDF_TO_IMAGES":
                command.add(inputPaths.get(0));
                command.add(outputPath);
                break;

            case "MERGE_PDF":
            case "IMAGES_TO_PDF":
                command.add(outputPath);
                command.addAll(inputPaths);
                break;

            case "SPLIT_PDF":
            case "ROTATE_PDF":
            case "ADD_WATERMARK":
                command.add(inputPaths.get(0));
                command.add(outputPath);
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

    /**
     * Generate output file name based on operation
     */
    private String generateOutputFileName(String originalFileName, String operationType) {
        String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
        String timestamp = String.valueOf(System.currentTimeMillis());

        switch (operationType) {
            case "WORD_TO_PDF":
            case "IMAGES_TO_PDF":
                return baseName + "_" + timestamp + ".pdf";
            case "PDF_TO_WORD":
                return baseName + "_" + timestamp + ".docx";
            case "MERGE_PDF":
                return "merged_" + timestamp + ".pdf";
            case "SPLIT_PDF":
                return "split_" + timestamp + ".zip"; // Returns ZIP with multiple PDFs
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
     * Delete conversion and associated files
     */
    public void deleteConversion(User user, Long id) throws IOException {
        DocumentConversion conversion = getConversionById(user, id);
        
        try {
            // Delete from R2
            List<String> originalPaths = objectMapper.readValue(
                    conversion.getOriginalPaths(), 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
            
            for (String path : originalPaths) {
                cloudflareR2Service.deleteFile(path);
            }
            
            cloudflareR2Service.deleteFile(conversion.getOutputPath());
            
            // Delete from database
            documentConversionRepository.delete(conversion);
            
            logger.info("Deleted conversion {} for user {}", id, user.getId());
        } catch (Exception e) {
            logger.error("Error deleting conversion: {}", e.getMessage());
            throw new IOException("Failed to delete conversion", e);
        }
    }
}