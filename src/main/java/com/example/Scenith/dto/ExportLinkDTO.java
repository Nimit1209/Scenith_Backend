package com.example.Scenith.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExportLinkDTO {
    private String fileName;
    private String downloadUrl;
    private String r2Path;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

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
}