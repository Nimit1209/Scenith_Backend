package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "compressed_media")
public class CompressedMedia {
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

    @Column(name = "processed_file_name")
    private String processedFileName;

    @Column(name = "processed_path")
    private String processedPath;

    @Column(name = "original_cdn_url")
    private String originalCdnUrl;

    @Column(name = "processed_cdn_url")
    private String processedCdnUrl;

    @Column(name = "target_size", nullable = false)
    private String targetSize;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_message", length = 4000)  // or 4000
    private String errorMessage;

    public CompressedMedia() {
        this.status = "PENDING";
    }
}