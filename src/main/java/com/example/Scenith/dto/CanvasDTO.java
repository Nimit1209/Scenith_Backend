package com.example.Scenith.dto;

import lombok.Data;

@Data
public class CanvasDTO {  // Remove 'public', keep it package-private
    private Integer width;
    private Integer height;
    private String backgroundColor;
}