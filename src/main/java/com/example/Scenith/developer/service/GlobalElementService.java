package com.example.Scenith.developer.service;

import com.example.Scenith.developer.entity.Developer;
import com.example.Scenith.developer.entity.GlobalElement;
import com.example.Scenith.developer.repository.DeveloperRepository;
import com.example.Scenith.developer.repository.GlobalElementRepository;
import com.example.Scenith.dto.ElementDto;
import com.example.Scenith.service.CloudflareR2Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GlobalElementService {
    private final GlobalElementRepository globalElementRepository;
    private final DeveloperRepository developerRepository;
    private final ObjectMapper objectMapper;
    private final CloudflareR2Service cloudflareR2Service;

    private static final Logger logger = LoggerFactory.getLogger(GlobalElementService.class);

    public GlobalElementService(
            GlobalElementRepository globalElementRepository,
            DeveloperRepository developerRepository,
            ObjectMapper objectMapper,
            CloudflareR2Service cloudflareR2Service) {
        this.globalElementRepository = globalElementRepository;
        this.developerRepository = developerRepository;
        this.objectMapper = objectMapper;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    @Transactional
    public List<ElementDto> uploadGlobalElements(MultipartFile[] files, String title, String type, String category, String username) throws IOException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Developer not found: " + username));

        List<ElementDto> elements = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new IOException("Uploaded file is empty: " + file.getOriginalFilename());
            }

            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !isValidFileType(originalFileName)) {
                throw new RuntimeException("Invalid file type for '" + originalFileName + "'. Only PNG, JPEG, GIF, or WEBP allowed.");
            }

            // Sanitize filename
            String sanitizedFileName = originalFileName.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
            String uniqueFileName = System.currentTimeMillis() + "_" + sanitizedFileName;

            // Define R2 path
            String r2Path = "elements/" + uniqueFileName;
            logger.info("Uploading global element to R2: r2Path={}", r2Path);

            // Upload to Cloudflare R2
            String uploadedPath = cloudflareR2Service.uploadFile(file, r2Path);

            // Verify file existence in R2
            if (!cloudflareR2Service.fileExists(r2Path)) {
                logger.error("Uploaded element not found in R2: r2Path={}", r2Path);
                throw new IOException("Failed to verify uploaded element in R2: " + r2Path);
            }

            // Generate CDN URL
            String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600);

            // Create JSON for globalElement_json
            Map<String, String> elementData = new HashMap<>();
            elementData.put("imagePath", r2Path);
            elementData.put("imageFileName", uniqueFileName);
            elementData.put("cdnUrl", cdnUrl);
            elementData.put("originalFileName", originalFileName);
            String json = objectMapper.writeValueAsString(elementData);

            // Save to database
            GlobalElement element = new GlobalElement();
            element.setGlobalElementJson(json);
            globalElementRepository.save(element);

            // Create DTO
            ElementDto dto = new ElementDto();
            dto.setId(element.getId().toString());
            dto.setFilePath(r2Path);
            dto.setFileName(uniqueFileName);
            elements.add(dto);
        }

        return elements;
    }

    public List<ElementDto> getGlobalElements() {
        return globalElementRepository.findAll().stream()
                .map(this::toElementDto)
                .collect(Collectors.toList());
    }

    private ElementDto toElementDto(GlobalElement globalElement) {
        try {
            Map<String, String> jsonData = objectMapper.readValue(
                    globalElement.getGlobalElementJson(),
                    new TypeReference<Map<String, String>>() {}
            );
            ElementDto dto = new ElementDto();
            dto.setId(globalElement.getId().toString());
            dto.setFilePath(jsonData.get("imagePath"));
            dto.setFileName(jsonData.get("imageFileName"));
            return dto;
        } catch (IOException e) {
            throw new RuntimeException("Error parsing globalElement_json: " + e.getMessage());
        }
    }

    private boolean isValidFileType(String fileName) {
        String lowerCase = fileName.toLowerCase();
        return lowerCase.endsWith(".png") || lowerCase.endsWith(".jpg") ||
                lowerCase.endsWith(".jpeg") || lowerCase.endsWith(".gif") ||
                lowerCase.endsWith(".webp");
    }


    // In GlobalElementService.java
    @Transactional
    public void deleteGlobalElement(Long elementId, String username) throws IOException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Developer not found: " + username));

        GlobalElement element = globalElementRepository.findById(elementId)
                .orElseThrow(() -> new RuntimeException("Element not found: " + elementId));

        try {
            // Parse JSON to get R2 path
            Map<String, String> jsonData = objectMapper.readValue(
                    element.getGlobalElementJson(),
                    new TypeReference<Map<String, String>>() {}
            );
            String r2Path = jsonData.get("imagePath");

            // Delete file from Cloudflare R2
            if (cloudflareR2Service.fileExists(r2Path)) {
                cloudflareR2Service.deleteFile(r2Path);
                logger.info("Deleted file from R2: r2Path={}", r2Path);
            } else {
                logger.warn("File not found in R2: r2Path={}", r2Path);
            }

            // Delete from database
            globalElementRepository.delete(element);
            logger.info("Deleted global element: id={}, username={}", elementId, username);
        } catch (IOException e) {
            logger.error("Error deleting global element: id={}, username={}: {}", elementId, username, e.getMessage());
            throw new IOException("Failed to delete element: " + e.getMessage());
        }
    }
}