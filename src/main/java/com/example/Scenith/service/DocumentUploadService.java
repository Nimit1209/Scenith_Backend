package com.example.Scenith.service;

import com.example.Scenith.entity.DocumentUpload;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.DocumentUploadRepository;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DocumentUploadService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentUploadService.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final CloudflareR2Service cloudflareR2Service;
    private final DocumentUploadRepository documentUploadRepository;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    @Autowired
    public DocumentUploadService(JwtUtil jwtUtil, UserRepository userRepository, CloudflareR2Service cloudflareR2Service, DocumentUploadRepository documentUploadRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.cloudflareR2Service = cloudflareR2Service;
        this.documentUploadRepository = documentUploadRepository;
    }

    /**
     * Upload multiple documents
     */
    public List<DocumentUpload> uploadDocuments(User user, List<MultipartFile> files) throws IOException {
        List<DocumentUpload> uploads = new ArrayList<>();
        String timestamp = String.valueOf(System.currentTimeMillis());

        for (MultipartFile file : files) {
            String originalFileName = file.getOriginalFilename();

            // Generate unique filename while preserving extension
            String uniqueFileName = generateUniqueFileName(originalFileName, timestamp);

            String fileType = determineFileType(originalFileName);

            // Save to temp and upload to R2
            String absoluteBaseDir = baseDir.startsWith("/") ? baseDir : "/" + baseDir;
            String workDir = absoluteBaseDir + File.separator + "videoeditor" + File.separator + "upload_" + timestamp;
            Files.createDirectories(Paths.get(workDir));

            String tempFilePath = workDir + File.separator + uniqueFileName;
            File tempFile = cloudflareR2Service.saveMultipartFileToTemp(file, tempFilePath);
            
            try {
                // Upload to R2
                String r2Path = String.format("documents/%s/uploads/%s/%s", user.getId(), timestamp, uniqueFileName);
                cloudflareR2Service.uploadFile(r2Path, tempFile);
                
                // Generate URLs
                Map<String, String> urls = cloudflareR2Service.generateUrls(r2Path, 3600);
                
                // Create upload record
                DocumentUpload upload = DocumentUpload.builder()
                        .fileName(originalFileName) // Store original name for display
                        .filePath(r2Path)
                        .cdnUrl(urls.get("cdnUrl"))
                        .presignedUrl(urls.get("presignedUrl"))
                        .fileType(fileType)
                        .fileSizeBytes(file.getSize())
                        .user(user)
                        .build();
                
                uploads.add(documentUploadRepository.save(upload));
                logger.info("Uploaded document: {} for user: {}", originalFileName, user.getId());
                
            } finally {
                // Clean up temp file
                if (tempFile.exists()) {
                    Files.delete(tempFile.toPath());
                }
            }
        }

        return uploads;
    }

    /**
     * Get user documents
     */
    public List<DocumentUpload> getUserDocuments(User user, String fileType) {
        if (fileType != null && !fileType.isEmpty()) {
            return documentUploadRepository.findByUserAndFileType(user, fileType);
        }
        return documentUploadRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Delete document
     */
    public void deleteDocument(User user, Long id) throws IOException {
        DocumentUpload upload = documentUploadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        if (!upload.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized access");
        }

        // Delete from R2
        cloudflareR2Service.deleteFile(upload.getFilePath());

        // Delete from database
        documentUploadRepository.delete(upload);

        logger.info("Deleted document {} for user {}", id, user.getId());
    }

    /**
     * Get user from token
     */
    public User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Determine file type from filename
     */
    private String determineFileType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".pdf")) {
            return "PDF";
        } else if (lowerName.matches(".*\\.(jpg|jpeg|png|gif|bmp|tiff)$")) {
            return "IMAGE";
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
            return "DOCX";
        }
        return "OTHER";
    }

    /**
     * Generate unique filename with timestamp to allow duplicate uploads
     */
    private String generateUniqueFileName(String originalFileName, String timestamp) {
        if (originalFileName == null || originalFileName.isEmpty()) {
            return "file_" + timestamp;
        }

        int lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            // No extension
            return originalFileName + "_" + timestamp;
        }

        String nameWithoutExtension = originalFileName.substring(0, lastDotIndex);
        String extension = originalFileName.substring(lastDotIndex);

        return nameWithoutExtension + "_" + timestamp + "_" + System.nanoTime() + extension;
    }
}