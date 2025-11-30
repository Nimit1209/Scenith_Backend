package com.example.Scenith.dto.imagedto;

import lombok.Data;

@Data
public class UpdateImageProjectRequest {  // Remove 'public'
    private String projectName;
    private String designJson;
}