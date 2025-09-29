package com.example.Scenith.dto;

import com.example.Scenith.entity.VideoFilterJob;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoFilterJobResponse {
    private Long id;
    private Long userId;
    private String inputVideoPath;
    private String outputVideoPath;
    private String cdnUrl;
    private String filterName;
    private Double brightness;
    private Double contrast;
    private Double saturation;
    private Double temperature;
    private Double gamma;
    private Double shadows;
    private Double highlights;
    private Double vibrance;
    private Double hue;
    private Double exposure;
    private Double tint;
    private Double sharpness;
    private String presetName;
    private String lutPath;
    private VideoFilterJob.ProcessingStatus status;
    private Integer progressPercentage;
}