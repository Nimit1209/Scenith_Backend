package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "video_filter_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoFilterJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "uploaded_video_id", nullable = false)
    private VideoFilterUpload uploadedVideo;

    @Column(name = "output_video_path", length = 500)
    private String outputVideoPath;

    @Column(name = "cdn_url", length = 500)
    private String cdnUrl;

    @Column(name = "filter_name", length = 100)
    private String filterName;

    @Column(name = "brightness", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double brightness = 0.0;

    @Column(name = "contrast", columnDefinition = "DECIMAL(5,2) DEFAULT 1.0")
    private Double contrast = 1.0;

    @Column(name = "saturation", columnDefinition = "DECIMAL(5,2) DEFAULT 1.0")
    private Double saturation = 1.0;

    @Column(name = "temperature", columnDefinition = "DECIMAL(7,2) DEFAULT 6500.0")
    private Double temperature = 6500.0;

    @Column(name = "gamma", columnDefinition = "DECIMAL(5,2) DEFAULT 1.0")
    private Double gamma = 1.0;

    @Column(name = "shadows", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double shadows = 0.0;

    @Column(name = "highlights", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double highlights = 0.0;

    @Column(name = "vibrance", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double vibrance = 0.0;

    @Column(name = "hue", columnDefinition = "DECIMAL(6,2) DEFAULT 0.0")
    private Double hue = 0.0;

    @Column(name = "exposure", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double exposure = 0.0;

    @Column(name = "tint", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double tint = 0.0;

    @Column(name = "sharpness", columnDefinition = "DECIMAL(5,2) DEFAULT 0.0")
    private Double sharpness = 0.0;

    @Column(name = "preset_name", length = 50)
    private String presetName;

    @Column(name = "lut_path", length = 500)
    private String lutPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProcessingStatus status = ProcessingStatus.PENDING;

    @Column(name = "progress_percentage", columnDefinition = "INTEGER DEFAULT 0")
    private Integer progressPercentage = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}