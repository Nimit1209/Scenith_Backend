package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document_conversions")
public class DocumentConversion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "operation_type", nullable = false)
    private String operationType; // WORD_TO_PDF, PDF_TO_WORD, MERGE_PDF, SPLIT_PDF, COMPRESS_PDF, etc.

    @Column(name = "original_file_names", columnDefinition = "TEXT")
    private String originalFileNames; // JSON array for multiple files

    @Column(name = "original_paths", columnDefinition = "TEXT")
    private String originalPaths; // JSON array for multiple file paths

    @Column(name = "output_file_name")
    private String outputFileName;

    @Column(name = "output_path")
    private String outputPath;

    @Column(name = "original_cdn_urls", columnDefinition = "TEXT")
    private String originalCdnUrls; // JSON array

    @Column(name = "original_presigned_urls", columnDefinition = "TEXT")
    private String originalPresignedUrls; // JSON array

    @Column(name = "output_cdn_url")
    private String outputCdnUrl;

    @Column(name = "output_presigned_url", length = 512)
    private String outputPresignedUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status", nullable = false)
    private String status; // PENDING, PROCESSING, SUCCESS, FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processing_options", columnDefinition = "TEXT")
    private String processingOptions; // JSON for operation-specific options

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    // Constructors
    public DocumentConversion() {
        this.createdAt = LocalDateTime.now();
        this.status = "PENDING";
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getOriginalFileNames() { return originalFileNames; }
    public void setOriginalFileNames(String originalFileNames) { this.originalFileNames = originalFileNames; }

    public String getOriginalPaths() { return originalPaths; }
    public void setOriginalPaths(String originalPaths) { this.originalPaths = originalPaths; }

    public String getOutputFileName() { return outputFileName; }
    public void setOutputFileName(String outputFileName) { this.outputFileName = outputFileName; }

    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }

    public String getOriginalCdnUrls() { return originalCdnUrls; }
    public void setOriginalCdnUrls(String originalCdnUrls) { this.originalCdnUrls = originalCdnUrls; }

    public String getOriginalPresignedUrls() { return originalPresignedUrls; }
    public void setOriginalPresignedUrls(String originalPresignedUrls) { this.originalPresignedUrls = originalPresignedUrls; }

    public String getOutputCdnUrl() { return outputCdnUrl; }
    public void setOutputCdnUrl(String outputCdnUrl) { this.outputCdnUrl = outputCdnUrl; }

    public String getOutputPresignedUrl() { return outputPresignedUrl; }
    public void setOutputPresignedUrl(String outputPresignedUrl) { this.outputPresignedUrl = outputPresignedUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getProcessingOptions() { return processingOptions; }
    public void setProcessingOptions(String processingOptions) { this.processingOptions = processingOptions; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
}