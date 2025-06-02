package com.example.Scenith.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExportLinkDetails {
    private String fileName;
    private String downloadUrl;
    private String r2Path;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}