package com.example.Scenith.service;

import com.example.Scenith.entity.ConvertedMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ConvertedMediaRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaConversionService {
    private static final Logger logger = LoggerFactory.getLogger(MediaConversionService.class);
    private static final List<String> VIDEO_FORMATS = Arrays.asList("MP4", "AVI", "MKV", "MOV", "WEBM", "FLV", "WMV");
    private static final List<String> IMAGE_FORMATS = Arrays.asList("PNG", "JPG", "BMP", "GIF", "TIFF", "WEBP");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ConvertedMediaRepository convertedMediaRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final ProcessingEmailHelper emailHelper;

    @Value("${app.ffmpeg-path:/usr/local/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${app.conversion-script-path:/app/scripts/convert_media.py}")
    private String conversionScriptPath;

    @Value("${python.path:/usr/local/bin/python3.11}")
    private String pythonPath;

    @Value("${cf.public.access.url:https://cdn.scenith.in}")
    private String cdnDomain;

    public ConvertedMedia uploadMedia(User user, MultipartFile mediaFile, String targetFormat) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        String mediaType = determineMediaType(mediaFile);
        validateInputs(mediaFile, mediaType, targetFormat);

        // Generate unique filename to avoid conflicts
        String originalFileName = UUID.randomUUID().toString() + "_" +
                sanitizeFilename(mediaFile.getOriginalFilename());
        String r2Path = "conversions/" + user.getId() + "/original/" + originalFileName;

        // Upload directly to R2 maintaining the same path structure
        cloudflareR2Service.uploadFile(mediaFile, r2Path);

        // Generate CDN URL with same path structure
        String originalCdnUrl = String.format("https://%s/%s",
                cdnDomain.replaceFirst("^(https?://)", ""), r2Path);

        ConvertedMedia convertedMedia = new ConvertedMedia();
        convertedMedia.setUser(user);
        convertedMedia.setOriginalFileName(mediaFile.getOriginalFilename());
        convertedMedia.setOriginalPath(r2Path);
        convertedMedia.setOriginalCdnUrl(originalCdnUrl);
        convertedMedia.setMediaType(mediaType);
        convertedMedia.setTargetFormat(targetFormat.toUpperCase());
        convertedMedia.setStatus("UPLOADED");
        convertedMediaRepository.save(convertedMedia);

        logger.info("Saved metadata for user: {}, media: {}, type: {}",
                user.getId(), originalFileName, mediaType);
        return convertedMedia;
    }

    public ConvertedMedia convertMedia(User user, Long mediaId) throws IOException, InterruptedException {
        logger.info("Converting media for user: {}, mediaId: {}", user.getId(), mediaId);

        ConvertedMedia convertedMedia = convertedMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!convertedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to convert media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to convert this media");
        }

        convertedMedia.setStatus("PROCESSING");
        convertedMediaRepository.save(convertedMedia);

        // Create temp directory for processing
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"),
                "videoeditor", "conversions", String.valueOf(mediaId));
        Files.createDirectories(tempDir);

        String tempInputPath = tempDir.resolve("input").toString();
        String outputFileName;
        String processedR2Path;

        try {
            // Download original file from R2 to temp
            cloudflareR2Service.downloadFile(
                    convertedMedia.getOriginalPath(), tempInputPath);

            File inputFile = new File(tempInputPath);
            if (!inputFile.exists() || inputFile.length() == 0) {
                logger.error("Downloaded input file is missing or empty: {}", tempInputPath);
                convertedMedia.setStatus("FAILED");
                convertedMedia.setErrorMessage("Input file download failed");
                convertedMediaRepository.save(convertedMedia);
                throw new IOException("Input file download failed");
            }

            // Create output filename with correct extension
            String originalNameWithoutExt = getFileNameWithoutExtension(
                    convertedMedia.getOriginalFileName());
            String targetExt = convertedMedia.getTargetFormat().toLowerCase();

            // Handle JPG format properly
            if (targetExt.equals("jpg")) {
                targetExt = "jpg";
            } else if (targetExt.equals("jpeg")) {
                targetExt = "jpg";
            }

            outputFileName = "converted_" + originalNameWithoutExt + "." + targetExt;
            processedR2Path = "conversions/" + user.getId() + "/processed/" + outputFileName;

            String tempOutputPath = tempDir.resolve(outputFileName).toString();
            File outputFile = new File(tempOutputPath);

            logger.info("Converting {} from {} to {}",
                    convertedMedia.getMediaType(),
                    inputFile.getName(),
                    outputFileName);

            // Perform conversion
            if (convertedMedia.getMediaType().equals("VIDEO")) {
                convertVideo(inputFile, outputFile, convertedMedia.getTargetFormat(), mediaId);
            } else {
                convertImage(inputFile, outputFile, convertedMedia.getTargetFormat(), mediaId);
            }

            // Verify output file
            boolean fileReady = waitForFileReady(outputFile, 10, 1000);
            if (!fileReady) {
                logger.error("Output file not created or empty after retries: {}", tempOutputPath);
                logger.error("Expected output path: {}", outputFile.getAbsolutePath());
                logger.error("Output file exists: {}", outputFile.exists());
                logger.error("Output file size: {}", outputFile.exists() ? outputFile.length() : "N/A");
                convertedMedia.setStatus("FAILED");
                convertedMedia.setErrorMessage("Output file creation failed");
                convertedMediaRepository.save(convertedMedia);
                throw new IOException("Output file creation failed");
            }

            logger.info("Output file created successfully: {} (size: {} bytes)",
                    outputFile.getAbsolutePath(), outputFile.length());

            // Upload processed file to R2
            logger.info("Uploading processed file to R2: {}", processedR2Path);
            cloudflareR2Service.uploadFile(outputFile, processedR2Path);

            // Generate CDN URL
            String processedCdnUrl = String.format("https://%s/%s",
                    cdnDomain.replaceFirst("^(https?://)", ""), processedR2Path);

            // Update entity
            convertedMedia.setProcessedFileName(outputFileName);
            convertedMedia.setProcessedPath(processedR2Path);
            convertedMedia.setProcessedCdnUrl(processedCdnUrl);
            convertedMedia.setStatus("SUCCESS");
            convertedMediaRepository.save(convertedMedia);
            // NEW: Send completion email
            emailHelper.sendProcessingCompleteEmail(
                    user,
                    ProcessingEmailHelper.ServiceType.CONVERSION,
                    convertedMedia.getOriginalFileName(),
                    convertedMedia.getProcessedCdnUrl(),
                    mediaId
            );

            logger.info("Successfully converted media for user: {}, mediaId: {}", user.getId(), mediaId);
            return convertedMedia;

        } catch (Exception e) {
            logger.error("Conversion failed for mediaId {}: {}", mediaId, e.getMessage(), e);
            convertedMedia.setStatus("FAILED");
            convertedMedia.setErrorMessage(e.getMessage());
            convertedMediaRepository.save(convertedMedia);
            throw e;
        } finally {
            // Cleanup temp files
            cleanupTempDirectory(tempDir);
        }
    }

    public List<ConvertedMedia> getUserConvertedMedia(User user) {
        return convertedMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private String determineMediaType(MultipartFile mediaFile) throws IOException {
        String contentType = mediaFile.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Cannot determine media type");
        }
        if (contentType.startsWith("video/")) {
            return "VIDEO";
        } else if (contentType.startsWith("image/")) {
            return "IMAGE";
        } else {
            throw new IllegalArgumentException("Unsupported media type: " + contentType);
        }
    }

    private void validateInputs(MultipartFile mediaFile, String mediaType, String targetFormat) {
        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty");
            throw new IllegalArgumentException("Media file is null or empty");
        }
        if (targetFormat == null || targetFormat.trim().isEmpty()) {
            logger.error("Target format is null or empty");
            throw new IllegalArgumentException("Target format is required");
        }
        String formatUpper = targetFormat.toUpperCase();
        if (mediaType.equals("VIDEO") && !VIDEO_FORMATS.contains(formatUpper)) {
            logger.error("Invalid video format: {}", targetFormat);
            throw new IllegalArgumentException("Invalid video format. Supported: " + String.join(", ", VIDEO_FORMATS));
        }
        if (mediaType.equals("IMAGE") && !IMAGE_FORMATS.contains(formatUpper)) {
            logger.error("Invalid image format: {}", targetFormat);
            throw new IllegalArgumentException("Invalid image format. Supported: " + String.join(", ", IMAGE_FORMATS));
        }
    }

    private void convertVideo(File inputFile, File outputFile, String targetFormat, Long mediaId)
            throws IOException, InterruptedException {

        File pythonFile = new File(pythonPath);
        if (!pythonFile.exists() || !pythonFile.canExecute()) {
            logger.error("Python executable not found or not executable: {}", pythonPath);
            throw new IOException("Python executable not found or not executable: " + pythonPath);
        }

        File scriptFile = new File(conversionScriptPath);
        if (!scriptFile.exists()) {
            logger.error("Conversion script not found: {}", conversionScriptPath);
            throw new IOException("Conversion script not found: " + conversionScriptPath);
        }

        // Ensure output directory exists
        File outputDir = outputFile.getParentFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            logger.error("Failed to create output directory: {}", outputDir.getAbsolutePath());
            throw new IOException("Failed to create output directory: " + outputDir.getAbsolutePath());
        }

        List<String> command = Arrays.asList(
                pythonPath,
                conversionScriptPath,
                inputFile.getAbsolutePath(),
                outputFile.getAbsolutePath(),
                targetFormat.toUpperCase()
        );

        logger.info("Executing video conversion command: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(scriptFile.getParentFile());
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

            String line;
            while ((line = stdoutReader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("Python stdout: {}", line);
            }
            while ((line = stderrReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
                logger.debug("Python stderr: {}", line);
            }
        }

        int exitCode = process.waitFor();

        logger.info("Python process completed with exit code: {}", exitCode);
        if (exitCode != 0) {
            logger.error("Video conversion failed for mediaId: {}, exit code: {}", mediaId, exitCode);
            logger.error("Python stderr output: {}", errorOutput.toString());
            throw new IOException("Video conversion failed with exit code: " + exitCode +
                    "\nError: " + errorOutput.toString());
        }

        if (!outputFile.exists() || outputFile.length() == 0) {
            logger.error("Output video file not created or empty for mediaId: {}", mediaId);
            throw new IOException("Output video file not created or empty after conversion");
        }

        logger.info("Video conversion successful for mediaId: {}, output size: {} bytes",
                mediaId, outputFile.length());
    }

    private void convertImage(File inputFile, File outputFile, String targetFormat, Long mediaId) throws IOException {
        logger.info("Starting image conversion: {} -> {} (format: {})",
                inputFile.getName(), outputFile.getName(), targetFormat);

        // Normalize format
        String format = targetFormat.toLowerCase();
        if (format.equals("jpg") || format.equals("jpeg")) {
            format = "jpeg";
        }

        try {
            BufferedImage image = ImageIO.read(inputFile);
            if (image == null) {
                logger.error("Failed to read image for mediaId: {}", mediaId);
                throw new IOException("Failed to read image - ImageIO returned null");
            }

            logger.info("Image loaded: {}x{} pixels", image.getWidth(), image.getHeight());

            // Convert to RGB for formats that don't support transparency
            BufferedImage rgbImage = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g2d = rgbImage.createGraphics();
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, rgbImage.getWidth(), rgbImage.getHeight());
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();

            // Ensure output directory exists
            Files.createDirectories(outputFile.getParentFile().toPath());

            // Write image based on format
            boolean writeSuccess = false;
            if (format.equals("jpeg")) {
                logger.info("Writing JPEG with quality compression");
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) {
                    throw new IOException("No JPEG writer available");
                }
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.95f);
                try (FileImageOutputStream output = new FileImageOutputStream(outputFile)) {
                    writer.setOutput(output);
                    writer.write(null, new IIOImage(rgbImage, null, null), param);
                    writer.dispose();
                }
                writeSuccess = outputFile.exists() && outputFile.length() > 0;
            } else {
                logger.info("Writing {} format using ImageIO", format);
                writeSuccess = ImageIO.write(rgbImage, format, outputFile);
            }

            if (!writeSuccess) {
                throw new IOException("ImageIO.write returned false for format: " + format);
            }

            if (!outputFile.exists()) {
                throw new IOException("Output file was not created: " + outputFile.getAbsolutePath());
            }

            if (outputFile.length() == 0) {
                throw new IOException("Output file is empty (0 bytes): " + outputFile.getAbsolutePath());
            }

            logger.info("Image conversion successful: {} bytes written to {}",
                    outputFile.length(), outputFile.getAbsolutePath());

        } catch (IOException e) {
            logger.error("Image conversion failed for mediaId: {}: {}", mediaId, e.getMessage(), e);
            throw new IOException("Image conversion failed: " + e.getMessage(), e);
        }
    }

    private boolean waitForFileReady(File file, int maxRetries, long waitTimeMs) {
        for (int i = 0; i < maxRetries; i++) {
            if (file.exists() && file.length() > 0 && file.canRead()) {
                logger.debug("File ready after {} attempts: {}", i + 1, file.getName());
                return true;
            }
            logger.debug("Waiting for file (attempt {}/{}): exists={}, size={}",
                    i + 1, maxRetries, file.exists(), file.exists() ? file.length() : "N/A");
            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("File not ready after {} attempts: {}", maxRetries, file.getAbsolutePath());
        return false;
    }

    private void cleanupTempDirectory(Path tempDir) {
        try {
            if (!Files.exists(tempDir)) {
                return;
            }
            Files.walk(tempDir)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            logger.debug("Deleted temp file: {}", p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete temp file: {}", p, e);
                        }
                    });
            logger.info("Cleaned up temp directory: {}", tempDir);
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp directory: {}", tempDir, e);
        }
    }

    public ConvertedMedia updateTargetFormat(User user, Long mediaId, String newTargetFormat) throws IOException {
        logger.info("Updating target format for user: {}, mediaId: {}, newTargetFormat: {}",
                user.getId(), mediaId, newTargetFormat);

        ConvertedMedia convertedMedia = convertedMediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found"));

        if (!convertedMedia.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not authorized to update this media");
        }

        String mediaType = convertedMedia.getMediaType();
        String formatUpper = newTargetFormat.toUpperCase();
        if (mediaType.equals("VIDEO") && !VIDEO_FORMATS.contains(formatUpper)) {
            throw new IllegalArgumentException("Invalid video format. Supported: " +
                    String.join(", ", VIDEO_FORMATS));
        }
        if (mediaType.equals("IMAGE") && !IMAGE_FORMATS.contains(formatUpper)) {
            throw new IllegalArgumentException("Invalid image format. Supported: " +
                    String.join(", ", IMAGE_FORMATS));
        }

        convertedMedia.setTargetFormat(formatUpper);
        convertedMediaRepository.save(convertedMedia);
        return convertedMedia;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "media_" + System.currentTimeMillis();
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getFileNameWithoutExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }
    @Transactional
    public void deleteMedia(User user, Long mediaId) throws IOException {
        logger.info("Deleting converted media for user: {}, mediaId: {}", user.getId(), mediaId);

        ConvertedMedia convertedMedia = convertedMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!convertedMedia.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to delete media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to delete this media");
        }

        // Don't allow deletion if media is being processed
        if ("PROCESSING".equals(convertedMedia.getStatus())) {
            throw new IllegalStateException("Cannot delete media while it is being processed");
        }

        // Delete files from R2
        try {
            if (convertedMedia.getOriginalPath() != null) {
                cloudflareR2Service.deleteFile(convertedMedia.getOriginalPath());
                logger.info("Deleted original file from R2: {}", convertedMedia.getOriginalPath());
            }

            if (convertedMedia.getProcessedPath() != null) {
                cloudflareR2Service.deleteFile(convertedMedia.getProcessedPath());
                logger.info("Deleted processed file from R2: {}", convertedMedia.getProcessedPath());
            }
        } catch (IOException e) {
            logger.warn("Failed to delete some files from R2 for mediaId: {}", mediaId, e);
            // Continue with DB deletion even if R2 deletion fails
        }

        // Delete from database
        convertedMediaRepository.delete(convertedMedia);
        logger.info("Successfully deleted converted media for user: {}, mediaId: {}", user.getId(), mediaId);
    }
}