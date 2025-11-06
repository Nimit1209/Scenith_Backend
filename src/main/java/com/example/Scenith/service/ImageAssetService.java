package com.example.Scenith.service;

import com.example.Scenith.entity.ImageAsset;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.ImageAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

@Service
public class ImageAssetService {

    private static final Logger logger = LoggerFactory.getLogger(ImageAssetService.class);

    @Value("${app.base-dir:/temp}")
    private String baseDir;

    private final ImageAssetRepository imageAssetRepository;
    private final CloudflareR2Service cloudflareR2Service;

    public ImageAssetService(ImageAssetRepository imageAssetRepository, CloudflareR2Service cloudflareR2Service) {
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
        if (originalFilename == null || originalFilename.lastIndexOf(".") == -1) {
            throw new IllegalArgumentException("Invalid file name");
        }
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));

        // Validate image type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        // Generate unique filename
        String uniqueFilename = UUID.randomUUID().toString() + extension;

        // Define R2 path
        String r2Path = String.format("image_editor/%d/assets/%s", user.getId(), uniqueFilename);

        // Upload directly to Cloudflare R2 (no temp file)
        cloudflareR2Service.uploadFile(file, r2Path);
        logger.info("Uploaded asset to R2: {}", r2Path);

        // Generate CDN URL (1 year expiration)
        String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 31536000);

        // Read image dimensions from InputStream (no disk I/O)
        int width = 0;
        int height = 0;
        try (var inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (Exception e) {
            logger.warn("Failed to read image dimensions for file: {}", originalFilename, e);
            // Dimensions remain 0 – acceptable fallback
        }

        // Save to database
        ImageAsset asset = new ImageAsset();
        asset.setUser(user);
        asset.setAssetName(originalFilename);
        asset.setAssetType(assetType != null ? assetType.toUpperCase() : "IMAGE");
        asset.setOriginalFilename(originalFilename);
        asset.setFilePath(r2Path);
        asset.setCdnUrl(cdnUrl);
        asset.setFileSize(file.getSize());
        asset.setMimeType(contentType);
        asset.setWidth(width);
        asset.setHeight(height);

        imageAssetRepository.save(asset);
        logger.info("Asset uploaded successfully: {}", asset.getId());

        return asset;
    }

    /**
     * Get all assets for user
     */
    public List<ImageAsset> getUserAssets(User user) {
        List<ImageAsset> assets = imageAssetRepository.findByUserOrderByCreatedAtDesc(user);

        // Refresh CDN URLs if needed
        for (ImageAsset asset : assets) {
            try {
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(asset.getFilePath(), 31536000);
                asset.setCdnUrl(cdnUrl);
                imageAssetRepository.save(asset);
            } catch (Exception e) {
                logger.warn("Failed to refresh CDN URL for asset: {}, path: {}", asset.getId(), asset.getFilePath());
            }
        }

        return assets;
    }

    /**
     * Get assets by type
     */
    public List<ImageAsset> getUserAssetsByType(User user, String assetType) {
        List<ImageAsset> assets = imageAssetRepository.findByUserAndAssetType(user, assetType.toUpperCase());

        // Refresh CDN URLs
        for (ImageAsset asset : assets) {
            try {
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(asset.getFilePath(), 31536000);
                asset.setCdnUrl(cdnUrl);
                imageAssetRepository.save(asset);
            } catch (Exception e) {
                logger.warn("Failed to refresh CDN URL for asset: {}", asset.getId());
            }
        }

        return assets;
    }

    /**
     * Get single asset
     */
    public ImageAsset getAssetById(User user, Long assetId) {
        ImageAsset asset = imageAssetRepository.findByIdAndUser(assetId, user)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found"));

        // Refresh CDN URL
        try {
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(asset.getFilePath(), 31536000);
            asset.setCdnUrl(cdnUrl);
            imageAssetRepository.save(asset);
        } catch (Exception e) {
            logger.warn("Failed to refresh CDN URL for asset: {}", asset.getId());
        }

        return asset;
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
            logger.error("Failed to delete asset from R2: {}", asset.getFilePath(), e);
            throw new IOException("Failed to delete asset from R2", e);
        }

        // Delete from database
        imageAssetRepository.delete(asset);
        logger.info("Asset deleted: {}", assetId);
    }
}