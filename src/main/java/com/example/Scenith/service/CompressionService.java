package com.example.Scenith.service;

import com.example.Scenith.entity.CompressedMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.CompressedMediaRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class CompressionService {

    private static final Logger logger = LoggerFactory.getLogger(CompressionService.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final CompressedMediaRepository compressedMediaRepository;
    private final  ProcessingEmailHelper emailHelper;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${python.path:/usr/local/bin/python3}")
    private String pythonPath;

    @Value("${app.compression-script-path:/app/scripts/compress_media.py}")
    private String compressionScriptPath;

    public CompressionService(
            JwtUtil jwtUtil,
            UserRepository userRepository,
            CloudflareR2Service cloudflareR2Service,
            CompressedMediaRepository compressedMediaRepository, ProcessingEmailHelper emailHelper) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.cloudflareR2Service = cloudflareR2Service;
        this.compressedMediaRepository = compressedMediaRepository;
        this.emailHelper = emailHelper;
    }

    public CompressedMedia uploadMedia(User user, MultipartFile mediaFile, String targetSize) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty for user: {}", user.getId());
            throw new IllegalArgumentException("Media file is null or empty");
        }
        if (targetSize == null || !targetSize.matches("\\d+(KB|MB)")) {
            logger.error("Invalid target size format: {}", targetSize);
            throw new IllegalArgumentException("Target size must be in format 'numberKB' or 'numberMB'");
        }

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String tempFileName = "compression-" + System.currentTimeMillis() + "-" + mediaFile.getOriginalFilename();
        String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + tempFileName;

        File inputFile = null;
        try {
            inputFile = cloudflareR2Service.saveMultipartFileToTemp(mediaFile, tempInputPath);
            logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

            if (inputFile.length() == 0) {
                logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
                throw new IOException("Input file is empty");
            }

            String r2OriginalPath = String.format("Compression/uploaded/%s/%s", user.getId(), mediaFile.getOriginalFilename());
            cloudflareR2Service.uploadFile(r2OriginalPath, inputFile);
            logger.info("Uploaded original media to R2: {}", r2OriginalPath);

            Map<String, String> originalUrls = cloudflareR2Service.generateUrls(r2OriginalPath, 3600);

            CompressedMedia compressedMedia = new CompressedMedia();
            compressedMedia.setUser(user);
            compressedMedia.setOriginalFileName(mediaFile.getOriginalFilename());
            compressedMedia.setOriginalPath(r2OriginalPath);
            compressedMedia.setOriginalCdnUrl(originalUrls.get("cdnUrl"));
            compressedMedia.setTargetSize(targetSize);
            compressedMedia.setStatus("UPLOADED");
            compressedMediaRepository.save(compressedMedia);

            logger.info("Saved metadata for user: {}, media: {}", user.getId(), mediaFile.getOriginalFilename());
            return compressedMedia;

        } finally {
            if (inputFile != null && inputFile.exists()) {
                try {
                    Files.delete(inputFile.toPath());
                    logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary input file: {}", inputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    public CompressedMedia compressMedia(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Compressing media for user: {}, mediaId: {}", user.getId(), mediaId);

        CompressedMedia compressedMedia = compressedMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!compressedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to compress media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to compress this media");
        }

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "input-" + System.currentTimeMillis() + "-" + compressedMedia.getOriginalFileName();
        String outputFileName = "compressed_" + compressedMedia.getOriginalFileName();
        String tempOutputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + outputFileName;

        File inputFile = null;
        File outputFile = null;
        try {
            File outputDir = new File(absoluteBaseDir + File.separator + "videoeditor");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                logger.error("Failed to create output directory: {}", outputDir.getAbsolutePath());
                throw new IOException("Failed to create output directory");
            }
            if (!outputDir.canWrite()) {
                logger.error("Output directory is not writable: {}", outputDir.getAbsolutePath());
                throw new IOException("Output directory is not writable");
            }

            inputFile = cloudflareR2Service.downloadFileWithRetry(compressedMedia.getOriginalPath(), tempInputPath, 3);
            logger.debug("Downloaded media to: {}", inputFile.getAbsolutePath());

            if (!inputFile.exists() || inputFile.length() == 0) {
                logger.error("Input file is missing or empty: {}", tempInputPath);
                throw new IOException("Input file is missing or empty");
            }

            File scriptFile = new File(compressionScriptPath);
            if (!scriptFile.exists()) {
                logger.error("Compression script not found: {}", scriptFile.getAbsolutePath());
                throw new IOException("Compression script not found");
            }

            String r2ProcessedPath = String.format("Compression/processed/%s/%s", user.getId(), outputFileName);
            outputFile = new File(tempOutputPath);
            Files.createDirectories(outputFile.toPath().getParent());

            List<String> command = Arrays.asList(
                    pythonPath,
                    compressionScriptPath,
                    inputFile.getAbsolutePath(),
                    outputFile.getAbsolutePath(),
                    compressedMedia.getTargetSize()
            );

            logger.debug("Executing command: {}", String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            // Capture stdout and stderr separately
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                logger.error("Failed to start compression process: {}", e.getMessage());
                throw new IOException("Failed to start compression process", e);
            }

            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("stdout: {}", line);
                }
                while ((line = stderrReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    logger.debug("stderr: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("Compression failed for user: {}, mediaId: {}, exit code: {}, stdout: {}, stderr: {}",
                        user.getId(), mediaId, exitCode, output, errorOutput);
                compressedMedia.setStatus("FAILED");
                compressedMedia.setErrorMessage("Compression failed: " + errorOutput);
                compressedMediaRepository.save(compressedMedia);
                throw new IOException("Compression failed with exit code: " + exitCode + ", error: " + errorOutput);
            }

            if (!outputFile.exists()) {
                logger.error("Output file not created: {}", outputFile.getAbsolutePath());
                compressedMedia.setStatus("FAILED");
                compressedMedia.setErrorMessage("Output file not created: " + output);
                compressedMediaRepository.save(compressedMedia);
                throw new IOException("Output file not created");
            }

            cloudflareR2Service.uploadFile(r2ProcessedPath, outputFile);
            logger.info("Uploaded compressed media to R2: {}", r2ProcessedPath);

            Map<String, String> processedUrls = cloudflareR2Service.generateUrls(r2ProcessedPath, 3600);
            compressedMedia.setProcessedFileName(outputFileName);
            compressedMedia.setProcessedPath(r2ProcessedPath);
            compressedMedia.setProcessedCdnUrl(processedUrls.get("cdnUrl"));
            compressedMedia.setStatus("SUCCESS");
            compressedMediaRepository.save(compressedMedia);

            // NEW: Send completion email
            emailHelper.sendProcessingCompleteEmail(
                    user,
                    ProcessingEmailHelper.ServiceType.COMPRESSION,
                    compressedMedia.getOriginalFileName(),
                    compressedMedia.getProcessedCdnUrl(),
                    mediaId
            );
            logger.info("Successfully compressed media for user: {}, mediaId: {}", user.getId(), mediaId);
            return compressedMedia;

        } finally {
            if (inputFile != null && inputFile.exists()) {
                try {
                    Files.delete(inputFile.toPath());
                    logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary input file: {}", inputFile.getAbsolutePath(), e);
                }
            }
            if (outputFile != null && outputFile.exists()) {
                try {
                    Files.delete(outputFile.toPath());
                    logger.debug("Deleted temporary output file: {}", outputFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary output file: {}", outputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    public List<CompressedMedia> getUserCompressedMedia(User user) {
        return compressedMediaRepository.findByUser(user);
    }

    public CompressedMedia updateTargetSize(User user, Long mediaId, String newTargetSize) throws IllegalArgumentException {

        if (newTargetSize == null || !newTargetSize.matches("\\d+(KB|MB)")) {
            throw new IllegalArgumentException("Target size must be in format 'numberKB' or 'numberMB'");
        }

        CompressedMedia compressedMedia = compressedMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    return new IllegalArgumentException("Media not found");
                });

        if (!compressedMedia.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not authorized to update this media");
        }

        if (!compressedMedia.getStatus().equals("UPLOADED")) {
            throw new IllegalStateException("Media target size can only be updated in UPLOADED state");
        }

        compressedMedia.setTargetSize(newTargetSize);
        compressedMediaRepository.save(compressedMedia);
        return compressedMedia;
    }
}