package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "standalone_images")
public class StandaloneImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "original_path", nullable = false)
    private String originalPath;

    @Column(name = "processed_file_name")
    private String processedFileName;

    @Column(name = "processed_path")
    private String processedPath;

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "original_presigned_url", length = 512) // Increased length
    private String originalPresignedUrl;

    @Column(name = "processed_cdn_url")
    private String processedCdnUrl;

    @Column(name = "processed_presigned_url", length = 512) // Increased length
    private String processedPresignedUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_message",columnDefinition = "TEXT")
    private String errorMessage;

    // Constructors
    public StandaloneImage() {
        this.createdAt = LocalDateTime.now();
        this.status = "PENDING";
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }

    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }

    public String getProcessedFileName() { return processedFileName; }
    public void setProcessedFileName(String processedFileName) { this.processedFileName = processedFileName; }

    public String getProcessedPath() { return processedPath; }
    public void setProcessedPath(String processedPath) { this.processedPath = processedPath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getOriginalCdnUrl() { return originalCdnUrl; }
    public void setOriginalCdnUrl(String originalCdnUrl) { this.originalCdnUrl = originalCdnUrl; }

    public String getOriginalPresignedUrl() { return originalPresignedUrl; }
    public void setOriginalPresignedUrl(String originalPresignedUrl) { this.originalPresignedUrl = originalPresignedUrl; }

    public String getProcessedCdnUrl() { return processedCdnUrl; }
    public void setProcessedCdnUrl(String processedCdnUrl) { this.processedCdnUrl = processedCdnUrl; }

    public String getProcessedPresignedUrl() { return processedPresignedUrl; }
    public void setProcessedPresignedUrl(String processedPresignedUrl) { this.processedPresignedUrl = processedPresignedUrl; }
}