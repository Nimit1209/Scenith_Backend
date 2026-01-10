package com.example.Scenith.service.imageService;

import com.example.Scenith.dto.imagedto.CanvasDTO;
import com.example.Scenith.dto.imagedto.DesignDTO;
import com.example.Scenith.dto.imagedto.LayerDTO;
import com.example.Scenith.dto.imagedto.ShadowDTO;
import com.example.Scenith.service.CloudflareR2Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ImageRenderService {

    private static final Logger logger = LoggerFactory.getLogger(ImageRenderService.class);

    @Value("${app.imagemagick-path:/usr/local/bin/magick}")
    private String imageMagickPath;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    private final ObjectMapper objectMapper;
    private final CloudflareR2Service cloudflareR2Service;

    public ImageRenderService(ObjectMapper objectMapper,
                              CloudflareR2Service cloudflareR2Service) {
        this.objectMapper = objectMapper;
        this.cloudflareR2Service = cloudflareR2Service;
    }


    /**
     * Main method to render design JSON to image and upload to R2
     * Returns the R2 path of the uploaded file
     */
    public String renderDesign(String designJson, String format, Integer quality, Long userId, Long projectId)
            throws IOException, InterruptedException {

        logger.info("Starting render for project: {}, format: {}", projectId, format);
        System.setProperty("MAGICK_CONFIGURE_PATH", "");

        // Parse design JSON
        DesignDTO design = objectMapper.readValue(designJson, DesignDTO.class);

        // Create temp directory for processing
        String tempDirPath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" +
                File.separator + "image_render_" + userId + "_" + projectId + "_" + System.currentTimeMillis();

        File tempDir = new File(tempDirPath);

        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory: " + tempDirPath);
        }

        File finalExportFile = null;

        try {
            // Sort layers by zIndex
            List<LayerDTO> sortedLayers = new ArrayList<>(design.getLayers());
            sortedLayers.sort(Comparator.comparingInt(l -> l.getZIndex() != null ? l.getZIndex() : 0));

            // Step 1: Create base canvas
            String baseCanvasPath = tempDirPath + File.separator + "base_canvas.png";
            createBaseCanvas(design.getCanvas(), baseCanvasPath);

            // Step 2: Process each layer and composite
            String currentImage = baseCanvasPath;
            int layerIndex = 0;

            for (LayerDTO layer : sortedLayers) {
                String layerOutputPath = tempDirPath + File.separator + "layer_" + layerIndex + "_composite.png";
                currentImage = processAndCompositeLayer(layer, currentImage, layerOutputPath, tempDirPath,
                        design.getCanvas().getWidth(), design.getCanvas().getHeight());
                layerIndex++;
            }

            // Step 3: Export final image in desired format
            String finalFileName = "export_" + System.currentTimeMillis() + "." + format.toLowerCase();
            String finalTempPath = tempDirPath + File.separator + finalFileName;

            exportFinalImage(currentImage, finalTempPath, format, quality);

            finalExportFile = new File(finalTempPath);

            // Step 4: Upload to R2
            String exportR2Path = "image_editor/" + userId + "/exports/project_" + projectId + "_" +
                    System.currentTimeMillis() + "." + format.toLowerCase();

            cloudflareR2Service.uploadFile(exportR2Path, finalExportFile);
            logger.info("Uploaded rendered image to R2: {}", exportR2Path);

            return exportR2Path;

        } finally {
            // Cleanup temp directory
            cleanupTempDirectory(tempDir);
        }
    }
    /**
     * Create base canvas with background color
     */
    private void createBaseCanvas(CanvasDTO canvas, String outputPath) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        Boolean isTransparent = canvas.getTransparent();
        if (isTransparent != null && isTransparent) {
            command.add("-size");
            command.add(canvas.getWidth() + "x" + canvas.getHeight());
            command.add("xc:transparent");
        } else {
            String bgColor = canvas.getBackgroundColor() != null ? canvas.getBackgroundColor() : "#FFFFFF";
            command.add("-size");
            command.add(canvas.getWidth() + "x" + canvas.getHeight());
            command.add("xc:" + bgColor);
        }
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");

        command.add("PNG32:" + outputPath);

        executeImageMagickCommand(command, "Create base canvas");
    }


    /**
     * Process single layer and composite it onto current image
     */
    private String processAndCompositeLayer(LayerDTO layer, String baseImagePath, String outputPath,
                                            String tempDirPath, Integer canvasWidth, Integer canvasHeight)
            throws IOException, InterruptedException {

        logger.debug("Processing layer: {} of type: {}", layer.getId(), layer.getType());

        String layerImagePath;

        switch (layer.getType().toLowerCase()) {
            case "image":
                layerImagePath = processImageLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "text":
                layerImagePath = processTextLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "shape":
                layerImagePath = processShapeLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            case "background":
                layerImagePath = processBackgroundLayer(layer, tempDirPath, canvasWidth, canvasHeight);
                break;
            default:
                logger.warn("Unknown layer type: {}, skipping", layer.getType());
                return baseImagePath;
        }

        // Composite layer onto base image
        compositeImages(baseImagePath, layerImagePath, outputPath, layer);

        // Delete intermediate layer file
        Files.deleteIfExists(Paths.get(layerImagePath));

        return outputPath;
    }

    private String processImageLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight)
            throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_image.png";
        String sourceImagePath = downloadOrCopyImage(layer.getSrc(), tempDirPath, layer.getId());

        boolean isSvg = sourceImagePath.toLowerCase().endsWith(".svg");

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);

        // For SVG, specify density BEFORE reading the file
        if (isSvg) {
            command.add("-density");
            command.add("300");  // High DPI for quality
            command.add("-background");
            command.add("transparent");
        }

        command.add(sourceImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");

        // Get dimensions
        double naturalWidth;
        double naturalHeight;

        if (isSvg) {
            // For SVG, try to read viewBox if layer dimensions aren't set
            naturalWidth = layer.getWidth();
            naturalHeight = layer.getHeight();

            // Fallback to reasonable defaults if not set
            if (naturalWidth == 0 || naturalHeight == 0) {
                logger.warn("SVG layer {} has no dimensions set, using defaults", layer.getId());
                naturalWidth = 500;  // Reasonable default
                naturalHeight = 500;
            }
        } else {
            BufferedImage sourceImg = ImageIO.read(new File(sourceImagePath));
            if (sourceImg == null) {
                throw new IOException("Failed to read image file: " + sourceImagePath);
            }
            naturalWidth = sourceImg.getWidth();
            naturalHeight = sourceImg.getHeight();
        }

        // Get scale and crop values
        double scale = layer.getScale() != null ? layer.getScale() : 1.0;
        double cropTop = layer.getCropTop() != null ? layer.getCropTop() : 0;
        double cropRight = layer.getCropRight() != null ? layer.getCropRight() : 0;
        double cropBottom = layer.getCropBottom() != null ? layer.getCropBottom() : 0;
        double cropLeft = layer.getCropLeft() != null ? layer.getCropLeft() : 0;

        // Calculate dimensions
        double cropHorizPercent = cropLeft + cropRight;
        double cropVertPercent = cropTop + cropBottom;
        double scaledWidthBeforeCrop = layer.getWidth() / ((100 - cropHorizPercent) / 100.0);
        double scaledHeightBeforeCrop = layer.getHeight() / ((100 - cropVertPercent) / 100.0);

        // Scale the image
        command.add("-resize");
        command.add(((int)Math.round(scaledWidthBeforeCrop)) + "x" + ((int)Math.round(scaledHeightBeforeCrop)) + "!");

        // Apply crop
        if (cropTop > 0 || cropRight > 0 || cropBottom > 0 || cropLeft > 0) {
            int cropX = (int)Math.round((cropLeft / 100.0) * scaledWidthBeforeCrop);
            int cropY = (int)Math.round((cropTop / 100.0) * scaledHeightBeforeCrop);
            int cropW = (int)Math.round(scaledWidthBeforeCrop * (1 - (cropLeft + cropRight) / 100.0));
            int cropH = (int)Math.round(scaledHeightBeforeCrop * (1 - (cropTop + cropBottom) / 100.0));

            command.add("-crop");
            command.add(cropW + "x" + cropH + "+" + cropX + "+" + cropY);
            command.add("+repage");
        }

        // **FIXED: Rotation without extent - let it expand naturally**
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            command.add("-background");
            command.add("transparent");
            command.add("-rotate");
            command.add(String.valueOf(layer.getRotation()));
            // Don't use +repage here - we need the virtual canvas info
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            command.add("-channel");
            command.add("Alpha");
            command.add("-evaluate");
            command.add("multiply");
            command.add(String.valueOf(layer.getOpacity()));
            command.add("+channel");
        }

        // Apply filters and shadow
        applyFilters(command, layer.getFilters());
        if (layer.getShadow() != null) {
            applyShadow(command, layer.getShadow());
        }

        command.add(outputPath);
        executeImageMagickCommand(command, "Process image layer");

        if (!sourceImagePath.equals(layer.getSrc())) {
            Files.deleteIfExists(Paths.get(sourceImagePath));
        }

        return outputPath;
    }

    /**
     * Process TEXT layer
     */
    private String processTextLayer(LayerDTO layer, String tempDirPath,
                                    Integer canvasWidth, Integer canvasHeight)
            throws IOException, InterruptedException {

        // Check if multi-color text
        if (layer.getTextSegments() != null && !layer.getTextSegments().isEmpty()) {
            return processMultiColorText(layer, tempDirPath, canvasWidth, canvasHeight);
        }

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_text.png";

        // Get font size
        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        String text = layer.getText() != null ? layer.getText() : "Text";

        // Apply text transform to get actual displayed text
        if ("uppercase".equalsIgnoreCase(layer.getTextTransform())) text = text.toUpperCase();
        if ("lowercase".equalsIgnoreCase(layer.getTextTransform())) text = text.toLowerCase();
        if ("capitalize".equalsIgnoreCase(layer.getTextTransform())) text = capitalize(text);

        // Estimate minimum text dimensions
        int estimatedTextWidth = (int)(text.length() * fontSize * 0.6);
        int estimatedTextHeight = (int)(fontSize * 1.5);

        // Determine canvas dimensions
        int canvasWidthToUse;
        int canvasHeightToUse;

        if (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0 &&
                layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0) {
            // Use background dimensions if specified
            canvasWidthToUse = layer.getBackgroundWidth().intValue();
            canvasHeightToUse = layer.getBackgroundHeight().intValue();
        } else {
            // No background - use text dimensions with padding
            canvasWidthToUse = estimatedTextWidth + 20;
            canvasHeightToUse = estimatedTextHeight + 10;
        }

        // Calculate extra padding for underline
        int extraBottomPadding = 0;
        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            extraBottomPadding = Math.max(8, fontSize / 4);
        }

        // Add extra padding for border stroke
        int borderStrokeWidth = (layer.getBackgroundBorderWidth() != null) ? layer.getBackgroundBorderWidth() : 0;
        int extraPadding = borderStrokeWidth * 2;

        // Total canvas size
        int totalWidth = canvasWidthToUse + extraPadding;
        int totalHeight = canvasHeightToUse + extraBottomPadding + extraPadding;

        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add("-size");
        cmd.add(totalWidth + "x" + totalHeight);
        cmd.add("xc:transparent");
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");

        // Draw background if opacity > 0
        if (layer.getBackgroundColor() != null && layer.getBackgroundOpacity() != null
                && layer.getBackgroundOpacity() > 0) {

            String bgColor = layer.getBackgroundColor() +
                    String.format("%02x", (int)(layer.getBackgroundOpacity() * 255));

            int borderRadius = layer.getBackgroundBorderRadius() != null ?
                    layer.getBackgroundBorderRadius() : 8;

            cmd.add("-fill");
            cmd.add(bgColor);

            // Add border if specified - draw INSIDE the background box like CSS
            if (layer.getBackgroundBorder() != null && layer.getBackgroundBorderWidth() != null) {
                cmd.add("-stroke");
                cmd.add(layer.getBackgroundBorder());
                cmd.add("-strokewidth");
                cmd.add(String.valueOf(layer.getBackgroundBorderWidth()));

                // Adjust rectangle to account for stroke being drawn on the path
                // We need to inset by half the stroke width to match CSS border behavior
                int halfStroke = borderStrokeWidth / 2;
                int offset = borderStrokeWidth + halfStroke;

                cmd.add("-draw");
                cmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                        offset, offset,
                        canvasWidthToUse + borderStrokeWidth - halfStroke - 1,
                        canvasHeightToUse + borderStrokeWidth - halfStroke - 1,
                        borderRadius, borderRadius));
            } else {
                cmd.add("-stroke");
                cmd.add("none");

                // No border - draw at the edge with padding offset
                int offset = borderStrokeWidth;
                cmd.add("-draw");
                cmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                        offset, offset,
                        canvasWidthToUse + offset - 1, canvasHeightToUse + offset - 1,
                        borderRadius, borderRadius));
            }
        }

        // Reset stroke for text
        cmd.add("-stroke");
        cmd.add("none");

        // Font settings
        String fontPath = resolveFontPath(
                layer.getFontFamily(),
                layer.getFontWeight(),
                layer.getFontStyle());
        cmd.add("-font");
        cmd.add(fontPath);
        cmd.add("-pointsize");
        cmd.add(String.valueOf(fontSize));

        // Weight / style fallback
        if ("bold".equalsIgnoreCase(layer.getFontWeight())) {
            cmd.add("-weight");
            cmd.add("bold");
        }
        if ("italic".equalsIgnoreCase(layer.getFontStyle())) {
            cmd.add("-style");
            cmd.add("italic");
        }

        // Outline (stroke)
        if (layer.getOutlineWidth() != null && layer.getOutlineWidth() > 0
                && layer.getOutlineColor() != null) {
            cmd.add("-stroke");
            cmd.add(layer.getOutlineColor());
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(layer.getOutlineWidth().intValue()));
        } else {
            cmd.add("-stroke");
            cmd.add("none");
        }

        // Fill color
        cmd.add("-fill");
        cmd.add(layer.getColor() != null ? layer.getColor() : "#000000");

        // Combined gravity
        String horizontalAlign = (layer.getTextAlign() != null ? layer.getTextAlign() : "center").toLowerCase();
        String verticalAlign = (layer.getVerticalAlign() != null ? layer.getVerticalAlign() : "middle").toLowerCase();

        String gravity = "Center";
        if ("top".equals(verticalAlign)) {
            gravity = switch (horizontalAlign) {
                case "left" -> "NorthWest";
                case "right" -> "NorthEast";
                default -> "North";
            };
        } else if ("bottom".equals(verticalAlign)) {
            gravity = switch (horizontalAlign) {
                case "left" -> "SouthWest";
                case "right" -> "SouthEast";
                default -> "South";
            };
        } else {
            gravity = switch (horizontalAlign) {
                case "left" -> "West";
                case "right" -> "East";
                default -> "Center";
            };
        }

        cmd.add("-gravity");
        cmd.add(gravity);

        // Calculate vertical offset to match frontend rendering
        // Calculate vertical offset to match frontend rendering
        int verticalOffset = 0;
        if ("top".equals(verticalAlign)) {
            // For top alignment, adjust slightly upward
            verticalOffset = -fontSize / 16;
        } else if ("middle".equals(verticalAlign)) {
            // For middle alignment, adjust upward to match frontend
            verticalOffset = -fontSize / 10;
        } else if ("bottom".equals(verticalAlign)) {
            // For bottom alignment, adjust upward more
            verticalOffset = -fontSize / 8;
        }

        // Annotate text with vertical offset
        cmd.add("-annotate");
        cmd.add("+0+" + verticalOffset);
        cmd.add(text);

        if (layer.getCurveRadius() != null && layer.getCurveRadius() > 0) {
            cmd.add("-distort");
            cmd.add("Arc");
            cmd.add(String.valueOf(layer.getCurveRadius()));
        }

        cmd.add(outputPath);
        executeImageMagickCommand(cmd, "Process text layer");

        // Apply text decorations AFTER rendering but BEFORE rotation
        if ("underline".equalsIgnoreCase(layer.getTextDecoration()) ||
                "line-through".equalsIgnoreCase(layer.getTextDecoration())) {

            String decoratedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_decorated.png";
            applyTextDecorationProperly(outputPath, decoratedPath, layer, totalWidth, totalHeight);
            Files.deleteIfExists(Paths.get(outputPath));
            outputPath = decoratedPath;
        }

        // Rotation AFTER decoration
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            String rotatedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_rotated.png";
            List<String> rotateCmd = new ArrayList<>();
            rotateCmd.add(imageMagickPath);
            rotateCmd.add(outputPath);
            rotateCmd.add("-background");
            rotateCmd.add("transparent");
            rotateCmd.add("-rotate");
            rotateCmd.add(String.valueOf(layer.getRotation()));
            rotateCmd.add("-trim");
            rotateCmd.add("+repage");

            // Apply opacity
            if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
                rotateCmd.add("-channel");
                rotateCmd.add("Alpha");
                rotateCmd.add("-evaluate");
                rotateCmd.add("multiply");
                rotateCmd.add(String.valueOf(layer.getOpacity()));
                rotateCmd.add("+channel");
            }

            // Apply shadow
            if (layer.getShadow() != null) {
                applyShadow(rotateCmd, layer.getShadow());
            }

            rotateCmd.add(rotatedPath);
            executeImageMagickCommand(rotateCmd, "Rotate text layer");
            Files.deleteIfExists(Paths.get(outputPath));
            return rotatedPath;
        }

        // Opacity (if no rotation)
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            cmd = new ArrayList<>();
            cmd.add(imageMagickPath);
            cmd.add(outputPath);
            cmd.add("-channel");
            cmd.add("Alpha");
            cmd.add("-evaluate");
            cmd.add("multiply");
            cmd.add(String.valueOf(layer.getOpacity()));
            cmd.add("+channel");
            cmd.add(outputPath);
            executeImageMagickCommand(cmd, "Apply opacity to text");
        }

        // Shadow (if no rotation)
        if (layer.getShadow() != null) {
            cmd = new ArrayList<>();
            cmd.add(imageMagickPath);
            cmd.add(outputPath);
            applyShadow(cmd, layer.getShadow());
            cmd.add(outputPath);
            executeImageMagickCommand(cmd, "Apply shadow to text");
        }

        return outputPath;
    }

    private String processMultiColorText(LayerDTO layer, String tempDirPath, int canvasWidth, int canvasHeight)
            throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_multicolor.png";

        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        String text = layer.getText() != null ? layer.getText() : "Text";

        int estimatedTextWidth = (int)(text.length() * fontSize * 0.6);
        int estimatedTextHeight = (int)(fontSize * 1.5);

        // Determine canvas dimensions
        int canvasWidthToUse;
        int canvasHeightToUse;

        if (layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0 &&
                layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0) {
            canvasWidthToUse = layer.getBackgroundWidth().intValue();
            canvasHeightToUse = layer.getBackgroundHeight().intValue();
        } else {
            canvasWidthToUse = estimatedTextWidth + 20;
            canvasHeightToUse = estimatedTextHeight + 10;
        }

        int extraBottomPadding = 0;
        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            extraBottomPadding = Math.max(8, fontSize / 4);
        }

        int borderStrokeWidth = (layer.getBackgroundBorderWidth() != null) ? layer.getBackgroundBorderWidth() : 0;
        int extraPadding = borderStrokeWidth * 2;

        int totalWidth = canvasWidthToUse + extraPadding;
        int totalHeight = canvasHeightToUse + extraBottomPadding + extraPadding;

        // Create base with background
        List<String> baseCmd = new ArrayList<>();
        baseCmd.add(imageMagickPath);
        baseCmd.add("-size");
        baseCmd.add(totalWidth + "x" + totalHeight);
        baseCmd.add("xc:transparent");
        baseCmd.add("-set");
        baseCmd.add("colorspace");
        baseCmd.add("sRGB");

        // Add background if specified
        if (layer.getBackgroundColor() != null && layer.getBackgroundOpacity() != null
                && layer.getBackgroundOpacity() > 0) {

            String bgColor = layer.getBackgroundColor() +
                    String.format("%02x", (int)(layer.getBackgroundOpacity() * 255));

            int borderRadius = layer.getBackgroundBorderRadius() != null ?
                    layer.getBackgroundBorderRadius() : 8;

            baseCmd.add("-fill");
            baseCmd.add(bgColor);

            if (layer.getBackgroundBorder() != null && layer.getBackgroundBorderWidth() != null) {
                baseCmd.add("-stroke");
                baseCmd.add(layer.getBackgroundBorder());
                baseCmd.add("-strokewidth");
                baseCmd.add(String.valueOf(layer.getBackgroundBorderWidth()));

                // Match CSS border behavior - inset by half stroke width
                int halfStroke = borderStrokeWidth / 2;
                int offset = borderStrokeWidth + halfStroke;

                baseCmd.add("-draw");
                baseCmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                        offset, offset,
                        canvasWidthToUse + borderStrokeWidth - halfStroke - 1,
                        canvasHeightToUse + borderStrokeWidth - halfStroke - 1,
                        borderRadius, borderRadius));
            } else {
                baseCmd.add("-stroke");
                baseCmd.add("none");

                int offset = borderStrokeWidth;
                baseCmd.add("-draw");
                baseCmd.add(String.format("roundrectangle %d,%d %d,%d %d,%d",
                        offset, offset,
                        canvasWidthToUse + offset - 1, canvasHeightToUse + offset - 1,
                        borderRadius, borderRadius));
            }
        }

        baseCmd.add(outputPath);
        executeImageMagickCommand(baseCmd, "Create base for multi-color text");

        // Get font properties
        String fontPath = resolveFontPath(
                layer.getFontFamily(),
                layer.getFontWeight(),
                layer.getFontStyle());

        List<LayerDTO.TextSegmentDTO> segments = layer.getTextSegments();
        if (segments == null || segments.isEmpty()) {
            return processTextLayer(layer, tempDirPath, canvasWidth, canvasHeight);
        }

        segments.sort((a, b) -> a.getStartIndex().compareTo(b.getStartIndex()));

        String fullText = layer.getText() != null ? layer.getText() : "";
        String horizontalAlign = (layer.getTextAlign() != null ? layer.getTextAlign() : "center").toLowerCase();
        String verticalAlign = (layer.getVerticalAlign() != null ? layer.getVerticalAlign() : "middle").toLowerCase();

        // Calculate total text width
        int totalTextWidth = estimateTextWidth(fullText, fontSize, fontPath);
        int startX = 0;

        if ("center".equals(horizontalAlign)) {
            startX = (totalWidth - totalTextWidth) / 2;
        } else if ("right".equals(horizontalAlign)) {
            startX = totalWidth - totalTextWidth - 10;
        } else {
            startX = 10 + (extraPadding / 2);
        }

        // Calculate Y position
        int baseY = 0;
        if ("top".equals(verticalAlign)) {
            baseY = fontSize + (extraPadding / 2);
        } else if ("bottom".equals(verticalAlign)) {
            baseY = totalHeight - 10;
        } else {
            baseY = (totalHeight / 2) + (fontSize / 3);
        }

        // Render each segment
        int currentX = startX;
        int lastIndex = 0;

        for (int i = 0; i <= segments.size(); i++) {
            if (i < segments.size()) {
                LayerDTO.TextSegmentDTO segment = segments.get(i);

                if (segment.getStartIndex() > lastIndex) {
                    String beforeText = fullText.substring(lastIndex, segment.getStartIndex());
                    currentX = renderTextSegment(outputPath, beforeText, currentX, baseY,
                            layer.getColor() != null ? layer.getColor() : "#000000",
                            fontPath, fontSize, layer);
                }

                currentX = renderTextSegment(outputPath, segment.getText(), currentX, baseY,
                        segment.getColor(), fontPath, fontSize, layer);

                lastIndex = segment.getEndIndex();
            } else {
                if (lastIndex < fullText.length()) {
                    String remainingText = fullText.substring(lastIndex);
                    renderTextSegment(outputPath, remainingText, currentX, baseY,
                            layer.getColor() != null ? layer.getColor() : "#000000",
                            fontPath, fontSize, layer);
                }
            }
        }

        // Apply decorations
        if ("underline".equalsIgnoreCase(layer.getTextDecoration()) ||
                "line-through".equalsIgnoreCase(layer.getTextDecoration())) {
            String decoratedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_decorated.png";
            applyTextDecorationProperly(outputPath, decoratedPath, layer, totalWidth, totalHeight);
            Files.deleteIfExists(Paths.get(outputPath));
            outputPath = decoratedPath;
        }

        // Apply rotation
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            String rotatedPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_rotated.png";
            List<String> rotateCmd = new ArrayList<>();
            rotateCmd.add(imageMagickPath);
            rotateCmd.add(outputPath);
            rotateCmd.add("-background");
            rotateCmd.add("transparent");
            rotateCmd.add("-rotate");
            rotateCmd.add(String.valueOf(layer.getRotation()));
            rotateCmd.add("-trim");  // ADD THIS LINE
            rotateCmd.add("+repage");  // ADD THIS LINE

            if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
                rotateCmd.add("-channel");
                rotateCmd.add("Alpha");
                rotateCmd.add("-evaluate");
                rotateCmd.add("multiply");
                rotateCmd.add(String.valueOf(layer.getOpacity()));
                rotateCmd.add("+channel");
            }

            if (layer.getShadow() != null) {
                applyShadow(rotateCmd, layer.getShadow());
            }

            rotateCmd.add(rotatedPath);
            executeImageMagickCommand(rotateCmd, "Rotate multi-color text layer");
            Files.deleteIfExists(Paths.get(outputPath));
            return rotatedPath;
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            List<String> opacityCmd = new ArrayList<>();
            opacityCmd.add(imageMagickPath);
            opacityCmd.add(outputPath);
            opacityCmd.add("-channel");
            opacityCmd.add("Alpha");
            opacityCmd.add("-evaluate");
            opacityCmd.add("multiply");
            opacityCmd.add(String.valueOf(layer.getOpacity()));
            opacityCmd.add("+channel");
            opacityCmd.add(outputPath);
            executeImageMagickCommand(opacityCmd, "Apply opacity to multi-color text");
        }

        // Apply shadow
        if (layer.getShadow() != null) {
            List<String> shadowCmd = new ArrayList<>();
            shadowCmd.add(imageMagickPath);
            shadowCmd.add(outputPath);
            applyShadow(shadowCmd, layer.getShadow());
            shadowCmd.add(outputPath);
            executeImageMagickCommand(shadowCmd, "Apply shadow to multi-color text");
        }

        return outputPath;
    }

    private int estimateTextWidth(String text, int fontSize, String fontPath) {
        // More accurate estimation based on average character width
        return (int)(text.length() * fontSize * 0.55);
    }

    private int renderTextSegment(String baseImagePath, String text, int x, int y,
                                  String color, String fontPath, int fontSize, LayerDTO layer)
        throws IOException, InterruptedException {

        if (text == null || text.isEmpty()) return x;

        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add(baseImagePath);
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");
        cmd.add("-font");
        cmd.add(fontPath);
        cmd.add("-pointsize");
        cmd.add(String.valueOf(fontSize));

        // Apply text transform
        String transformedText = text;
        if ("uppercase".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = text.toUpperCase();
        } else if ("lowercase".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = text.toLowerCase();
        } else if ("capitalize".equalsIgnoreCase(layer.getTextTransform())) {
            transformedText = capitalize(text);
        }

        // Apply outline if specified
        if (layer.getOutlineWidth() != null && layer.getOutlineWidth() > 0
            && layer.getOutlineColor() != null) {
            cmd.add("-stroke");
            cmd.add(layer.getOutlineColor());
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(layer.getOutlineWidth().intValue()));
        } else {
            cmd.add("-stroke");
            cmd.add("none");
        }

        cmd.add("-fill");
        cmd.add(color);
        cmd.add("-annotate");
        cmd.add("+" + x + "+" + y);
        cmd.add(transformedText);
        cmd.add(baseImagePath);

        executeImageMagickCommand(cmd, "Render text segment");

        // Return new X position
        return x + estimateTextWidth(transformedText, fontSize, fontPath);
    }

    /**
     * Apply text decoration properly - measure text bounds and draw line accordingly
     */
    private void applyTextDecorationProperly(String inputPath, String outputPath, LayerDTO layer, int width, int height)
        throws IOException, InterruptedException {

        // Use ImageMagick to detect actual content bounds (non-transparent pixels)
        List<String> identifyCmd = new ArrayList<>();
        identifyCmd.add(imageMagickPath);
        identifyCmd.add("identify");
        identifyCmd.add("-format");
        identifyCmd.add("%@");  // Returns: WIDTHxHEIGHT+X+Y
        identifyCmd.add(inputPath);

        ProcessBuilder pb = new ProcessBuilder(identifyCmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            logger.warn("Identify command timed out");
        }

        String trimBox = output.toString().trim();
        logger.debug("Trim box result: {}", trimBox);

        // Parse trim box: WIDTHxHEIGHT+X+Y
        int textX = 0;
        int textY = 0;
        int textWidth = width;
        int textHeight = height;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)x(\\d+)\\+(\\d+)\\+(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(trimBox);

        if (matcher.find()) {
            textWidth = Integer.parseInt(matcher.group(1));
            textHeight = Integer.parseInt(matcher.group(2));
            textX = Integer.parseInt(matcher.group(3));
            textY = Integer.parseInt(matcher.group(4));

            logger.debug("Detected text bounds: width={}, height={}, x={}, y={}", textWidth, textHeight, textX, textY);
        } else {
            logger.warn("Could not parse trim box: '{}'. Using full dimensions.", trimBox);
            // Fallback: try to estimate based on alignment
            int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
            String text = layer.getText() != null ? layer.getText() : "";

            // Rough estimation
            textWidth = (int)(text.length() * fontSize * 0.55);
            textHeight = (int)(fontSize * 1.2);

            String horizontalAlign = layer.getTextAlign() != null ? layer.getTextAlign().toLowerCase() : "center";
            String verticalAlign = layer.getVerticalAlign() != null ? layer.getVerticalAlign().toLowerCase() : "middle";

            // Estimate position based on alignment
            if ("center".equals(horizontalAlign)) {
                textX = (width - textWidth) / 2;
            } else if ("right".equals(horizontalAlign)) {
                textX = width - textWidth - 10;
            } else {
                textX = 10;
            }

            if ("middle".equals(verticalAlign)) {
                textY = (height - textHeight) / 2;
            } else if ("top".equals(verticalAlign)) {
                textY = 10;
            } else {
                textY = height - textHeight - 10;
            }
        }

        // Draw the decoration line
        List<String> cmd = new ArrayList<>();
        cmd.add(imageMagickPath);
        cmd.add(inputPath);
        cmd.add("-set");
        cmd.add("colorspace");
        cmd.add("sRGB");

        // Determine color
        String color = layer.getColor() != null ? layer.getColor() : "#000000";
        if ("line-through".equalsIgnoreCase(layer.getTextDecoration()) && layer.getLinethroughColor() != null) {
            color = layer.getLinethroughColor();
        }

        int fontSize = layer.getFontSize() != null ? layer.getFontSize() : 32;
        int strokeWidth = Math.max(2, fontSize / 16);

        // Calculate horizontal extent of the line
        int leftMargin = textX;
        int rightMargin = textX + textWidth;

        if ("underline".equalsIgnoreCase(layer.getTextDecoration())) {
            // Position underline below text with proper gap
            int gap = Math.max(3, fontSize / 10);
            int yPosition = textY + textHeight + gap;

            cmd.add("-stroke");
            cmd.add(color);
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(strokeWidth));
            cmd.add("-draw");
            cmd.add(String.format("line %d,%d %d,%d", leftMargin, yPosition, rightMargin, yPosition));

        } else if ("line-through".equalsIgnoreCase(layer.getTextDecoration())) {
            // FIXED: Position at vertical center of actual text
            // Use 45% from top to account for typical font metrics
            int yPosition = textY + (int)(textHeight * 0.45);

            cmd.add("-stroke");
            cmd.add(color);
            cmd.add("-strokewidth");
            cmd.add(String.valueOf(strokeWidth));
            cmd.add("-draw");
            cmd.add(String.format("line %d,%d %d,%d", leftMargin, yPosition, rightMargin, yPosition));
        }

        cmd.add(outputPath);
        executeImageMagickCommand(cmd, "Apply text decoration");
    }

    private String capitalize(String s) {
        return Arrays.stream(s.split("\\s+"))
            .map(word -> word.isEmpty() ? word :
                Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
            .collect(Collectors.joining(" "));
    }

    /**
     * Process SHAPE layer
     */
    private String processShapeLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight)
        throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_shape.png";

        int width = layer.getWidth() != null ? layer.getWidth().intValue() : 100;
        int height = layer.getHeight() != null ? layer.getHeight().intValue() : 100;
        String fill = layer.getFill() != null ? layer.getFill() : "#000000";
        String stroke = layer.getStroke();
        Integer strokeWidth = layer.getStrokeWidth() != null ? layer.getStrokeWidth() : 0;

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add("-size");
        command.add(width + "x" + height);
        command.add("xc:transparent");
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add("-fill");
        command.add(fill);

        if (stroke != null && strokeWidth > 0) {
            command.add("-stroke");
            command.add(stroke);
            command.add("-strokewidth");
            command.add(String.valueOf(strokeWidth));
        } else {
            command.add("-stroke");
            command.add("none");
        }

        // Draw shape based on type
        String shape = layer.getShape() != null ? layer.getShape().toLowerCase() : "rectangle";
        switch (shape) {
            case "rectangle":
                Integer borderRadius = layer.getBorderRadius();
                if (borderRadius != null && borderRadius > 0) {
                    command.add("-draw");
                    command.add("roundrectangle 0,0 " + (width-1) + "," + (height-1) + " " +
                        borderRadius + "," + borderRadius);
                } else {
                    command.add("-draw");
                    command.add("rectangle 0,0 " + (width-1) + "," + (height-1));
                }
                break;
            case "circle":
                int radius = Math.min(width, height) / 2;
                command.add("-draw");
                command.add("circle " + radius + "," + radius + " " + radius + ",0");
                break;
            case "ellipse":
                int rx = width / 2;
                int ry = height / 2;
                command.add("-draw");
                command.add("ellipse " + rx + "," + ry + " " + rx + "," + ry + " 0,360");
                break;
            case "triangle":
                command.add("-draw");
                command.add("polygon " + (width/2) + ",0 " + width + "," + height + " 0," + height);
                break;
            default:
                command.add("-draw");
                command.add("rectangle 0,0 " + (width-1) + "," + (height-1));
        }

        // Apply rotation
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            command.add("-background");
            command.add("transparent");
            command.add("-rotate");
            command.add(String.valueOf(layer.getRotation()));
            command.add("+repage");
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            command.add("-channel");
            command.add("Alpha");
            command.add("-evaluate");
            command.add("multiply");
            command.add(String.valueOf(layer.getOpacity()));
            command.add("+channel");
        }

        // Apply shadow if exists
        if (layer.getShadow() != null) {
            applyShadow(command, layer.getShadow());
        }

        command.add(outputPath);

        executeImageMagickCommand(command, "Process shape layer");

        return outputPath;
    }

    /**
     * Process BACKGROUND layer (similar to image but fills entire canvas)
     */
    private String processBackgroundLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight) 
            throws IOException, InterruptedException {
        
        // Reuse image layer logic but force it to canvas size
        layer.setWidth((double) canvasWidth);
        layer.setHeight((double) canvasHeight);
        layer.setX(0.0);
        layer.setY(0.0);
        
        return processImageLayer(layer, tempDirPath, canvasWidth, canvasHeight);
    }

    /**
     * Composite layer image onto base image at specified position
     */
    private void compositeImages(String baseImagePath, String layerImagePath, String outputPath, LayerDTO layer)
            throws IOException, InterruptedException {

        int x = layer.getX() != null ? layer.getX().intValue() : 0;
        int y = layer.getY() != null ? layer.getY().intValue() : 0;

        // **FIXED: Apply rotation offset for both IMAGE and TEXT layers**
        if (layer.getRotation() != null && layer.getRotation() != 0) {

            // Get the dimensions of the rotated image
            BufferedImage rotatedImg = ImageIO.read(new File(layerImagePath));
            if (rotatedImg == null) {
                throw new IOException("Failed to read rotated image: " + layerImagePath);
            }
            int rotatedWidth = rotatedImg.getWidth();
            int rotatedHeight = rotatedImg.getHeight();

            if ("text".equalsIgnoreCase(layer.getType())) {
                // For TEXT layers (which use -trim), we need to account for the trimmed size
                // The frontend renders text at a specific position, and rotation happens around center

                // Get original dimensions before rotation
                double origWidth = layer.getBackgroundWidth() != null && layer.getBackgroundWidth() > 0
                        ? layer.getBackgroundWidth()
                        : (layer.getText() != null ? layer.getText().length() * (layer.getFontSize() != null ? layer.getFontSize() : 32) * 0.6 : 100);
                double origHeight = layer.getBackgroundHeight() != null && layer.getBackgroundHeight() > 0
                        ? layer.getBackgroundHeight()
                        : (layer.getFontSize() != null ? layer.getFontSize() : 32) * 1.5;

                // Calculate the center point of the original text
                double centerX = x + origWidth / 2;
                double centerY = y + origHeight / 2;

                // The rotated (trimmed) image needs to be centered at the same point
                int newX = (int)Math.round(centerX - rotatedWidth / 2);
                int newY = (int)Math.round(centerY - rotatedHeight / 2);

                x = newX;
                y = newY;

            } else if ("image".equalsIgnoreCase(layer.getType())) {
                // For IMAGE layers (which don't use -trim), use the existing logic
                double origWidth = layer.getWidth();
                double origHeight = layer.getHeight();

                double centerX = x + origWidth / 2;
                double centerY = y + origHeight / 2;

                int newX = (int)Math.round(centerX - rotatedWidth / 2);
                int newY = (int)Math.round(centerY - rotatedHeight / 2);

                x = newX;
                y = newY;
            }
        }

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(baseImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add(layerImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add("-geometry");
        command.add("+" + x + "+" + y);
        command.add("-compose");
        command.add("Over");
        command.add("-composite");
        command.add(outputPath);

        executeImageMagickCommand(command, "Composite layer");
    }

    /**
     * Apply filters to image - Enhanced version matching CSS output
     */
    private void applyFilters(List<String> command, List<LayerDTO.FilterDTO> filters) {
        if (filters == null || filters.isEmpty()) return;

        for (LayerDTO.FilterDTO filter : filters) {
            String type = filter.getType().toLowerCase();
            double value = filter.getValue();

            switch (type) {
                case "brightness":
                    // CSS: brightness(0% to 200%), where 100% is normal
                    // ImageMagick: -brightness-contrast uses -100 to 100
                    // Map: -100 (0% in CSS) to 0 (100%) to 100 (200%)
                    int brightVal = (int) Math.round(value);
                    command.add("-brightness-contrast");
                    command.add(brightVal + "x0");
                    break;

                case "contrast":
                    // CSS: contrast(0% to 200%), where 100% is normal
                    // ImageMagick: -brightness-contrast uses -100 to 100
                    int contrastVal = (int) Math.round(value);
                    command.add("-brightness-contrast");
                    command.add("0x" + contrastVal);
                    break;

                case "saturation":
                    // CSS: saturate(0% to 200%), where 100% is normal
                    // ImageMagick: -modulate saturation uses 0-200, where 100 is normal
                    int satVal = (int) Math.round(100 + value);
                    satVal = Math.max(0, Math.min(200, satVal));
                    command.add("-modulate");
                    command.add("100," + satVal + ",100");
                    break;

                case "hue-rotate":
                    // CSS: hue-rotate(0deg to 360deg)
                    // ImageMagick: -modulate hue uses relative percentage
                    // 360 degrees = 200 in modulate (wraps around)
                    double hueShift = (value / 360.0) * 200.0;
                    command.add("-modulate");
                    command.add(String.format("100,100,%.2f", 100 + hueShift));
                    break;

                case "blur":
                    // CSS: blur(0px to 20px) - actual Gaussian blur
                    // ImageMagick: -blur uses radius x sigma format
                    if (value > 0) {
                        // CSS blur is visual, ImageMagick needs adjustment
                        // CSS blur is roughly 2x stronger visually
                        double adjustedBlur = value * 0.5;
                        command.add("-blur");
                        command.add(String.format("0x%.2f", adjustedBlur));
                    }
                    break;

                case "sharpen":
                    // CSS doesn't have sharpen, we approximate with contrast
                    // ImageMagick: -sharpen uses radius x sigma
                    if (value > 0) {
                        // Sharpen is subtle - use adaptive sharpen for better results
                        command.add("-adaptive-sharpen");
                        command.add(String.format("0x%.2f", value * 0.3));
                    }
                    break;

                case "temperature":
                    // CSS approximation: warm = sepia + hue-rotate
                    // Range: -100 (cool/blue) to 100 (warm/orange)
                    if (value != 0) {
                        if (value > 0) {
                            // Warm: add yellow/orange cast
                            // Sepia gives warm base, then adjust hue
                            double sepiaAmount = value * 0.15; // Subtle sepia
                            command.add("-sepia-tone");
                            command.add(String.format("%.1f%%", sepiaAmount));

                            // Slight hue shift toward orange
                            double hueAdjust = -value * 0.08; // Maps to CSS hue-rotate
                            command.add("-modulate");
                            command.add(String.format("100,100,%.2f", 100 + hueAdjust));
                        } else {
                            // Cool: shift hue toward blue
                            double coolFactor = -value;
                            double hueAdjust = coolFactor * 0.4; // Maps to CSS hue-rotate
                            command.add("-modulate");
                            command.add(String.format("100,100,%.2f", 100 + hueAdjust));

                            // Reduce saturation slightly for cool look
                            command.add("-modulate");
                            command.add(String.format("100,%.0f,100", 100 - (coolFactor * 0.05)));
                        }
                    }
                    break;

                case "tint":
                    // CSS approximation: hue-rotate for tint
                    // Range: -100 (green) to 100 (magenta)
                    if (value != 0) {
                        // Map to hue rotation
                        double hueAdjust = value * 1.2; // Matches CSS tint approximation
                        command.add("-modulate");
                        command.add(String.format("100,100,%.2f", 100 + hueAdjust));
                    }
                    break;

                case "exposure":
                    // CSS approximation: brightness adjustment
                    // Range: -2 to 2 EV stops
                    // EV stop formula: 2^value
                    if (value != 0) {
                        double factor = Math.pow(2, value);
                        // CSS shows this as brightness(factor * 100%)
                        // ImageMagick: use gamma adjustment for more accurate exposure
                        if (value > 0) {
                            // Increase exposure - brighten
                            command.add("-gamma");
                            command.add(String.format("%.3f", 1.0 / factor)); // Inverse for brightening
                        } else {
                            // Decrease exposure - darken
                            command.add("-gamma");
                            command.add(String.format("%.3f", factor));
                        }
                    }
                    break;

                case "highlights":
                    // CSS approximation: brightness adjustment
                    // Range: -100 to 100
                    // In CSS, this is shown as subtle brightness change (value * 0.3)
                    if (value != 0) {
                        // Use gamma to target highlights
                        double factor = value / 100.0;
                        if (factor > 0) {
                            // Reduce highlights - darken bright areas
                            command.add("-level");
                            command.add(String.format("0%%,%.1f%%", 100 - (factor * 20)));
                        } else {
                            // Boost highlights - brighten bright areas
                            command.add("-level");
                            command.add(String.format("0%%,%.1f%%", 100 - (factor * 20)));
                        }
                    }
                    break;

                case "shadows":
                    // CSS approximation: brightness adjustment
                    // Range: -100 to 100
                    if (value != 0) {
                        double factor = value / 100.0;
                        if (factor > 0) {
                            // Lift shadows - brighten dark areas
                            command.add("-level");
                            command.add(String.format("%.1f%%,100%%", factor * 15));
                        } else {
                            // Crush shadows - darken dark areas
                            command.add("-level");
                            command.add(String.format("%.1f%%,100%%", factor * 15));
                        }
                    }
                    break;

                case "vibrance":
                    // CSS: saturate(value%)
                    // Range: 0 to 200, where 100 is normal
                    if (value != 100) {
                        int vibranceVal = (int) value;
                        command.add("-modulate");
                        command.add("100," + vibranceVal + ",100");
                    }
                    break;

                // Legacy filters
                case "grayscale":
                    command.add("-colorspace");
                    command.add("Gray");
                    break;

                case "sepia":
                    command.add("-sepia-tone");
                    command.add(((int)value) + "%");
                    break;

                case "invert":
                    command.add("-negate");
                    break;
            }
        }
    }

    /**
     * Apply shadow effect
     */
    private void applyShadow(List<String> command, ShadowDTO shadow) {
        command.add("(");
        command.add("+clone");
        command.add("-background");
        command.add(shadow.getColor() != null ? shadow.getColor() : "#000000");
        command.add("-shadow");
        command.add("80x" + shadow.getBlur() + "+" + 
                   shadow.getOffsetX().intValue() + "+" + shadow.getOffsetY().intValue());
        command.add(")");
        command.add("+swap");
        command.add("-background");
        command.add("none");
        command.add("-layers");
        command.add("merge");
    }

    private String resolveFontPath(String fontFamily, String fontWeight, String fontStyle) {
        // 1. build a key that contains weight & style
        String key = fontFamily;
        if ("bold".equalsIgnoreCase(fontWeight)) key += " Bold";
        if ("italic".equalsIgnoreCase(fontStyle)) key += " Italic";
        if ("bold".equalsIgnoreCase(fontWeight) && "italic".equalsIgnoreCase(fontStyle)) key += " Bold Italic";

        // 2. reuse the huge map you already have (getFontPathByFamily)
        return getFontPathByFamily(key);      // <-- call your existing method
    }

    private String getFontPathByFamily(String fontFamily) {
        final String FONTS_RESOURCE_PATH = "/fonts/";
        final String TEMP_FONT_DIR = System.getProperty("java.io.tmpdir") + "/scenith-fonts/";

        File tempDir = new File(TEMP_FONT_DIR);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temporary font directory: {}", TEMP_FONT_DIR);
            return "C:/Windows/Fonts/Arial.ttf";
        }

        String defaultFontPath = getFontFilePath("arial.ttf", FONTS_RESOURCE_PATH, TEMP_FONT_DIR);

        if (fontFamily == null || fontFamily.trim().isEmpty()) {
            logger.warn("Font family is null or empty. Using default font: arial.ttf");
            return defaultFontPath;
        }

        Map<String, String> fontMap = new HashMap<>();
        fontMap.put("Arial", "arial.ttf");
        fontMap.put("Times New Roman", "times.ttf");
        fontMap.put("Courier New", "cour.ttf");
        fontMap.put("Calibri", "calibri.ttf");
        fontMap.put("Verdana", "verdana.ttf");
        fontMap.put("Georgia", "georgia.ttf");
        fontMap.put("Comic Sans MS", "comic.ttf");
        fontMap.put("Impact", "impact.ttf");
        fontMap.put("Tahoma", "tahoma.ttf");

        // Arial variants
        fontMap.put("Arial Bold", "arialbd.ttf");
        fontMap.put("Arial Italic", "ariali.ttf");
        fontMap.put("Arial Bold Italic", "arialbi.ttf");
        fontMap.put("Arial Black", "ariblk.ttf");

        // Georgia variants
        fontMap.put("Georgia Bold", "georgiab.ttf");
        fontMap.put("Georgia Italic", "georgiai.ttf");
        fontMap.put("Georgia Bold Italic", "georgiaz.ttf");

        // Times New Roman variants
        fontMap.put("Times New Roman Bold", "timesbd.ttf");
        fontMap.put("Times New Roman Italic", "timesi.ttf");
        fontMap.put("Times New Roman Bold Italic", "timesbi.ttf");

        // Alumni Sans Pinstripe
        fontMap.put("Alumni Sans Pinstripe", "AlumniSansPinstripe-Regular.ttf");

        // Lexend Giga variants
        fontMap.put("Lexend Giga", "LexendGiga-Regular.ttf");
        fontMap.put("Lexend Giga Black", "LexendGiga-Black.ttf");
        fontMap.put("Lexend Giga Bold", "LexendGiga-Bold.ttf");


        // Montserrat Alternates variants
        fontMap.put("Montserrat Alternates", "MontserratAlternates-ExtraLight.ttf");
        fontMap.put("Montserrat Alternates Black", "MontserratAlternates-Black.ttf");
        fontMap.put("Montserrat Alternates Medium Italic", "MontserratAlternates-MediumItalic.ttf");

        // Noto Sans Mono variants
        fontMap.put("Noto Sans Mono", "NotoSansMono-Regular.ttf");
        fontMap.put("Noto Sans Mono Bold", "NotoSansMono-Bold.ttf");


        // Poiret One
        fontMap.put("Poiret One", "PoiretOne-Regular.ttf");

        // Arimo variants
        fontMap.put("Arimo", "Arimo-Regular.ttf");
        fontMap.put("Arimo Bold", "Arimo-Bold.ttf");
        fontMap.put("Arimo Bold Italic", "Arimo-BoldItalic.ttf");
        fontMap.put("Arimo Italic", "Arimo-Italic.ttf");


        // Carlito variants
        fontMap.put("Carlito", "Carlito-Regular.ttf");
        fontMap.put("Carlito Bold", "Carlito-Bold.ttf");
        fontMap.put("Carlito Bold Italic", "Carlito-BoldItalic.ttf");
        fontMap.put("Carlito Italic", "Carlito-Italic.ttf");

        // Comic Neue variants
        fontMap.put("Comic Neue", "ComicNeue-Regular.ttf");
        fontMap.put("Comic Neue Bold", "ComicNeue-Bold.ttf");
        fontMap.put("Comic Neue Bold Italic", "ComicNeue-BoldItalic.ttf");
        fontMap.put("Comic Neue Italic", "ComicNeue-Italic.ttf");


        // Courier Prime variants
        fontMap.put("Courier Prime", "CourierPrime-Regular.ttf");
        fontMap.put("Courier Prime Bold", "CourierPrime-Bold.ttf");
        fontMap.put("Courier Prime Bold Italic", "CourierPrime-BoldItalic.ttf");
        fontMap.put("Courier Prime Italic", "CourierPrime-Italic.ttf");

        // Gelasio variants
        fontMap.put("Gelasio", "Gelasio-Regular.ttf");
        fontMap.put("Gelasio Bold", "Gelasio-Bold.ttf");
        fontMap.put("Gelasio Bold Italic", "Gelasio-BoldItalic.ttf");
        fontMap.put("Gelasio Italic", "Gelasio-Italic.ttf");


        // Tinos variants
        fontMap.put("Tinos", "Tinos-Regular.ttf");
        fontMap.put("Tinos Bold", "Tinos-Bold.ttf");
        fontMap.put("Tinos Bold Italic", "Tinos-BoldItalic.ttf");
        fontMap.put("Tinos Italic", "Tinos-Italic.ttf");

        // Amatic SC variants
        fontMap.put("Amatic SC", "AmaticSC-Regular.ttf");
        fontMap.put("Amatic SC Bold", "AmaticSC-Bold.ttf");

// Barriecito
        fontMap.put("Barriecito", "Barriecito-Regular.ttf");

// Barrio
        fontMap.put("Barrio", "Barrio-Regular.ttf");

// Birthstone
        fontMap.put("Birthstone", "Birthstone-Regular.ttf");

// Bungee Hairline
        fontMap.put("Bungee Hairline", "BungeeHairline-Regular.ttf");

// Butcherman
        fontMap.put("Butcherman", "Butcherman-Regular.ttf");

// Doto variants
        fontMap.put("Doto Black", "Doto-Black.ttf");
        fontMap.put("Doto ExtraBold", "Doto-ExtraBold.ttf");
        fontMap.put("Doto Rounded Bold", "Doto_Rounded-Bold.ttf");

// Fascinate Inline
        fontMap.put("Fascinate Inline", "FascinateInline-Regular.ttf");

// Freckle Face
        fontMap.put("Freckle Face", "FreckleFace-Regular.ttf");

// Fredericka the Great
        fontMap.put("Fredericka the Great", "FrederickatheGreat-Regular.ttf");

// Imperial Script
        fontMap.put("Imperial Script", "ImperialScript-Regular.ttf");

// Kings
        fontMap.put("Kings", "Kings-Regular.ttf");

// Kirang Haerang
        fontMap.put("Kirang Haerang", "KirangHaerang-Regular.ttf");

// Lavishly Yours
        fontMap.put("Lavishly Yours", "LavishlyYours-Regular.ttf");

// Mountains of Christmas variants
        fontMap.put("Mountains of Christmas", "MountainsofChristmas-Regular.ttf");
        fontMap.put("Mountains of Christmas Bold", "MountainsofChristmas-Bold.ttf");

// Rampart One
        fontMap.put("Rampart One", "RampartOne-Regular.ttf");

// Rubik Wet Paint
        fontMap.put("Rubik Wet Paint", "RubikWetPaint-Regular.ttf");

// Tangerine variants
        fontMap.put("Tangerine", "Tangerine-Regular.ttf");
        fontMap.put("Tangerine Bold", "Tangerine-Bold.ttf");

// Yesteryear
        fontMap.put("Yesteryear", "Yesteryear-Regular.ttf");

        String processedFontFamily = fontFamily.trim();
        if (fontMap.containsKey(processedFontFamily)) {
            String fontFileName = fontMap.get(processedFontFamily);
            String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
            logger.debug("Found font match for: {} -> {}", processedFontFamily, fontPath);
            return fontPath;
        }

        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
                String fontFileName = entry.getValue();
                String fontPath = getFontFilePath(fontFileName, FONTS_RESOURCE_PATH, TEMP_FONT_DIR);
                logger.debug("Found case-insensitive font match for: {} -> {}", processedFontFamily, fontPath);
                return fontPath;
            }
        }

        logger.warn("Font family '{}' not found. Using default: arial.ttf", fontFamily);
        return defaultFontPath;
    }

    private String getFontFilePath(String fontFileName, String fontsResourcePath, String tempFontDir) {
        try {
            File tempFontFile = new File(tempFontDir + fontFileName);
            if (tempFontFile.exists()) {
                return tempFontFile.getAbsolutePath();
            }

            String resourcePath = fontsResourcePath + fontFileName;
            InputStream fontStream = getClass().getResourceAsStream(resourcePath);
            if (fontStream == null) {
                logger.error("Font file not found in resources: {}", resourcePath);
                return "C:/Windows/Fonts/Arial.ttf";
            }

            Path tempPath = tempFontFile.toPath();
            Files.copy(fontStream, tempPath, StandardCopyOption.REPLACE_EXISTING);
            fontStream.close();

            logger.debug("Extracted font to: {}", tempFontFile.getAbsolutePath());
            return tempFontFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Error accessing font file: {}. Error: {}", fontFileName, e.getMessage());
            return "C:/Windows/Fonts/Arial.ttf";
        }
    }

    /**
     * Export final image in specified format
     */
    private void exportFinalImage(String inputPath, String outputPath, String format, Integer quality)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(inputPath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");

        if ("JPG".equalsIgnoreCase(format) || "JPEG".equalsIgnoreCase(format)) {
            // JPG doesn't support transparency - flatten with white background
            command.add("-background");
            command.add("white");
            command.add("-flatten");
            command.add("-quality");
            command.add(String.valueOf(quality != null ? quality : 90));
            command.add(outputPath);
        } else if ("PNG".equalsIgnoreCase(format)) {
            // PNG with transparency support - explicitly use PNG32 format
            command.add("-quality");
            command.add(String.valueOf(quality != null ? quality : 90));
            command.add("PNG32:" + outputPath); // Force PNG32 format with alpha channel
        } else if ("PDF".equalsIgnoreCase(format)) {
            command.add("-density");
            command.add("300");
            command.add(outputPath);
        } else {
            // Default case for other formats
            command.add(outputPath);
        }

        executeImageMagickCommand(command, "Export final image");
    }

    /**
     * Download image from URL or R2 path
     */
    private String downloadOrCopyImage(String src, String tempDirPath, String layerId) throws IOException, InterruptedException {
        String outputPath = tempDirPath + File.separator + "source_" + layerId;

        // Preserve original extension
        if (src.startsWith("http://") || src.startsWith("https://")) {
            String extension = src.substring(src.lastIndexOf('.'));
            outputPath += extension;
            try (InputStream in = new URL(src).openStream()) {
                Files.copy(in, Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            String fullPath = baseDir + File.separator + src;
            String extension = src.substring(src.lastIndexOf('.'));
            outputPath += extension;
            Files.copy(Paths.get(fullPath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
        }

        // NO conversion here - keep SVG as SVG
        return outputPath;
    }

    /**
     * Execute ImageMagick command
     */
    private void executeImageMagickCommand(List<String> command, String operationName)
        throws IOException, InterruptedException {

        logger.debug("Executing {}: {}", operationName, String.join(" ", command));

        // FORCE sRGB colorspace - prevent auto-grayscale
        List<String> finalCommand = new ArrayList<>();
        finalCommand.add(command.get(0)); // imageMagickPath
        finalCommand.add("-define");
        finalCommand.add("colorspace:auto-grayscale=off");
        finalCommand.addAll(command.subList(1, command.size()));

        ProcessBuilder processBuilder = new ProcessBuilder(finalCommand);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("ImageMagick: {}", line);
            }
        }

        boolean completed = process.waitFor(2, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("ImageMagick process timed out for " + operationName + ": " + output.toString());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("ImageMagick failed for " + operationName + " with exit code " +
                exitCode + ": " + output.toString());
        }
    }

    /**
     * Cleanup temp directory
     */
    private void cleanupTempDirectory(File tempDir) {
        try {
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        Files.deleteIfExists(file.toPath());
                    }
                }
                Files.deleteIfExists(tempDir.toPath());
                logger.debug("Cleaned up temp directory: {}", tempDir.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp directory: {}", e.getMessage());
        }
    }
}