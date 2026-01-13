package com.example.Scenith.entity.imageentity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "image_elements")
public class ImageElement {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "category")
    private String category; // e.g., "stickers", "icons", "decorations", "frames"

    @Column(name = "file_path", nullable = false)
    private String filePath; // Relative path: image_editor/elements/filename.png

    @Column(name = "cdn_url", nullable = false)
    private String cdnUrl; // Full CDN URL

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "file_format")
    private String fileFormat; // PNG, JPG, SVG

    @Column(name = "width")
    private Integer width; // Original width in pixels

    @Column(name = "height")
    private Integer height; // Original height in pixels

    @Column(name = "file_size")
    private Long fileSize; // In bytes

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(name = "tags")
    private String tags; // Comma-separated for search

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "download_count", nullable = false)
    private Long downloadCount = 0L;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}