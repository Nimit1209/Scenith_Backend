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
import java.util.List;
import java.util.UUID;

@Service
public class ImageAssetService {

    private static final Logger logger = LoggerFactory.getLogger(ImageAssetService.class);

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    private final ImageAssetRepository imageAssetRepository;

    public ImageAssetService(ImageAssetRepository imageAssetRepository) {
        this.imageAssetRepository = imageAssetRepository;
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

        // Create asset directory
        String assetDirPath = baseDir + File.separator + "image_editor" + File.separator + 
                            user.getId() + File.separator + "assets";
        File assetDir = new File(assetDirPath);
        if (!assetDir.exists() && !assetDir.mkdirs()) {
            throw new IOException("Failed to create asset directory");
        }

        // Generate unique filename
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        File assetFile = new File(assetDir, uniqueFilename);
        file.transferTo(assetFile);

        // Get image dimensions
        BufferedImage image = ImageIO.read(assetFile);
        int width = 0;
        int height = 0;
        if (image != null) {
            width = image.getWidth();
            height = image.getHeight();
        }

        // Save to database
        ImageAsset asset = new ImageAsset();
        asset.setUser(user);
        asset.setAssetName(originalFilename);
        asset.setAssetType(assetType != null ? assetType.toUpperCase() : "IMAGE");
        asset.setOriginalFilename(originalFilename);
        asset.setFilePath("image_editor/" + user.getId() + "/assets/" + uniqueFilename);
        asset.setCdnUrl("http://localhost:8080/" + asset.getFilePath());
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

        // Delete file
        String fullPath = baseDir + File.separator + asset.getFilePath();
        File file = new File(fullPath);
        if (file.exists()) {
            file.delete();
        }

        // Delete from database
        imageAssetRepository.delete(asset);
        logger.info("Asset deleted: {}", assetId);
    }
}