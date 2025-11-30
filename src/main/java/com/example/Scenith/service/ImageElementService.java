package com.example.Scenith.service;

import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.repository.imagerepository.ImageElementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageElementService {

    private static final Logger logger = LoggerFactory.getLogger(ImageElementService.class);

    private final ImageElementRepository elementRepository;
    private final CloudflareR2Service cloudflareR2Service;

    public ImageElementService(ImageElementRepository elementRepository,
                               CloudflareR2Service cloudflareR2Service) {
        this.elementRepository = elementRepository;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    /**
     * Upload new element (Admin only)
     */
    @Transactional
    public ImageElement uploadElement(MultipartFile file, String name, String category, String tags) throws IOException {
        logger.info("Uploading element: {}", name);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("Invalid filename");
        }

        // Get file extension
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!extension.matches("\\.(png|jpg|jpeg|svg)")) {
            throw new IllegalArgumentException("Only PNG, JPG, and SVG files are allowed");
        }

        // Create temporary file for processing
        String tempFileName = "element_temp_" + System.currentTimeMillis() + extension;
        String tempFilePath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + tempFileName;
        File tempFile = new File(tempFilePath);

        // Ensure parent directory exists
        Path tempDir = tempFile.toPath().getParent();
        Files.createDirectories(tempDir);
        if (!Files.isWritable(tempDir)) {
            logger.error("Temporary directory is not writable: {}", tempDir);
            throw new IOException("Cannot write to temporary directory: " + tempDir);
        }

        try {
            // Save to temporary file
            Files.copy(file.getInputStream(), tempFile.toPath());
            logger.debug("Created temporary element file: {}", tempFile.getAbsolutePath());

            // Get image dimensions
            Integer width = null;
            Integer height = null;
            if (!extension.equals(".svg")) {
                try {
                    BufferedImage img = ImageIO.read(tempFile);
                    if (img != null) {
                        width = img.getWidth();
                        height = img.getHeight();
                        logger.debug("Image dimensions: {}x{}", width, height);
                    }
                } catch (Exception e) {
                    logger.warn("Could not read image dimensions: {}", e.getMessage());
                }
            }

            // Define R2 path - maintain same folder structure as local
            String uniqueFilename = UUID.randomUUID().toString() + extension;
            String elementR2Path = "image_editor/elements/" + uniqueFilename;

            // Upload to R2
            cloudflareR2Service.uploadFile(elementR2Path, tempFile);
            logger.info("Uploaded element to R2: {}", elementR2Path);

            // Generate URLs
            Map<String, String> urls = cloudflareR2Service.generateUrls(elementR2Path, 3600);

            // Create element record
            ImageElement element = new ImageElement();
            element.setName(name != null ? name : originalFilename);
            element.setCategory(category != null ? category : "general");
            element.setFilePath(elementR2Path);
            element.setCdnUrl(urls.get("cdnUrl"));
            element.setFileFormat(extension.substring(1).toUpperCase());
            element.setWidth(width);
            element.setHeight(height);
            element.setFileSize(file.getSize());
            element.setTags(tags);
            element.setIsActive(true);

            elementRepository.save(element);
            logger.info("Element uploaded successfully: {}", element.getId());

            return element;

        } finally {
            // Clean up temporary file
            if (tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                    logger.debug("Deleted temporary element file: {}", tempFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary element file: {}, error: {}", tempFile.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    /**
     * Get all active elements
     */
    public List<ImageElement> getAllActiveElements() {
        return elementRepository.findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc();
    }

    /**
     * Get elements by category
     */
    public List<ImageElement> getElementsByCategory(String category) {
        return elementRepository.findByCategoryAndIsActiveTrueOrderByDisplayOrderAsc(category);
    }

    /**
     * Get element by ID
     */
    public ImageElement getElementById(Long id) {
        return elementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Element not found"));
    }

    /**
     * Update element
     */
    @Transactional
    public ImageElement updateElement(Long id, String name, String category, String tags, Boolean isActive, Integer displayOrder) {
        ImageElement element = getElementById(id);

        if (name != null) element.setName(name);
        if (category != null) element.setCategory(category);
        if (tags != null) element.setTags(tags);
        if (isActive != null) element.setIsActive(isActive);
        if (displayOrder != null) element.setDisplayOrder(displayOrder);

        return elementRepository.save(element);
    }

    /**
     * Delete element
     */
    @Transactional
    public void deleteElement(Long id) throws IOException {
        ImageElement element = getElementById(id);

        // Delete file from R2
        try {
            cloudflareR2Service.deleteFile(element.getFilePath());
            logger.info("Deleted element from R2: {}", element.getFilePath());
        } catch (Exception e) {
            logger.warn("Failed to delete element from R2: {}, error: {}", element.getFilePath(), e.getMessage());
            // Continue with database deletion even if R2 deletion fails
        }

        // Delete record from database
        elementRepository.delete(element);
        logger.info("Element deleted from database: {}", id);
    }

    /**
     * Get all elements (including inactive) - Admin only
     */
    public List<ImageElement> getAllElements() {
        return elementRepository.findAll();
    }
}