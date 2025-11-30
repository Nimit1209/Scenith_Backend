package com.example.Scenith.dto.imagedto;

import lombok.Data;

@Data
public class ExportImageRequest {  // Remove 'public'
    private String format;
    private Integer quality;
}