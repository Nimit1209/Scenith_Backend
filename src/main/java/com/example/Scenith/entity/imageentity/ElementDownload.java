package com.example.Scenith.entity.imageentity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "element_downloads")
@Data
public class ElementDownload {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "element_id", nullable = false)
    private Long elementId;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "download_format", nullable = false)
    private String downloadFormat; // SVG, PNG, JPG, JPEG
    
    @Column(name = "resolution")
    private String resolution; // e.g., "512x512", "1024x1024", null for SVG
    
    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt;
    
    @PrePersist
    protected void onCreate() {
        downloadedAt = LocalDateTime.now();
    }
}