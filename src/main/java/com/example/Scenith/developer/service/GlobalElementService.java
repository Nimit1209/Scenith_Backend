package com.example.Scenith.developer.service;


import com.example.Scenith.developer.entity.Developer;
import com.example.Scenith.developer.entity.GlobalElement;
import com.example.Scenith.developer.repository.DeveloperRepository;
import com.example.Scenith.developer.repository.GlobalElementRepository;
import com.example.Scenith.dto.ElementDto;
import com.example.Scenith.service.CloudflareR2Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
    private final CloudflareR2Service cloudflareR2Service; // Updated field

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    public GlobalElementService(
            GlobalElementRepository globalElementRepository,
            DeveloperRepository developerRepository,
            ObjectMapper objectMapper,
            CloudflareR2Service cloudflareR2Service) { // Updated constructor
        this.globalElementRepository = globalElementRepository;
        this.developerRepository = developerRepository;
        this.objectMapper = objectMapper;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    @Transactional
    public List<ElementDto> uploadGlobalElements(MultipartFile[] files, String title, String type, String category, String username) throws IOException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Developer not found"));

        List<ElementDto> elements = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !isValidFileType(originalFileName)) {
                throw new RuntimeException("Invalid file type. Only PNG, JPEG, GIF, or WEBP allowed.");
            }

            // Handle filename conflicts (simplified, assuming B2 handles overwrites or unique naming)
            String fileName = originalFileName;

            // Save to temporary file
            String tempPath = baseDir + "/temp/elements/" + fileName;
            File tempFile = cloudflareR2Service.saveMultipartFileToTemp(file, tempPath);

            // Upload to Cloudflare R2
            String r2Path = "elements/" + fileName;
            cloudflareR2Service.uploadFile(tempFile, r2Path);

            // Clean up temporary file
            Files.deleteIfExists(tempFile.toPath());
            // Create JSON for globalElement_json
            Map<String, String> elementData = new HashMap<>();
            elementData.put("imagePath", r2Path);
            elementData.put("imageFileName", fileName);
            String json = objectMapper.writeValueAsString(elementData);

            GlobalElement element = new GlobalElement();
            element.setGlobalElementJson(json);
            globalElementRepository.save(element);

            ElementDto dto = new ElementDto();
            dto.setId(element.getId().toString());
            dto.setFilePath(r2Path);
            dto.setFileName(fileName);
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
}