package com.example.Scenith.service.imageService;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.ElementDownload;
import com.example.Scenith.entity.imageentity.ImageElement;
import com.example.Scenith.repository.imagerepository.ElementDownloadRepository;
import com.example.Scenith.repository.imagerepository.ImageElementRepository;
import com.example.Scenith.service.CloudflareR2Service;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.JPEGTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ElementDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(ElementDownloadService.class);

    private final ElementDownloadRepository downloadRepository;
    private final ImageElementRepository elementRepository;
    private final CloudflareR2Service cloudflareR2Service;

    public ElementDownloadService(ElementDownloadRepository downloadRepository,
                                  ImageElementRepository elementRepository,
                                  CloudflareR2Service cloudflareR2Service) {
        this.downloadRepository = downloadRepository;
        this.elementRepository = elementRepository;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    /**
     * Download element in specified format
     */
    @Transactional
    public DownloadResult downloadElement(Long elementId, String format, String resolution,
                                          String color, User user, String ipAddress) throws IOException {
        logger.info("Downloading element {} in format {} | resolution {} | color {}",
                elementId, format, resolution, color);

        // 1. Validate element exists
        ImageElement element = elementRepository.findById(elementId)
                .orElseThrow(() -> new IllegalArgumentException("Element not found"));

        // 2. Record download (moved early - good practice)
        recordDownload(elementId, user != null ? user.getId() : null, format, resolution, ipAddress);

        // 3. Get R2 path
        String r2Path = element.getFilePath();
        if (r2Path == null || r2Path.trim().isEmpty()) {
            throw new IllegalStateException("Element has no file path in storage");
        }

        // 4. Verify file really exists in R2
        if (!cloudflareR2Service.fileExists(r2Path)) {
            throw new IOException("File not found in Cloudflare R2: " + r2Path);
        }

        // 5. Prepare temporary file (with safe naming)
        String tempFileName = String.format("element_dl_%d_%d%s",
                elementId,
                System.currentTimeMillis(),
                getExtension(element.getFileFormat()));

        String tempDir = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor";
        Files.createDirectories(Paths.get(tempDir)); // make sure directory exists

        Path tempPath = Paths.get(tempDir, tempFileName);
        File tempFile = null;

        try {
            // Download from R2 → local temp file
            tempFile = cloudflareR2Service.downloadFile(r2Path, tempPath.toString());
            logger.debug("Downloaded from R2 to temp: {}", tempFile.getAbsolutePath());

            format = (format != null ? format.trim().toUpperCase() : "PNG");

            return switch (format) {
                case "SVG" -> downloadSvg(tempPath, element.getName(), color);
                case "PNG" -> downloadAsPng(tempPath, element, resolution, color);
                case "JPG", "JPEG" -> downloadAsJpg(tempPath, element, resolution, color);
                default -> throw new IllegalArgumentException("Unsupported download format: " + format);
            };

        } finally {
            // Important: always try to clean up
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                    logger.debug("Cleaned up temp file: {}", tempFile);
                } catch (Exception e) {
                    logger.warn("Failed to delete temporary file: {} → {}", tempFile, e.getMessage());
                }
            }
        }
    }

    /**
     * Download SVG directly
     */
    private DownloadResult downloadSvg(Path filePath, String elementName) throws IOException {
        byte[] fileContent = Files.readAllBytes(filePath);
        String filename = sanitizeFilename(elementName) + ".svg";
        return new DownloadResult(new ByteArrayResource(fileContent), filename, "image/svg+xml");
    }

    /**
     * Convert and download as PNG
     */
    private DownloadResult downloadAsPng(Path filePath, ImageElement element, String resolution, String color)
            throws IOException {
        int[] dimensions = parseResolution(resolution, element);
        int width = dimensions[0];
        int height = dimensions[1];

        byte[] pngData;

        if (element.getFileFormat().equalsIgnoreCase("SVG")) {
            // Convert SVG to PNG using Batik (with color if provided)
            pngData = convertSvgToPng(filePath, width, height, color);
        } else {
            // Convert raster image to PNG
            pngData = convertRasterToPng(filePath, width, height);
        }

        String filename = sanitizeFilename(element.getName()) + "_" + width + "x" + height + ".png";
        return new DownloadResult(new ByteArrayResource(pngData), filename, "image/png");
    }

    /**
     * Convert and download as JPG
     */
    private DownloadResult downloadAsJpg(Path filePath, ImageElement element, String resolution, String color)
            throws IOException {
        int[] dimensions = parseResolution(resolution, element);
        int width = dimensions[0];
        int height = dimensions[1];

        byte[] jpgData;

        if (element.getFileFormat().equalsIgnoreCase("SVG")) {
            // Convert SVG to JPG using Batik (with color if provided)
            jpgData = convertSvgToJpg(filePath, width, height, color);
        } else {
            // Convert raster image to JPG
            jpgData = convertRasterToJpg(filePath, width, height);
        }

        String filename = sanitizeFilename(element.getName()) + "_" + width + "x" + height + ".jpg";
        return new DownloadResult(new ByteArrayResource(jpgData), filename, "image/jpeg");
    }

    /**
     * Convert SVG to PNG using Apache Batik
     */
    private byte[] convertSvgToPng(Path svgPath, int width, int height, String color) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Read and potentially modify SVG content
            String svgContent = Files.readString(svgPath);

            // Apply color if provided
            if (color != null && !color.isEmpty()) {
                if (!color.startsWith("#")) {
                    color = "#" + color;
                }
                svgContent = svgContent.replaceAll("fill=\"#[0-9a-fA-F]{3,6}\"", "fill=\"" + color + "\"");
                svgContent = svgContent.replaceAll("stroke=\"#[0-9a-fA-F]{3,6}\"", "stroke=\"" + color + "\"");
                svgContent = svgContent.replaceAll("fill:#[0-9a-fA-F]{3,6}", "fill:" + color);
                svgContent = svgContent.replaceAll("stroke:#[0-9a-fA-F]{3,6}", "stroke:" + color);
            }

            InputStream inputStream = new ByteArrayInputStream(svgContent.getBytes());

            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);

            TranscoderInput input = new TranscoderInput(inputStream);
            TranscoderOutput output = new TranscoderOutput(outputStream);

            transcoder.transcode(input, output);
            return outputStream.toByteArray();

        } catch (TranscoderException e) {
            throw new IOException("Failed to convert SVG to PNG", e);
        }
    }

    private byte[] convertSvgToJpg(Path svgPath, int width, int height, String color) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Read and potentially modify SVG content
            String svgContent = Files.readString(svgPath);

            // Apply color if provided
            if (color != null && !color.isEmpty()) {
                if (!color.startsWith("#")) {
                    color = "#" + color;
                }
                svgContent = svgContent.replaceAll("fill=\"#[0-9a-fA-F]{3,6}\"", "fill=\"" + color + "\"");
                svgContent = svgContent.replaceAll("stroke=\"#[0-9a-fA-F]{3,6}\"", "stroke=\"" + color + "\"");
                svgContent = svgContent.replaceAll("fill:#[0-9a-fA-F]{3,6}", "fill:" + color);
                svgContent = svgContent.replaceAll("stroke:#[0-9a-fA-F]{3,6}", "stroke:" + color);
            }

            InputStream inputStream = new ByteArrayInputStream(svgContent.getBytes());

            JPEGTranscoder transcoder = new JPEGTranscoder();
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_HEIGHT, (float) height);
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 0.95f);
            transcoder.addTranscodingHint(JPEGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);

            TranscoderInput input = new TranscoderInput(inputStream);
            TranscoderOutput output = new TranscoderOutput(outputStream);

            transcoder.transcode(input, output);
            return outputStream.toByteArray();

        } catch (TranscoderException e) {
            throw new IOException("Failed to convert SVG to JPG", e);
        }
    }

    /**
     * Convert raster image to PNG
     */
    private byte[] convertRasterToPng(Path imagePath, int width, int height) throws IOException {
        BufferedImage originalImage = ImageIO.read(imagePath.toFile());
        if (originalImage == null) {
            throw new IOException("Failed to read image file");
        }
        BufferedImage resizedImage = resizeImage(originalImage, width, height);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(resizedImage, "PNG", outputStream);
        return outputStream.toByteArray();
    }

    /**
     * Convert raster image to JPG
     */
    private byte[] convertRasterToJpg(Path imagePath, int width, int height) throws IOException {
        BufferedImage originalImage = ImageIO.read(imagePath.toFile());
        if (originalImage == null) {
            throw new IOException("Failed to read image file");
        }
        BufferedImage resizedImage = resizeImage(originalImage, width, height);

        // Convert to RGB (JPG doesn't support transparency)
        BufferedImage rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgbImage.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.drawImage(resizedImage, 0, 0, null);
        g.dispose();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(rgbImage, "JPG", outputStream);
        return outputStream.toByteArray();
    }

    /**
     * Resize image maintaining aspect ratio
     */
    private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resizedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resizedImage;
    }

    /**
     * Parse resolution string
     */
    private int[] parseResolution(String resolution, ImageElement element) {
        if (resolution == null || resolution.isEmpty()) {
            // Default resolution
            return new int[]{512, 512};
        }

        String[] parts = resolution.split("x");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid resolution format. Use format: 512x512");
        }

        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);

            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                throw new IllegalArgumentException("Resolution must be between 1 and 4096");
            }

            return new int[]{width, height};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid resolution values");
        }
    }

    /**
     * Record download statistics
     */
    @Transactional
    private void recordDownload(Long elementId, Long userId, String format,
                                String resolution, String ipAddress) {
        // Save to download history
        ElementDownload download = new ElementDownload();
        download.setElementId(elementId);
        download.setUserId(userId);
        download.setDownloadFormat(format);
        download.setResolution(resolution);
        downloadRepository.save(download);

        // Increment download count in element table
        ImageElement element = elementRepository.findById(elementId)
                .orElseThrow(() -> new IllegalArgumentException("Element not found"));
        element.setDownloadCount(element.getDownloadCount() + 1);
        elementRepository.save(element);

        logger.info("Download recorded for element {} in format {}", elementId, format);
    }

    /**
     * Get download count for element (from element table for better performance)
     */
    public Long getDownloadCount(Long elementId) {
        ImageElement element = elementRepository.findById(elementId)
                .orElseThrow(() -> new IllegalArgumentException("Element not found"));
        return element.getDownloadCount();
    }

    /**
     * Get file extension from format
     */
    private String getExtension(String format) {
        if (format == null) {
            return ".tmp";
        }
        format = format.toLowerCase();
        switch (format) {
            case "svg":
                return ".svg";
            case "png":
                return ".png";
            case "jpg":
            case "jpeg":
                return ".jpg";
            default:
                return ".tmp";
        }
    }

    /**
     * Sanitize filename
     */
    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    /**
     * Download result wrapper
     */
    public static class DownloadResult {
        private final Resource resource;
        private final String filename;
        private final String contentType;

        public DownloadResult(Resource resource, String filename, String contentType) {
            this.resource = resource;
            this.filename = filename;
            this.contentType = contentType;
        }

        public Resource getResource() { return resource; }
        public String getFilename() { return filename; }
        public String getContentType() { return contentType; }
    }

    /**
     * Download SVG with optional color replacement
     */
    private DownloadResult downloadSvg(Path filePath, String elementName, String color) throws IOException {
        String svgContent = Files.readString(filePath);

        // If color is provided, replace fill and stroke colors
        if (color != null && !color.isEmpty()) {
            // Ensure color has # prefix
            if (!color.startsWith("#")) {
                color = "#" + color;
            }

            // Replace common SVG color attributes
            svgContent = svgContent.replaceAll("fill=\"#[0-9a-fA-F]{3,6}\"", "fill=\"" + color + "\"");
            svgContent = svgContent.replaceAll("stroke=\"#[0-9a-fA-F]{3,6}\"", "stroke=\"" + color + "\"");

            // Also handle fill: and stroke: in style attributes
            svgContent = svgContent.replaceAll("fill:#[0-9a-fA-F]{3,6}", "fill:" + color);
            svgContent = svgContent.replaceAll("stroke:#[0-9a-fA-F]{3,6}", "stroke:" + color);
        }

        byte[] fileContent = svgContent.getBytes();
        String filename = sanitizeFilename(elementName) + ".svg";
        return new DownloadResult(new ByteArrayResource(fileContent), filename, "image/svg+xml");
    }
}