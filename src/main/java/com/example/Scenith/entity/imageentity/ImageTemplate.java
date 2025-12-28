package com.example.Scenith.entity.imageentity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "image_templates")
public class ImageTemplate {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "description")
    private String description;

    @Column(name = "category")
    private String category; // "social-media", "business", "marketing", "presentation", etc.

    @Column(name = "canvas_width", nullable = false)
    private Integer canvasWidth;

    @Column(name = "canvas_height", nullable = false)
    private Integer canvasHeight;

    @Column(name = "design_json", columnDefinition = "TEXT", nullable = false)
    private String designJson; // Complete template design with all layers

    @Column(name = "thumbnail_url")
    private String thumbnailUrl; // Preview image of the template

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_premium", nullable = false)
    private Boolean isPremium = false;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(name = "tags")
    private String tags; // Comma-separated for search

    @Column(name = "usage_count")
    private Long usageCount = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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