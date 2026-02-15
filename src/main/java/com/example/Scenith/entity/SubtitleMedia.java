package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "subtitle_media")
public class SubtitleMedia {
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

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "processed_file_name")
    private String processedFileName;

    @Column(name = "processed_path")
    private String processedPath;

    @Column(name = "processed_cdn_url")
    private String processedCdnUrl;

    @Column(name = "subtitles_json", columnDefinition = "LONGTEXT")
    private String subtitlesJson; // Stores subtitles as JSON array of TextSegment-like objects

    @Column(name = "status", nullable = false)
    private String status;

    @Column(length = 10)
    private String quality;

    private double progress;

    public SubtitleMedia() {
        this.status = "PENDING";
    }
}