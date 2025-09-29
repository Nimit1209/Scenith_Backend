package com.example.Scenith.dto;

import lombok.Data;

@Data
public class VideoFilterJobRequest {

    // filters
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

    // LUTs
    private String presetName;
    private String lutPath; // optional if user uploaded
}