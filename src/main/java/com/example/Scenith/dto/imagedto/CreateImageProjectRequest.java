package com.example.Scenith.dto.imagedto;

import lombok.Data;

@Data
public class CreateImageProjectRequest {
    private String projectName;
    private Integer canvasWidth;
    private Integer canvasHeight;
    private String canvasBackgroundColor;
}