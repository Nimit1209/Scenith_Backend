package com.example.Scenith.service;

import com.example.Scenith.config.PresetConfig;
import com.example.Scenith.dto.VideoFilterJobRequest;
import com.example.Scenith.dto.VideoFilterJobResponse;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoFilterJob;
import com.example.Scenith.entity.VideoFilterUpload;
import com.example.Scenith.repository.VideoFilterJobRepository;
import com.example.Scenith.sqs.SqsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VideoFilterJobServiceImpl implements VideoFilterJobService {

    private static final Logger logger = LoggerFactory.getLogger(VideoFilterJobServiceImpl.class);

    private final VideoFilterJobRepository repository;
    private final VideoFilterUploadService uploadService;
    private final PresetConfig presetConfig;
    private final CloudflareR2Service cloudflareR2Service;
    private final SqsService sqsService;
    private final ObjectMapper objectMapper;

    @Value("${app.ffmpeg-path}")
    private String FFMPEG_PATH;

    @Value("${app.temp.dir}")
    private String TEMP_DIR;

    @Value("${sqs.queue.url}")
    private String videoExportQueueUrl;


    @Override
    public VideoFilterJobResponse createJobFromUpload(Long uploadId, VideoFilterJobRequest request, User user) {
        Optional<VideoFilterJob> existingJob = repository.findByUploadedVideoIdAndStatus(uploadId, VideoFilterJob.ProcessingStatus.PENDING);
        if (existingJob.isPresent()) {
            return mapToResponse(existingJob.get()); // Return existing job
        }

        VideoFilterUpload upload = uploadService.getVideoById(uploadId, user);

        VideoFilterJob job = VideoFilterJob.builder()
                .user(user)
                .uploadedVideo(upload)
                .filterName(request.getFilterName())
                .brightness(request.getBrightness())
                .contrast(request.getContrast())
                .saturation(request.getSaturation())
                .temperature(request.getTemperature())
                .gamma(request.getGamma())
                .shadows(request.getShadows())
                .highlights(request.getHighlights())
                .vibrance(request.getVibrance())
                .hue(request.getHue())
                .exposure(request.getExposure())
                .tint(request.getTint())
                .sharpness(request.getSharpness())
                .presetName(request.getPresetName())
                .lutPath(request.getLutPath())
                .status(VideoFilterJob.ProcessingStatus.PENDING)
                .progressPercentage(0)
                .build();

        VideoFilterJob saved = repository.save(job);
        return mapToResponse(saved);
    }

    @Override
    public VideoFilterJobResponse getJob(Long jobId, User user) {
        VideoFilterJob job = repository.findById(jobId)
                .filter(j -> j.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RuntimeException("Job not found or not authorized"));

        // Refresh CDN URL if needed
        if (job.getOutputVideoPath() != null && job.getCdnUrl() != null) {
            try {
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(job.getOutputVideoPath(), 3600);
                job.setCdnUrl(cdnUrl);
                repository.save(job);
            } catch (Exception e) {
                logger.warn("Failed to refresh CDN URL for jobId: {}, path: {}, error: {}",
                        jobId, job.getOutputVideoPath(), e.getMessage());
            }
        }
        return mapToResponse(job);
    }

    @Override
    public List<VideoFilterJobResponse> getJobsByUser(User user) {
        List<VideoFilterJob> jobs = repository.findByUserId(user.getId());
        for (VideoFilterJob job : jobs) {
            if (job.getOutputVideoPath() != null && job.getCdnUrl() != null) {
                try {
                    String cdnUrl = cloudflareR2Service.generateDownloadUrl(job.getOutputVideoPath(), 3600);
                    job.setCdnUrl(cdnUrl);
                    repository.save(job);
                } catch (Exception e) {
                    logger.warn("Failed to refresh CDN URL for jobId: {}, path: {}, error: {}",
                            job.getId(), job.getOutputVideoPath(), e.getMessage());
                }
            }
        }
        return jobs.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public void processJob(Long jobId, User user) {
        VideoFilterJob job = repository.findById(jobId)
                .filter(j -> j.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RuntimeException("Job not found or unauthorized"));

        if (job.getStatus() == VideoFilterJob.ProcessingStatus.PROCESSING) {
            throw new RuntimeException("Job is already processing");
        }

        // Update status to PROCESSING
        job.setOutputVideoPath(null);
        job.setCdnUrl(null);
        job.setStatus(VideoFilterJob.ProcessingStatus.PROCESSING);
        job.setProgressPercentage(0);
        repository.save(job);

        // Send job to SQS
        try {
            Map<String, String> taskDetails = Map.of(
                    "jobId", String.valueOf(jobId),
                    "userId", String.valueOf(user.getId()),
                    "taskType","VIDEO_FILTER"
            );
            String messageBody = objectMapper.writeValueAsString(taskDetails);
            sqsService.sendMessage(messageBody, videoExportQueueUrl);
            logger.info("Sent job {} to SQS queue {}", jobId, videoExportQueueUrl);
        } catch (Exception e) {
            job.setStatus(VideoFilterJob.ProcessingStatus.FAILED);
            job.setProgressPercentage(0);
            repository.save(job);
            throw new RuntimeException("Failed to send job to SQS: " + e.getMessage(), e);
        }
    }
    @Transactional
    @Override
    public void processJobFromSqs(Map<String, String> taskDetails) {
        Long jobId = Long.valueOf(taskDetails.get("jobId"));
        Long userId = Long.valueOf(taskDetails.get("userId"));

        VideoFilterJob job = repository.findByIdWithUploadedVideo(jobId)
                .filter(j -> j.getUser().getId().equals(userId))
                .orElseThrow(() -> new RuntimeException("Job not found or unauthorized"));

        if (job.getStatus() != VideoFilterJob.ProcessingStatus.PROCESSING) {
            logger.warn("Job {} is not in PROCESSING state, current state: {}", jobId, job.getStatus());
            return;
        }

        File tempInputFile = null;
        File tempOutputFile = null;
        File tempLutFile = null;
        try {
            // Download input video from R2
            String inputR2Path = job.getUploadedVideo().getFilePath();
            String tempInputPath = TEMP_DIR + "/input_" + System.currentTimeMillis() + ".mp4";
            tempInputFile = cloudflareR2Service.downloadFile(inputR2Path, tempInputPath);
            logger.info("Downloaded input video for job {}: {}", jobId, inputR2Path);

            // Download LUT file if provided
            String lutPath = job.getLutPath();
            if (lutPath != null && !lutPath.isEmpty()) {
                String tempLutPath = TEMP_DIR + "/lut_" + System.currentTimeMillis() + ".cube";
                try {
                    tempLutFile = cloudflareR2Service.downloadFile(lutPath, tempLutPath);
                    job.setLutPath(tempLutFile.getAbsolutePath());
                    logger.info("Downloaded LUT file for job {}: {}", jobId, lutPath);
                } catch (IOException e) {
                    logger.warn("LUT file not found or failed to download: {}, jobId: {}", lutPath, jobId);
                    job.setLutPath(null); // Proceed without LUT
                }
            }

            // Prepare output path
            String outputFileName = "filtered_" + System.currentTimeMillis() + ".mp4";
            String outputR2Path = String.format("videos/filtered/%d/filtered/%s", userId, outputFileName);
            String tempOutputPath = TEMP_DIR + "/" + outputFileName;
            tempOutputFile = new File(tempOutputPath);
            Files.createDirectories(tempOutputFile.toPath().getParent());

            // Build and execute FFmpeg command
            List<String> command = buildFFmpegCommand(job, tempInputFile.getAbsolutePath(), tempOutputFile.getAbsolutePath());
            executeFFmpegCommand(command, job);

            // Upload output to R2
            cloudflareR2Service.uploadFile(outputR2Path, tempOutputFile);
            logger.info("Uploaded output video for job {}: {}", jobId, outputR2Path);

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(outputR2Path, 3600);

            // Update job
            job.setOutputVideoPath(outputR2Path);
            job.setCdnUrl(cdnUrl);
            job.setStatus(VideoFilterJob.ProcessingStatus.COMPLETED);
            job.setProgressPercentage(100);
            repository.save(job);
            logger.info("Completed job {} for user {}", jobId, userId);

        } catch (Exception e) {
            logger.error("Failed to process job {}: {}", jobId, e.getMessage(), e);
            job.setStatus(VideoFilterJob.ProcessingStatus.FAILED);
            job.setProgressPercentage(0);
            repository.save(job);
            throw new RuntimeException("Processing failed: " + e.getMessage(), e);
        } finally {
            cleanupTempFile(tempInputFile, "input");
            cleanupTempFile(tempOutputFile, "output");
            cleanupTempFile(tempLutFile, "LUT");
        }
    }

    private void cleanupTempFile(File file, String type) {
        if (file != null && file.exists()) {
            try {
                Files.delete(file.toPath());
                logger.debug("Deleted temporary {} file: {}", type, file.getAbsolutePath());
            } catch (IOException e) {
                logger.warn("Failed to delete temporary {} file: {}, error: {}", type, file.getAbsolutePath(), e.getMessage());
            }
        }
    }

    private List<String> buildFFmpegCommand(VideoFilterJob job, String inputPath, String outputPath) {
        List<String> command = new ArrayList<>();
        command.add(FFMPEG_PATH);
        command.add("-i");
        command.add(inputPath);

        // Apply preset values if specified
        if (job.getPresetName() != null && !job.getPresetName().isEmpty()) {
            Map<String, Double> presetValues = presetConfig.getPreset(job.getPresetName());
            if (presetValues != null) {
                logger.info("Applying preset for job {}: {}", job.getId(), job.getPresetName());
                job.setBrightness(presetValues.get("brightness"));
                job.setContrast(presetValues.get("contrast"));
                job.setSaturation(presetValues.get("saturation"));
                job.setTemperature(presetValues.get("temperature"));
                job.setGamma(presetValues.get("gamma"));
                job.setShadows(presetValues.get("shadows"));
                job.setHighlights(presetValues.get("highlights"));
                job.setVibrance(presetValues.get("vibrance"));
                job.setHue(presetValues.get("hue"));
                job.setExposure(presetValues.get("exposure"));
                job.setTint(presetValues.get("tint"));
                job.setSharpness(presetValues.get("sharpness"));
            }
        }

        // Build filter chain
        List<String> filters = new ArrayList<>();

        if (job.getBrightness() != null && Math.abs(job.getBrightness()) > 0.001) {
            filters.add(String.format("eq=brightness=%.3f", job.getBrightness()));
        }
        if (job.getContrast() != null && Math.abs(job.getContrast() - 1.0) > 0.001) {
            filters.add(String.format("eq=contrast=%.3f", job.getContrast()));
        }
        if (job.getSaturation() != null && Math.abs(job.getSaturation() - 1.0) > 0.001) {
            filters.add(String.format("eq=saturation=%.3f", job.getSaturation()));
        }
        if (job.getGamma() != null && Math.abs(job.getGamma() - 1.0) > 0.001) {
            filters.add(String.format("eq=gamma=%.3f", job.getGamma()));
        }
        if (job.getHue() != null && Math.abs(job.getHue()) > 0.001) {
            filters.add(String.format("hue=h=%.1f", job.getHue()));
        }
        if (job.getTemperature() != null && Math.abs(job.getTemperature() - 6500.0) > 1.0) {
            double temp = job.getTemperature();
            if (temp < 6500) {
                double rs = Math.min(0.3, (6500 - temp) / 6500 * 0.3);
                filters.add(String.format("colorbalance=rs=%.3f", rs));
            } else if (temp > 6500) {
                double bs = Math.min(0.3, (temp - 6500) / 3500 * 0.3);
                filters.add(String.format("colorbalance=bs=%.3f", bs));
            }
        }
        if (job.getShadows() != null && Math.abs(job.getShadows()) > 0.001) {
            double shadowGamma = 1.0 - (job.getShadows() * 0.2);
            shadowGamma = Math.max(0.5, Math.min(2.0, shadowGamma));
            filters.add(String.format("eq=gamma_r=%.3f:gamma_g=%.3f:gamma_b=%.3f", shadowGamma, shadowGamma, shadowGamma));
        }
        if (job.getHighlights() != null && Math.abs(job.getHighlights()) > 0.001) {
            double highlightBrightness = job.getHighlights() * 0.1;
            filters.add(String.format("eq=brightness=%.3f", highlightBrightness));
        }
        if (job.getVibrance() != null && Math.abs(job.getVibrance()) > 0.001) {
            double vibranceSat = 1.0 + (job.getVibrance() * 0.3);
            vibranceSat = Math.max(0.0, Math.min(3.0, vibranceSat));
            filters.add(String.format("eq=saturation=%.3f", vibranceSat));
        }
        if (job.getExposure() != null && Math.abs(job.getExposure()) > 0.001) {
            filters.add(String.format("eq=brightness=%.3f", job.getExposure()));
        }
        if (job.getTint() != null && Math.abs(job.getTint()) > 0.001) {
            double tintBalance = job.getTint() * 0.01;
            tintBalance = Math.max(-0.5, Math.min(0.5, tintBalance));
            filters.add(String.format("colorbalance=gs=%.3f", tintBalance));
        }
        if (job.getSharpness() != null && Math.abs(job.getSharpness()) > 0.001) {
            if (job.getSharpness() > 0) {
                double sharpAmount = Math.min(2.0, job.getSharpness() * 1.5);
                filters.add(String.format("unsharp=5:5:%.2f:5:5:0.0", sharpAmount));
            } else {
                double blurAmount = Math.min(5.0, Math.abs(job.getSharpness()));
                filters.add(String.format("boxblur=%.2f", blurAmount));
            }
        }
        if (job.getLutPath() != null && !job.getLutPath().isEmpty()) {
            File lutFile = new File(job.getLutPath());
            if (lutFile.exists()) {
                filters.add(String.format("lut3d='%s'", job.getLutPath()));
            } else {
                logger.warn("LUT file not found for job {}: {}", job.getId(), job.getLutPath());
            }
        }

        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-profile:v");
        command.add("main");
        command.add("-level");
        command.add("3.1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-movflags");
        command.add("+faststart");
        command.add("-y");
        command.add(outputPath);

        logger.info("FFmpeg command for job {}: {}", job.getId(), String.join(" ", command));
        return command;
    }

    private void executeFFmpegCommand(List<String> command, VideoFilterJob job) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFmpeg output for job {}: {}", job.getId(), line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg process failed with exit code: " + exitCode);
        }
    }

    private VideoFilterJobResponse mapToResponse(VideoFilterJob job) {
        return VideoFilterJobResponse.builder()
                .id(job.getId())
                .userId(job.getUser().getId())
                .inputVideoPath(job.getUploadedVideo().getFilePath())
                .outputVideoPath(job.getOutputVideoPath())
                .cdnUrl(job.getCdnUrl())
                .filterName(job.getFilterName())
                .brightness(job.getBrightness())
                .contrast(job.getContrast())
                .saturation(job.getSaturation())
                .temperature(job.getTemperature())
                .gamma(job.getGamma())
                .shadows(job.getShadows())
                .highlights(job.getHighlights())
                .vibrance(job.getVibrance())
                .hue(job.getHue())
                .exposure(job.getExposure())
                .tint(job.getTint())
                .sharpness(job.getSharpness())
                .presetName(job.getPresetName())
                .lutPath(job.getLutPath())
                .status(job.getStatus())
                .progressPercentage(job.getProgressPercentage())
                .build();
    }

    @Transactional
    @Override
    public VideoFilterJobResponse updateJob(Long jobId, VideoFilterJobRequest request, User user) {
        VideoFilterJob job = repository.findByIdWithUploadedVideo(jobId)
                .filter(j -> j.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RuntimeException("Job not found or unauthorized"));

        if (job.getStatus() == VideoFilterJob.ProcessingStatus.PROCESSING) {
            throw new RuntimeException("Cannot update a job that is processing");
        }

        if (request.getFilterName() != null) job.setFilterName(request.getFilterName());
        if (request.getBrightness() != null) job.setBrightness(request.getBrightness());
        if (request.getContrast() != null) job.setContrast(request.getContrast());
        if (request.getSaturation() != null) job.setSaturation(request.getSaturation());
        if (request.getTemperature() != null) job.setTemperature(request.getTemperature());
        if (request.getGamma() != null) job.setGamma(request.getGamma());
        if (request.getShadows() != null) job.setShadows(request.getShadows());
        if (request.getHighlights() != null) job.setHighlights(request.getHighlights());
        if (request.getVibrance() != null) job.setVibrance(request.getVibrance());
        if (request.getHue() != null) job.setHue(request.getHue());
        if (request.getExposure() != null) job.setExposure(request.getExposure());
        if (request.getTint() != null) job.setTint(request.getTint());
        if (request.getSharpness() != null) job.setSharpness(request.getSharpness());
        if (request.getPresetName() != null) job.setPresetName(request.getPresetName());
        if (request.getLutPath() != null) job.setLutPath(request.getLutPath());

        VideoFilterJob saved = repository.save(job);
        return mapToResponse(saved);
    }
}