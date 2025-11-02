package com.example.Scenith.service;

import com.example.Scenith.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ImageRenderService {

    private static final Logger logger = LoggerFactory.getLogger(ImageRenderService.class);

    @Value("${app.base-dir:D:\\Backend\\videoEditor-main}")
    private String baseDir;

    @Value("${app.imagemagick-path:C:\\Program Files\\ImageMagick-7.1.2-Q16-HDRI\\magick.exe}")
    private String imageMagickPath;

    private final ObjectMapper objectMapper;

    public ImageRenderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Main method to render design JSON to image
     */
    public String renderDesign(String designJson, String format, Integer quality, Long userId, Long projectId) 
            throws IOException, InterruptedException {
        
        logger.info("Starting render for project: {}, format: {}", projectId, format);
        System.setProperty("MAGICK_CONFIGURE_PATH", "");
        
        // Parse design JSON
        DesignDTO design = objectMapper.readValue(designJson, DesignDTO.class);
        
        // Create temp and output directories
        String tempDirPath = baseDir + File.separator + "image_editor" + File.separator + userId + 
                            File.separator + "temp_" + projectId;
        String outputDirPath = baseDir + File.separator + "image_editor" + File.separator + userId + 
                              File.separator + "exports";
        
        File tempDir = new File(tempDirPath);
        File outputDir = new File(outputDirPath);
        
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create output directory");
        }
        
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
        String outputFileName = "project_" + projectId + "_" + System.currentTimeMillis() + "." + format.toLowerCase();
        String finalOutputPath = outputDirPath + File.separator + outputFileName;
        
        exportFinalImage(currentImage, finalOutputPath, format, quality);
        
        // Cleanup temp files
        cleanupTempDirectory(tempDir);
        
        // Return relative path for CDN URL
        String relativePath = "image_editor/" + userId + "/exports/" + outputFileName;
        logger.info("Render completed successfully: {}", relativePath);
        
        return relativePath;
    }

    /**
     * Create base canvas with background color
     */
    private void createBaseCanvas(CanvasDTO canvas, String outputPath) throws IOException, InterruptedException {
        String bgColor = canvas.getBackgroundColor() != null ? canvas.getBackgroundColor() : "#FFFFFF";

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add("-size");
        command.add(canvas.getWidth() + "x" + canvas.getHeight());
        command.add("xc:" + bgColor);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        command.add(outputPath);

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

    /**
     * Process IMAGE layer
     */
    private String processImageLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight) 
            throws IOException, InterruptedException {
        
        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_image.png";
        
        // Download or copy source image
        String sourceImagePath = downloadOrCopyImage(layer.getSrc(), tempDirPath, layer.getId());
        
        // Build command for resizing, rotating, and applying effects
        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add(sourceImagePath);
        command.add("-set");
        command.add("colorspace");
        command.add("sRGB");
        
        // Resize if width/height specified
        if (layer.getWidth() != null && layer.getHeight() != null) {
            command.add("-resize");
            command.add(layer.getWidth().intValue() + "x" + layer.getHeight().intValue() + "!");
        }
        
        // Apply rotation
        // Apply rotation
        if (layer.getRotation() != null && layer.getRotation() != 0) {
            command.add("-background");
            command.add("transparent");  // CHANGED from "none"
            command.add("-rotate");
            command.add(String.valueOf(layer.getRotation()));
            command.add("+repage");  // ADDED: Reset virtual canvas
        }

        // Apply opacity
        if (layer.getOpacity() != null && layer.getOpacity() < 1.0) {
            command.add("-channel");
            command.add("Alpha");
            command.add("-evaluate");
            command.add("multiply");
            command.add(String.valueOf(layer.getOpacity()));
            command.add("+channel");  // ADDED: Reset channel
        }
        
        // Apply filters
        applyFilters(command, layer.getFilters());
        
        // Apply shadow if exists
        if (layer.getShadow() != null) {
            applyShadow(command, layer.getShadow());
        }
        
        command.add(outputPath);
        
        executeImageMagickCommand(command, "Process image layer");
        
        // Delete source if it was downloaded
        if (!sourceImagePath.equals(layer.getSrc())) {
            Files.deleteIfExists(Paths.get(sourceImagePath));
        }
        
        return outputPath;
    }

    /**
     * Process TEXT layer
     */
    private String processTextLayer(LayerDTO layer, String tempDirPath, Integer canvasWidth, Integer canvasHeight)
        throws IOException, InterruptedException {

        String outputPath = tempDirPath + File.separator + "layer_" + layer.getId() + "_text.png";

        // Create transparent canvas first with exact dimensions
        int width = layer.getWidth() != null ? layer.getWidth().intValue() : 200;
        int height = layer.getHeight() != null ? layer.getHeight().intValue() : 50;

        List<String> command = new ArrayList<>();
        command.add(imageMagickPath);
        command.add("-size");
        command.add(width + "x" + height);
        command.add("xc:transparent");
        command.add("-fill");
        command.add(layer.getColor() != null ? layer.getColor() : "#000000");
        command.add("-font");
        command.add(layer.getFontFamily() != null ? layer.getFontFamily() : "Arial");
        command.add("-pointsize");
        command.add(String.valueOf(layer.getFontSize() != null ? layer.getFontSize() : 32));

        // Font weight and style
        if ("bold".equalsIgnoreCase(layer.getFontWeight())) {
            command.add("-weight");
            command.add("bold");
        }
        if ("italic".equalsIgnoreCase(layer.getFontStyle())) {
            command.add("-style");
            command.add("italic");
        }

        // Text alignment using gravity
        String gravity = "West";
        if ("center".equalsIgnoreCase(layer.getTextAlign())) {
            gravity = "Center";
        } else if ("right".equalsIgnoreCase(layer.getTextAlign())) {
            gravity = "East";
        }
        command.add("-gravity");
        command.add(gravity);

        // Draw text on the canvas
        command.add("-annotate");
        command.add("+0+0");
        command.add(layer.getText() != null ? layer.getText() : "Text");

        // Apply rotation if specified
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

        command.add(outputPath);

        executeImageMagickCommand(command, "Process text layer");

        return outputPath;
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
     * Apply filters to image
     */
    private void applyFilters(List<String> command, List<FilterDTO> filters) {
        if (filters == null || filters.isEmpty()) return;
        
        for (FilterDTO filter : filters) {
            String type = filter.getType().toLowerCase();
            double value = filter.getValue();
            
            switch (type) {
                case "blur":
                    command.add("-blur");
                    command.add("0x" + value);
                    break;
                case "brightness":
                    command.add("-modulate");
                    command.add((value * 100) + ",100,100");
                    break;
                case "contrast":
                    int contrastValue = (int) ((value - 1) * 100);
                    command.add("-brightness-contrast");
                    command.add("0x" + contrastValue);
                    break;
                case "grayscale":
                    command.add("-colorspace");
                    command.add("Gray");
                    break;
                case "sepia":
                    command.add("-sepia-tone");
                    command.add(((int)(value * 100)) + "%");
                    break;
                case "saturate":
                    command.add("-modulate");
                    command.add("100," + (value * 100) + ",100");
                    break;
                case "hue-rotate":
                    command.add("-modulate");
                    command.add("100,100," + (100 + value));
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

    /**
     * Apply shadow to text
     */
    private void applyShadowToText(List<String> command, ShadowDTO shadow) {
        // For text, we use a simpler approach with stroke
        command.add("-stroke");
        command.add(shadow.getColor() != null ? shadow.getColor() : "#00000080");
        command.add("-strokewidth");
        command.add(String.valueOf(shadow.getBlur().intValue()));
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
            command.add("-background");
            command.add("white");
            command.add("-flatten");
            command.add("-quality");
            command.add(String.valueOf(quality != null ? quality : 90));
        } else if ("PDF".equalsIgnoreCase(format)) {
            command.add("-density");
            command.add("300");
        }

        command.add(outputPath);

        executeImageMagickCommand(command, "Export final image");
    }

    /**
     * Download image from URL or copy from local path
     */
    private String downloadOrCopyImage(String src, String tempDirPath, String layerId) throws IOException {
        String outputPath = tempDirPath + File.separator + "source_" + layerId + ".png";
        
        if (src.startsWith("http://") || src.startsWith("https://")) {
            // Download from URL
            logger.debug("Downloading image from: {}", src);
            try (InputStream in = new URL(src).openStream()) {
                Files.copy(in, Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            // Copy from local path
            String fullPath = baseDir + File.separator + src;
            Files.copy(Paths.get(fullPath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
        }
        
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