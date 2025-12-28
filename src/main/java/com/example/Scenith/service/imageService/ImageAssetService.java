package com.example.Scenith.service.imageService;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ImageAsset;
import com.example.Scenith.repository.imagerepository.ImageAssetRepository;
import com.example.Scenith.service.CloudflareR2Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageAssetService {

    private static final Logger logger = LoggerFactory.getLogger(ImageAssetService.class);

    private final ImageAssetRepository imageAssetRepository;
    private final CloudflareR2Service cloudflareR2Service;

    @Value("${app.background-removal-script-path:/app/scripts/remove_background.py}")
    private String backgroundRemovalScriptPath;

    @Value("${python.path:/usr/local/bin/python3}")
    private String pythonPath;

    public ImageAssetService(ImageAssetRepository imageAssetRepository,
                            CloudflareR2Service cloudflareR2Service) {
        this.imageAssetRepository = imageAssetRepository;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    /**
     * Upload asset (image, icon, background)
     */
    public ImageAsset uploadAsset(User user, MultipartFile file, String assetType) throws IOException {
        logger.info("Uploading asset for user: {}, type: {}", user.getId(), assetType);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        
        // Validate image type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        // Create temporary file for processing
        String tempFileName = "asset_temp_" + System.currentTimeMillis() + extension;
        String tempFilePath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + tempFileName;
        File tempFile = new File(tempFilePath);

        // Ensure parent directory exists
        Path tempDir = tempFile.toPath().getParent();
        Files.createDirectories(tempDir);

        try {
            // Save to temporary file
            file.transferTo(tempFile);

            // Get image dimensions
            BufferedImage image = ImageIO.read(tempFile);
            int width = 0;
            int height = 0;
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }

            // Define R2 path - maintain same folder structure
            String userR2Dir = "image_editor/" + user.getId() + "/assets";
            String uniqueFilename = UUID.randomUUID().toString() + extension;
            String assetR2Path = userR2Dir + "/" + uniqueFilename;

            // Upload to R2
            cloudflareR2Service.uploadFile(assetR2Path, tempFile);
            logger.info("Uploaded asset to R2: {}", assetR2Path);

            // Generate URLs
            Map<String, String> urls = cloudflareR2Service.generateUrls(assetR2Path, 3600);

            // Save to database
            ImageAsset asset = new ImageAsset();
            asset.setUser(user);
            asset.setAssetName(originalFilename);
            asset.setAssetType(assetType != null ? assetType.toUpperCase() : "IMAGE");
            asset.setOriginalFilename(originalFilename);
            asset.setFilePath(assetR2Path);
            asset.setCdnUrl(urls.get("cdnUrl"));
            asset.setFileSize(file.getSize());
            asset.setMimeType(contentType);
            asset.setWidth(width);
            asset.setHeight(height);

            imageAssetRepository.save(asset);
            logger.info("Asset uploaded successfully: {}", asset.getId());

            return asset;

        } finally {
            // Clean up temporary file
            if (tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                    logger.debug("Deleted temporary asset file: {}", tempFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary asset file: {}", tempFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * Get all assets for user
     */
    public List<ImageAsset> getUserAssets(User user) {
        return imageAssetRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Get assets by type
     */
    public List<ImageAsset> getUserAssetsByType(User user, String assetType) {
        return imageAssetRepository.findByUserAndAssetType(user, assetType.toUpperCase());
    }

    /**
     * Get single asset
     */
    public ImageAsset getAssetById(User user, Long assetId) {
        return imageAssetRepository.findByIdAndUser(assetId, user)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found"));
    }

    /**
     * Delete asset
     */
    public void deleteAsset(User user, Long assetId) throws IOException {
        ImageAsset asset = imageAssetRepository.findByIdAndUser(assetId, user)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found"));

        // Delete file from R2
        try {
            cloudflareR2Service.deleteFile(asset.getFilePath());
            logger.info("Deleted asset from R2: {}", asset.getFilePath());
        } catch (Exception e) {
            logger.warn("Failed to delete asset from R2: {}", asset.getFilePath(), e);
        }

        // Delete from database
        imageAssetRepository.delete(asset);
        logger.info("Asset deleted: {}", assetId);
    }

    /**
     * Remove background from an existing uploaded asset (only for type IMAGE)
     * Creates and returns a NEW ImageAsset with transparent background
     * Fully server-ready — uses exact same paths and patterns as your production container
     */
    public ImageAsset removeBackground(User user, Long assetId) throws IOException, InterruptedException {
        logger.info("Starting background removal for asset ID: {} (user: {})", assetId, user.getId());

        ImageAsset originalAsset = imageAssetRepository.findByIdAndUser(assetId, user)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found or does not belong to user"));

        if (!"IMAGE".equalsIgnoreCase(originalAsset.getAssetType())) {
            throw new IllegalArgumentException("Background removal is only supported for assets of type IMAGE");
        }

        // Use the exact same temporary directory pattern as uploadAsset()
        String tempDir = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor";
        Path tempDirPath = Path.of(tempDir);
        Files.createDirectories(tempDirPath);

        String inputExtension = originalAsset.getOriginalFilename()
                .substring(originalAsset.getOriginalFilename().lastIndexOf('.'));
        String inputTempName = "bgremove_input_" + System.currentTimeMillis() + inputExtension;
        Path inputFilePath = tempDirPath.resolve(inputTempName);
        File inputFile = inputFilePath.toFile();

        String outputTempName = "bg_removed_" + UUID.randomUUID() + ".png";
        Path outputFilePath = tempDirPath.resolve(outputTempName);
        File outputFile = outputFilePath.toFile();

        try {
            // CORRECT: Pass String path, not File object
            cloudflareR2Service.downloadFile(originalAsset.getFilePath(), inputFile.getAbsolutePath());
            logger.debug("Downloaded from R2: {} → {}", originalAsset.getFilePath(), inputFile.getAbsolutePath());
            // 2. Run the Python background removal script (exact paths from your Dockerfile & properties)
            List<String> command = Arrays.asList(
                    pythonPath,                    // → /usr/local/bin/python3.11 (injected from properties)
                    backgroundRemovalScriptPath,    // → /app/scripts/remove_background.py (injected)
                    inputFile.getAbsolutePath(),
                    outputFile.getAbsolutePath()
            );

            logger.debug("Executing background removal: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Capture script output for better debugging
            StringBuilder scriptLog = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    scriptLog.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("Background removal failed (exit code {}): {}", exitCode, scriptLog.toString());
                throw new IOException("Background removal script failed with exit code " + exitCode);
            }

            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new IOException("Background removal did not produce output file");
            }

            // 3. Read dimensions of the new transparent image
            BufferedImage processedImage = ImageIO.read(outputFile);
            int width = processedImage != null ? processedImage.getWidth() : 0;
            int height = processedImage != null ? processedImage.getHeight() : 0;

            // 4. Upload processed PNG to R2 (same folder structure as uploadAsset())
            String userR2Dir = "image_editor/" + user.getId() + "/assets";
            String uniqueFilename = UUID.randomUUID() + ".png";
            String newR2Path = userR2Dir + "/" + uniqueFilename;

            cloudflareR2Service.uploadFile(newR2Path, outputFile);
            logger.info("Uploaded background-removed image to R2: {}", newR2Path);

            Map<String, String> urls = cloudflareR2Service.generateUrls(newR2Path, 3600);

            // 5. Save new asset in database
            ImageAsset newAsset = new ImageAsset();
            newAsset.setUser(user);
            newAsset.setAssetName("BG Removed - " + originalAsset.getAssetName());
            newAsset.setAssetType("IMAGE");
            newAsset.setOriginalFilename("bg_removed_" + originalAsset.getOriginalFilename().replaceFirst("[.][^.]+$", "") + ".png");
            newAsset.setFilePath(newR2Path);
            newAsset.setCdnUrl(urls.get("cdnUrl"));
            newAsset.setFileSize(outputFile.length());
            newAsset.setMimeType("image/png");
            newAsset.setWidth(width);
            newAsset.setHeight(height);

            imageAssetRepository.save(newAsset);
            logger.info("Background removal completed → new asset created with ID: {}", newAsset.getId());

            return newAsset;

        } finally {
            // Always clean up both temp files (very important in container!)
            try {
                Files.deleteIfExists(inputFilePath);
                Files.deleteIfExists(outputFilePath);
                logger.debug("Cleaned up temp files for background removal");
            } catch (Exception e) {
                logger.warn("Failed to delete temporary files during background removal cleanup", e);
            }
        }
    }
}