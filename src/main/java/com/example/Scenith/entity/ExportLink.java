package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "export_links")
public class ExportLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;
    private String fileName;
    private String downloadUrl;  // Store the presigned URL
    private String r2Path;       // Store the R2 path for regenerating URLs
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt; // Track when the presigned URL expires

    // Constructors
    public ExportLink() {}

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getR2Path() {
        return r2Path;
    }

    public void setR2Path(String r2Path) {
        this.r2Path = r2Path;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    // Helper method to check if URL has expired
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}