package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "podcast_clip_media")
public class PodcastClipMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_url")
    private String sourceUrl; // YouTube URL or null if local upload

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "original_r2_path")
    private String originalR2Path; // R2 path for original file

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "clips_json", columnDefinition = "TEXT")
    private String clipsJson; // JSON array of clip metadata

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "progress")
    private Double progress;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    public PodcastClipMedia() {
        this.status = "PENDING";
        this.progress = 0.0;
        this.createdAt = LocalDateTime.now();
        this.lastModified = LocalDateTime.now();
    }
}