package com.example.Scenith.entity.imageentity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "image_projects")
public class ImageProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(name = "canvas_width", nullable = false)
    private Integer canvasWidth;

    @Column(name = "canvas_height", nullable = false)
    private Integer canvasHeight;

    @Column(name = "canvas_background_color")
    private String canvasBackgroundColor;

    @Column(name = "design_json", columnDefinition = "TEXT")
    private String designJson; // Complete canvas state with all layers

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "status", nullable = false)
    private String status; // DRAFT, PROCESSING, COMPLETED, FAILED

    @Column(name = "last_exported_url")
    private String lastExportedUrl;

    @Column(name = "last_export_format")
    private String lastExportFormat; // PNG, JPG, PDF

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "DRAFT";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}