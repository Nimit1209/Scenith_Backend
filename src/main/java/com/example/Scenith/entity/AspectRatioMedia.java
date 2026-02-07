package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "aspect_ratio_media")
public class AspectRatioMedia {
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

    @Column(name = "aspect_ratio")
    private String aspectRatio;

    @Column(name = "output_width")
    private Integer outputWidth;

    @Column(name = "output_height")
    private Integer outputHeight;

    @Column(name = "position_x")
    private Integer positionX;

    @Column(name = "position_y")
    private Integer positionY;

    @Column(name = "scale")
    private Double scale;

    @Column(name = "status", nullable = false)
    private String status;

    private Double progress;

    public AspectRatioMedia() {
        this.status = "PENDING";
    }
}