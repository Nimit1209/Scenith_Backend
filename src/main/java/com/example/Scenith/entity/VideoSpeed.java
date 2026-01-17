package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_speed")
public class VideoSpeed {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_file_path", nullable = false)
    private String originalFilePath;

    @Column(name = "output_file_path")
    private String outputFilePath;

    @Column(name = "speed", nullable = false)
    private Double speed = 1.0; // Default speed is 1.0

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "progress", nullable = false)
    private Double progress;

    @Column(name = "cdn_url")
    private String cdnUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_modified", nullable = false)
    private LocalDateTime lastModified;

    @Column(length = 10)
    private String quality;
}