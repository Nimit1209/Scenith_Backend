package com.example.Scenith.service;

import com.example.Scenith.entity.StandaloneImage;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.StandaloneImageRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class StandaloneImageService {

  private static final Logger logger = LoggerFactory.getLogger(StandaloneImageService.class);

  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;
  private final CloudflareR2Service cloudflareR2Service;
  private final StandaloneImageRepository standaloneImageRepository;
  private final ObjectMapper objectMapper;

  @Value("${app.base-dir:/tmp}")
  private String baseDir;

  @Value("${python.path:/usr/local/bin/python3}")
  private String pythonPath;

  @Value("${app.background-removal-script-path:/app/scripts/remove_background.py}")
  private String backgroundRemovalScriptPath;

  public StandaloneImageService(
          JwtUtil jwtUtil,
          UserRepository userRepository,
          CloudflareR2Service cloudflareR2Service,
          StandaloneImageRepository standaloneImageRepository,
          ObjectMapper objectMapper) {
    this.jwtUtil = jwtUtil;
    this.userRepository = userRepository;
    this.cloudflareR2Service = cloudflareR2Service;
    this.standaloneImageRepository = standaloneImageRepository;
    this.objectMapper = objectMapper;
  }

  public StandaloneImage processStandaloneImageBackgroundRemoval(User user, MultipartFile imageFile)
          throws IOException, InterruptedException {
    logger.info("Processing standalone image for user: {}", user.getId());

    // Validate input
    if (imageFile == null || imageFile.isEmpty()) {
      logger.error("MultipartFile is null or empty for user: {}", user.getId());
      throw new IllegalArgumentException("Image file is null or empty");
    }

    // Ensure baseDir is absolute
    String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;

    // Create temporary files for processing
    String tempFileName = "standalone-" + System.currentTimeMillis() + "-" + imageFile.getOriginalFilename();
    String tempInputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + tempFileName;
    String outputFileName = "bg_removed_" + imageFile.getOriginalFilename();
    String tempOutputPath = absoluteBaseDir + File.separator + "videoeditor" + File.separator + outputFileName;

    File inputFile = null;
    File outputFile = null;
    try {
      // Save MultipartFile to temporary local file
      inputFile = cloudflareR2Service.saveMultipartFileToTemp(imageFile, tempInputPath);
      logger.debug("Saved input image to temporary file: {}", inputFile.getAbsolutePath());

      // Verify input file exists
      if (!inputFile.exists()) {
        logger.error("Input file not found: {}", inputFile.getAbsolutePath());
        throw new IOException("Input file not found: " + inputFile.getAbsolutePath());
      }

      // Verify Python script exists
      File scriptFile = new File(backgroundRemovalScriptPath);
      if (!scriptFile.exists()) {
        logger.error("Python script not found: {}", scriptFile.getAbsolutePath());
        throw new IOException("Python script not found: " + scriptFile.getAbsolutePath());
      }

      // Define R2 paths
      String r2OriginalPath = String.format("image/standalone/%s/original/%s", user.getId(), imageFile.getOriginalFilename());
      String r2ProcessedPath = String.format("image/standalone/%s/processed/%s", user.getId(), outputFileName);

      // Upload original file to R2
      cloudflareR2Service.uploadFile(r2OriginalPath, inputFile);
      logger.info("Uploaded original image to R2: {}", r2OriginalPath);

      // Run background removal script
      outputFile = new File(tempOutputPath);
      Files.createDirectories(outputFile.toPath().getParent());

      List<String> command = Arrays.asList(
              pythonPath,
              backgroundRemovalScriptPath,
              inputFile.getAbsolutePath(),
              outputFile.getAbsolutePath()
      );

      logger.debug("Executing command: {}", String.join(" ", command));
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      Process process;
      try {
        process = pb.start();
      } catch (IOException e) {
        logger.error("Failed to start Python process for user: {}, error: {}", user.getId(), e.getMessage());
        throw new IOException("Failed to start Python process: " + e.getMessage(), e);
      }

      StringBuilder output = new StringBuilder();
      StringBuilder errorOutput = new StringBuilder();
      try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
           BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line;
        while ((line = stdoutReader.readLine()) != null) {
          output.append(line).append("\n");
          logger.debug("stdout: {}", line);
        }
        while ((line = stderrReader.readLine()) != null) {
          errorOutput.append(line).append("\n");
          logger.debug("stderr: {}", line);
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        logger.error("Background removal failed for user: {}, exit code: {}, error: {}", user.getId(), exitCode, errorOutput);
        throw new IOException("Background removal failed with exit code: " + exitCode + ", error: " + errorOutput);
      }

      if (!outputFile.exists()) {
        logger.error("Output file not created: {}", outputFile.getAbsolutePath());
        throw new IOException("Output file not created: " + outputFile.getAbsolutePath());
      }

      // Upload processed file to R2
      cloudflareR2Service.uploadFile(r2ProcessedPath, outputFile);
      logger.info("Uploaded processed image to R2: {}", r2ProcessedPath);

      // Generate URLs for original and processed files
      Map<String, String> originalUrls = cloudflareR2Service.generateUrls(r2OriginalPath, 3600);
      Map<String, String> processedUrls = cloudflareR2Service.generateUrls(r2ProcessedPath, 3600);

      // Save standalone image record
      StandaloneImage standaloneImage = new StandaloneImage();
      standaloneImage.setUser(user);
      standaloneImage.setOriginalFileName(imageFile.getOriginalFilename());
      standaloneImage.setOriginalPath(r2OriginalPath);
      standaloneImage.setProcessedPath(r2ProcessedPath);
      standaloneImage.setProcessedFileName(outputFileName);
      standaloneImage.setOriginalCdnUrl(originalUrls.get("cdnUrl"));
      standaloneImage.setOriginalPresignedUrl(originalUrls.get("presignedUrl"));
      standaloneImage.setProcessedCdnUrl(processedUrls.get("cdnUrl"));
      standaloneImage.setProcessedPresignedUrl(processedUrls.get("presignedUrl"));
      standaloneImage.setStatus("SUCCESS");
      standaloneImage.setCreatedAt(LocalDateTime.now());
      standaloneImageRepository.save(standaloneImage);

      logger.info("Successfully processed and saved standalone image for user: {}", user.getId());
      return standaloneImage;

    } finally {
      // Clean up temporary files
      if (inputFile != null && inputFile.exists()) {
        try {
          Files.delete(inputFile.toPath());
          logger.debug("Deleted temporary input file: {}", inputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary input file: {}, error: {}", inputFile.getAbsolutePath(), e.getMessage());
        }
      }
      if (outputFile != null && outputFile.exists()) {
        try {
          Files.delete(outputFile.toPath());
          logger.debug("Deleted temporary output file: {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
          logger.warn("Failed to delete temporary output file: {}, error: {}", outputFile.getAbsolutePath(), e.getMessage());
        }
      }
    }
  }

  public User getUserFromToken(String token) {
    String email = jwtUtil.extractEmail(token.substring(7));
    return userRepository.findByEmail(email)
            .orElseThrow(() -> {
              logger.error("User not found for email extracted from token");
              return new RuntimeException("User not found");
            });
  }

  public List<StandaloneImage> getUserImages(User user) {
    return standaloneImageRepository.findByUser(user);
  }
}