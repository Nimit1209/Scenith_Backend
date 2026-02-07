package com.example.Scenith.service;

import com.example.Scenith.entity.AspectRatioMedia;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.AspectRatioMediaRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AspectRatioService {

    private static final Logger logger = LoggerFactory.getLogger(AspectRatioService.class);

    private final JwtUtil jwtUtil;
    private final AspectRatioMediaRepository aspectRatioMediaRepository;
    private final UserRepository userRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final SqsService sqsService;
    private final ObjectMapper objectMapper;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Value("${app.ffmpeg-path:/usr/local/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${app.ffprobe-path:/usr/local/bin/ffprobe}")
    private String ffprobePath;

    @Value("${sqs.queue.url}")
    private String aspectRatioQueueUrl;

    public AspectRatioMedia uploadMedia(User user, MultipartFile mediaFile) throws IOException {
        logger.info("Uploading media for user: {}", user.getId());

        if (mediaFile == null || mediaFile.isEmpty()) {
            logger.error("MultipartFile is null or empty for user: {}", user.getId());
            throw new IllegalArgumentException("Media file is null or empty");
        }

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String tempFileName = "aspect-ratio-" + System.currentTimeMillis() + "-" + sanitizeFilename(mediaFile.getOriginalFilename());
        String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + tempFileName;

        File inputFile = null;
        try {
            inputFile = cloudflareR2Service.saveMultipartFileToTemp(mediaFile, tempInputPath);
            logger.debug("Saved input media to: {}", inputFile.getAbsolutePath());

            if (inputFile.length() == 0) {
                logger.error("Input file is empty: {}", inputFile.getAbsolutePath());
                throw new IOException("Input file is empty");
            }

            // Upload to R2
            String r2OriginalPath = String.format("aspect_ratio/%s/original/%s", user.getId(), sanitizeFilename(mediaFile.getOriginalFilename()));
            cloudflareR2Service.uploadFile(r2OriginalPath, inputFile);
            logger.info("Uploaded original media to R2: {}", r2OriginalPath);

            // Generate URLs
            Map<String, String> originalUrls = cloudflareR2Service.generateUrls(r2OriginalPath, 3600);

            AspectRatioMedia aspectRatioMedia = new AspectRatioMedia();
            aspectRatioMedia.setUser(user);
            aspectRatioMedia.setOriginalFileName(sanitizeFilename(mediaFile.getOriginalFilename()));
            aspectRatioMedia.setOriginalPath(r2OriginalPath);
            aspectRatioMedia.setOriginalCdnUrl(originalUrls.get("cdnUrl"));
            aspectRatioMedia.setStatus("UPLOADED");
            aspectRatioMedia.setProgress(0.0);
            aspectRatioMediaRepository.save(aspectRatioMedia);

            logger.info("Saved metadata for user: {}, media: {}", user.getId(), mediaFile.getOriginalFilename());
            return aspectRatioMedia;

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

    public AspectRatioMedia setAspectRatio(User user, Long mediaId, String aspectRatio) throws IOException {
        logger.info("Setting aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to set aspect ratio for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to set aspect ratio for this media");
        }

        if (!isValidAspectRatio(aspectRatio)) {
            logger.error("Invalid aspect ratio: {}", aspectRatio);
            throw new IllegalArgumentException("Invalid aspect ratio. Must be in format 'width:height' (e.g., '16:9')");
        }

        media.setAspectRatio(aspectRatio);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully set aspect ratio for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updatePositionX(User user, Long mediaId, Integer positionX) throws IOException {
        logger.info("Updating positionX for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update positionX for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update positionX for this media");
        }

        media.setPositionX(positionX != null ? positionX : 0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated positionX for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updatePositionY(User user, Long mediaId, Integer positionY) throws IOException {
        logger.info("Updating positionY for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update positionY for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update positionY for this media");
        }

        media.setPositionY(positionY != null ? positionY : 0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated positionY for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateScale(User user, Long mediaId, Double scale) throws IOException {
        logger.info("Updating scale for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update scale for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update scale for this media");
        }

        if (scale != null && scale <= 0.0) {
            logger.error("Invalid scale value: {}", scale);
            throw new IllegalArgumentException("Scale must be positive");
        }

        media.setScale(scale != null ? scale : 1.0);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated scale for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateOutputWidth(User user, Long mediaId, Integer width) throws IOException {
        logger.info("Updating output width for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output width for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output width for this media");
        }

        if (width != null && width <= 0) {
            logger.error("Invalid output width: {}", width);
            throw new IllegalArgumentException("Output width must be positive");
        }

        if (width != null) {
            width = width % 2 == 0 ? width : width + 1;
        }

        media.setOutputWidth(width);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output width for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateOutputHeight(User user, Long mediaId, Integer height) throws IOException {
        logger.info("Updating output height for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output height for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output height for this media");
        }

        if (height != null && height <= 0) {
            logger.error("Invalid output height: {}", height);
            throw new IllegalArgumentException("Output height must be positive");
        }

        if (height != null) {
            height = height % 2 == 0 ? height : height + 1;
        }

        media.setOutputHeight(height);
        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output height for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia updateOutputResolution(User user, Long mediaId, Integer width, Integer height) throws IOException {
        logger.info("Updating output resolution for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to update output resolution for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to update output resolution for this media");
        }

        if (width != null) {
            if (width <= 0) {
                logger.error("Invalid output width: {}", width);
                throw new IllegalArgumentException("Output width must be positive");
            }
            width = width % 2 == 0 ? width : width + 1;
            media.setOutputWidth(width);
        }

        if (height != null) {
            if (height <= 0) {
                logger.error("Invalid output height: {}", height);
                throw new IllegalArgumentException("Output height must be positive");
            }
            height = height % 2 == 0 ? height : height + 1;
            media.setOutputHeight(height);
        }

        media.setStatus("CONFIGURED");
        aspectRatioMediaRepository.save(media);

        logger.info("Successfully updated output resolution for user: {}, mediaId: {}", user.getId(), mediaId);
        return media;
    }

    public AspectRatioMedia initiateProcessing(User user, Long mediaId) throws IOException {
        logger.info("Initiating aspect ratio processing for user: {}, mediaId: {}", user.getId(), mediaId);

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> {
                    logger.error("Media not found for id: {}", mediaId);
                    return new IllegalArgumentException("Media not found");
                });

        if (!media.getUser().getId().equals(user.getId())) {
            logger.error("User {} not authorized to process aspect ratio for media {}", user.getId(), mediaId);
            throw new IllegalArgumentException("Not authorized to process aspect ratio for this media");
        }

        if (media.getAspectRatio() == null && (media.getOutputWidth() == null || media.getOutputHeight() == null)) {
            logger.error("Neither aspect ratio nor output resolution configured for mediaId: {}", mediaId);
            throw new IllegalStateException("Either aspect ratio or output resolution must be configured before processing");
        }

        // Allow processing for UPLOADED, CONFIGURED, FAILED, or SUCCESS videos (re-export)
        if ("PENDING".equals(media.getStatus()) || "PROCESSING".equals(media.getStatus())) {
            throw new IllegalStateException("Media is already being processed");
        }

        // Reset fields for processing/re-processing
        media.setStatus("PENDING");
        media.setProgress(10.0);
        if ("SUCCESS".equals(media.getStatus())) {
            // Clear previous outputs for re-export
            media.setProcessedCdnUrl(null);
            media.setProcessedPath(null);
            media.setProcessedFileName(null);
        }
        aspectRatioMediaRepository.save(media);

        // Queue processing task
        Map<String, Object> taskDetails = new HashMap<>();
        taskDetails.put("mediaId", mediaId.toString());
        taskDetails.put("taskType", "ASPECT_RATIO");
        taskDetails.put("userId", user.getId().toString());
        taskDetails.put("originalPath", media.getOriginalPath());
        taskDetails.put("aspectRatio", media.getAspectRatio());
        taskDetails.put("outputWidth", media.getOutputWidth() != null ? media.getOutputWidth().toString() : null);
        taskDetails.put("outputHeight", media.getOutputHeight() != null ? media.getOutputHeight().toString() : null);
        taskDetails.put("positionX", media.getPositionX() != null ? media.getPositionX().toString() : "0");
        taskDetails.put("positionY", media.getPositionY() != null ? media.getPositionY().toString() : "0");
        taskDetails.put("scale", media.getScale() != null ? media.getScale().toString() : "1.0");
        taskDetails.put("originalFileName", media.getOriginalFileName());

        String messageBody = objectMapper.writeValueAsString(taskDetails);
        sqsService.sendMessage(messageBody, aspectRatioQueueUrl);

        logger.info("Queued aspect ratio processing task for mediaId: {}", mediaId);
        return media;
    }

    public void processAspectRatioTask(Map<String, Object> taskDetails) throws IOException, InterruptedException {
        Long mediaId = Long.parseLong(taskDetails.get("mediaId").toString());
        Long userId = Long.parseLong(taskDetails.get("userId").toString());
        String originalPath = taskDetails.get("originalPath").toString();
        String aspectRatio = (String) taskDetails.get("aspectRatio");
        Integer outputWidth = taskDetails.get("outputWidth") != null ? Integer.parseInt(taskDetails.get("outputWidth").toString()) : null;
        Integer outputHeight = taskDetails.get("outputHeight") != null ? Integer.parseInt(taskDetails.get("outputHeight").toString()) : null;
        int positionX = Integer.parseInt(taskDetails.get("positionX").toString());
        int positionY = Integer.parseInt(taskDetails.get("positionY").toString());
        double scale = Double.parseDouble(taskDetails.get("scale").toString());
        String originalFileName = taskDetails.get("originalFileName").toString();

        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        media.setStatus("PROCESSING");
        media.setProgress(20.0);
        aspectRatioMediaRepository.save(media);

        Path baseDirPath = Paths.get(baseDir).toAbsolutePath().normalize();
        Path tempDir = baseDirPath.resolve("temp/aspect-ratio/" + mediaId).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);

        String outputFileName = "aspect_ratio_" + System.currentTimeMillis() + "_" + originalFileName;
        String outputR2Path = String.format("aspect_ratio/%s/processed/%s", userId, outputFileName);
        Path tempOutputPath = tempDir.resolve(outputFileName);
        Path tempInputPath = tempDir.resolve(new File(originalPath).getName());

        try {
            // Download original media from R2
            cloudflareR2Service.downloadFile(originalPath, tempInputPath.toString());
            logger.debug("Downloaded media to: {}", tempInputPath);

            if (!Files.exists(tempInputPath) || Files.size(tempInputPath) == 0) {
                logger.error("Input file is missing or empty: {}", tempInputPath);
                throw new IOException("Input file is missing or empty");
            }

            validateInputFile(tempInputPath.toFile());

            Map<String, Object> videoInfo = getVideoInfo(tempInputPath.toFile());
            int originalWidth = (int) videoInfo.get("width");
            int originalHeight = (int) videoInfo.get("height");
            float fps = (float) videoInfo.get("fps");

            double totalDuration = getVideoDuration(tempInputPath.toFile());
            if (totalDuration <= 0) {
                logger.error("Invalid video duration: {}", totalDuration);
                throw new IOException("Invalid video duration");
            }

            // Process video
            renderAspectRatioVideo(
                    tempInputPath.toFile(),
                    tempOutputPath.toFile(),
                    aspectRatio,
                    outputWidth,
                    outputHeight,
                    positionX,
                    positionY,
                    scale,
                    originalWidth,
                    originalHeight,
                    fps,
                    mediaId,
                    totalDuration
            );

            // Upload processed media to R2
            cloudflareR2Service.uploadFile(tempOutputPath.toFile(), outputR2Path);
            logger.info("Uploaded processed media to R2: {}", outputR2Path);

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(outputR2Path, 0); // No expiration for CDN URL

            // Update media
            media.setProcessedFileName(outputFileName);
            media.setProcessedPath(outputR2Path);
            media.setProcessedCdnUrl(cdnUrl);
            media.setStatus("SUCCESS");
            media.setProgress(100.0);
            aspectRatioMediaRepository.save(media);

            logger.info("Successfully processed aspect ratio for mediaId: {}", mediaId);

        } catch (Exception e) {
            logger.error("Failed to process aspect ratio for mediaId {}: {}", mediaId, e.getMessage(), e);
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw e;
        } finally {
            cleanUpTempFiles(tempDir);
        }
    }

    public AspectRatioMedia getMediaStatus(Long mediaId, User user) {
        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        if (!media.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not authorized to view this media");
        }

        return media;
    }

    public List<AspectRatioMedia> getUserAspectRatioMedia(User user) {
        return aspectRatioMediaRepository.findByUser(user);
    }

    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email extracted from token");
                    return new RuntimeException("User not found");
                });
    }

    private boolean isValidAspectRatio(String aspectRatio) {
        if (aspectRatio == null) return false;
        String[] parts = aspectRatio.split(":");
        if (parts.length != 2) return false;
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            return width > 0 && height > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void validateInputFile(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffprobePath,
                "-i", inputFile.getAbsolutePath(),
                "-show_streams",
                "-show_format",
                "-print_format", "json",
                "-v", "quiet"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFprobe failed to validate input file {}: {}", inputFile.getAbsolutePath(), output.toString());
            throw new IOException("FFprobe failed to validate input file: " + output.toString());
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            List<?> streams = (List<?>) result.getOrDefault("streams", Collections.emptyList());
            if (streams.isEmpty()) {
                logger.error("No streams found in input file: {}", inputFile.getAbsolutePath());
                throw new IOException("No streams found in input file");
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", inputFile.getAbsolutePath(), output.toString());
            throw new IOException("Failed to parse FFprobe output: " + output.toString());
        }
    }

    private Map<String, Object> getVideoInfo(File inputFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-f", "null",
                "-"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg failed to get video info: " + output.toString());
        }

        Map<String, Object> info = new HashMap<>();
        String outputStr = output.toString();
        Pattern resolutionPattern = Pattern.compile("Stream.*Video:.* (\\d+)x(\\d+).*?([0-9.]+) fps");
        Matcher matcher = resolutionPattern.matcher(outputStr);
        if (matcher.find()) {
            info.put("width", Integer.parseInt(matcher.group(1)));
            info.put("height", Integer.parseInt(matcher.group(2)));
            info.put("fps", Float.parseFloat(matcher.group(3)));
        } else {
            throw new IOException("Could not parse video info from FFmpeg output");
        }

        return info;
    }

    private double getVideoDuration(File videoFile) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(
                ffprobePath,
                "-i", videoFile.getAbsolutePath(),
                "-show_entries", "format=duration",
                "-v", "quiet",
                "-of", "json"
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
                logger.debug("FFprobe output: {}", line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            logger.error("FFprobe failed to get video duration for {}: {}", videoFile.getAbsolutePath(), output.toString());
            throw new IOException("FFprobe failed to get video duration: " + output.toString());
        }

        try {
            Map<String, Object> result = objectMapper.readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
            Map<String, Object> format = (Map<String, Object>) result.get("format");
            if (format == null || !format.containsKey("duration")) {
                logger.error("No duration found in FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
                throw new IOException("No duration found in FFprobe output");
            }
            return Double.parseDouble(format.get("duration").toString());
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse FFprobe output for {}: {}", videoFile.getAbsolutePath(), output.toString());
            throw new IOException("Failed to parse FFprobe output: " + output.toString());
        }
    }

    private void renderAspectRatioVideo(
            File inputFile,
            File outputFile,
            String aspectRatio,
            Integer outputWidth,
            Integer outputHeight,
            int positionX,
            int positionY,
            double scale,
            int originalWidth,
            int originalHeight,
            float fps,
            Long mediaId,
            double totalDuration
    ) throws IOException, InterruptedException {

        int canvasWidth, canvasHeight;

        if (outputWidth != null && outputHeight != null) {
            canvasWidth = outputWidth;
            canvasHeight = outputHeight;
        } else if (aspectRatio != null) {
            String[] ratioParts = aspectRatio.split(":");
            int targetWidth = Integer.parseInt(ratioParts[0]);
            int targetHeight = Integer.parseInt(ratioParts[1]);
            double targetAspectRatio = (double) targetWidth / targetHeight;

            if (originalWidth / (double) originalHeight > targetAspectRatio) {
                canvasWidth = originalWidth;
                canvasHeight = (int) (canvasWidth / targetAspectRatio);
            } else {
                canvasHeight = originalHeight;
                canvasWidth = (int) (canvasHeight * targetAspectRatio);
            }
        } else {
            throw new IllegalStateException("Either output resolution or aspect ratio must be specified");
        }

        canvasWidth = canvasWidth % 2 == 0 ? canvasWidth : canvasWidth + 1;
        canvasHeight = canvasHeight % 2 == 0 ? canvasHeight : canvasHeight + 1;

        int scaledWidth = (int) (originalWidth * scale);
        int scaledHeight = (int) (originalHeight * scale);
        scaledWidth = scaledWidth % 2 == 0 ? scaledWidth : scaledWidth + 1;
        scaledHeight = scaledHeight % 2 == 0 ? scaledHeight : scaledHeight + 1;

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputFile.getAbsolutePath());

        StringBuilder filterComplex = new StringBuilder();
        filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                .append(":d=").append(String.format("%.6f", totalDuration)).append("[base];");

        filterComplex.append("[0:v]scale=");
        filterComplex.append(String.format("%d:%d", scaledWidth, scaledHeight));
        filterComplex.append(":flags=lanczos[scaled];");

        String xExpr = String.format("(W/2)+(%d)-(w/2)", positionX);
        String yExpr = String.format("(H/2)+(%d)-(h/2)", positionY);
        filterComplex.append("[base][scaled]overlay=x='").append(xExpr)
                .append("':y='").append(yExpr)
                .append("':format=auto[vout]");

        command.add("-filter_complex");
        command.add(filterComplex.toString());

        command.add("-map");
        command.add("0:a?");
        command.add("-map");
        command.add("[vout]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-r");
        command.add(String.format("%.2f", fps));
        command.add("-y");
        command.add(outputFile.getAbsolutePath());

        executeFFmpegCommand(command, mediaId, totalDuration);
    }

    private void executeFFmpegCommand(List<String> command, Long mediaId, double totalDuration) throws IOException, InterruptedException {
        AspectRatioMedia media = aspectRatioMediaRepository.findById(mediaId)
                .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));

        List<String> updatedCommand = new ArrayList<>(command);
        updatedCommand.add("-progress");
        updatedCommand.add("pipe:");

        ProcessBuilder processBuilder = new ProcessBuilder(updatedCommand);
        processBuilder.redirectErrorStream(true);

        String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
        String tempDirPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "logs";
        File tempDir = new File(tempDirPath);
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logger.error("Failed to create temp directory: {}", tempDir.getAbsolutePath());
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw new IOException("Failed to create temp directory");
        }

        File commandLogFile = new File(tempDir, "ffmpeg_command_aspect_ratio_" + mediaId + ".txt");
        try (PrintWriter writer = new PrintWriter(commandLogFile, "UTF-8")) {
            writer.println(String.join(" ", updatedCommand));
        }

        logger.debug("Executing FFmpeg command: {}", String.join(" ", updatedCommand));
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        double lastProgress = -1.0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.debug("FFmpeg: {}", line);
                if (line.startsWith("out_time_ms=") && !line.equals("out_time_ms=N/A")) {
                    try {
                        long outTimeUs = Long.parseLong(line.replace("out_time_ms=", ""));
                        double currentTime = outTimeUs / 1_000_000.0;
                        double totalProgress = Math.min(currentTime / totalDuration * 100.0, 100.0);

                        int roundedProgress = (int) Math.round(totalProgress);
                        if (roundedProgress != (int) lastProgress && roundedProgress >= 0 && roundedProgress <= 100 && roundedProgress % 10 == 0) {
                            media.setProgress((double) roundedProgress);
                            media.setStatus("PROCESSING");
                            aspectRatioMediaRepository.save(media);
                            logger.info("Progress updated: {}% for mediaId: {}", roundedProgress, mediaId);
                            lastProgress = roundedProgress;
                        }
                    } catch (NumberFormatException e) {
                        logger.error("Failed to parse out_time_ms: {}", line);
                    }
                }
            }
        }

        boolean completed = process.waitFor(30, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            File errorLogFile = new File(tempDir, "ffmpeg_error_aspect_ratio_" + mediaId + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            }
            throw new RuntimeException("FFmpeg process timed out after 30 minutes for mediaId: " + mediaId + ". Output logged to: " + errorLogFile.getAbsolutePath());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            File errorLogFile = new File(tempDir, "ffmpeg_error_aspect_ratio_" + mediaId + ".txt");
            try (PrintWriter writer = new PrintWriter(errorLogFile, "UTF-8")) {
                writer.println(output.toString());
            }
            media.setStatus("FAILED");
            media.setProgress(0.0);
            aspectRatioMediaRepository.save(media);
            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode + " for mediaId: " + mediaId + ". Output logged to: " + errorLogFile.getAbsolutePath());
        }
    }

    private void cleanUpTempFiles(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete temp file: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to clean up temp directory: {}", tempDir, e);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "media_" + System.currentTimeMillis() + ".mp4";
        return filename.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}