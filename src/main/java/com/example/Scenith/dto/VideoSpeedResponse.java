package com.example.Scenith.dto;

import lombok.Data;

@Data
public class VideoSpeedResponse {
    private Long id;
    private String status;
    private Double progress;
    private Double speed;
    private String cdnUrl;
    private String originalFilePath;
}