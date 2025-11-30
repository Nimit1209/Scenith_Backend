package com.example.Scenith.dto.imagedto;

import lombok.Data;
import java.util.List;

@Data
public class DesignDTO {
    private String version;
    private CanvasDTO canvas;
    private List<LayerDTO> layers;
}