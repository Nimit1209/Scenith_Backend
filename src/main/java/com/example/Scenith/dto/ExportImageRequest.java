package com.example.Scenith.dto;

import lombok.Data;

@Data
public class ExportImageRequest {  // Remove 'public'
    private String format;
    private Integer quality;
}