package com.example.Scenith.dto;

import lombok.Data;

@Data
public class UpdateImageProjectRequest {  // Remove 'public'
    private String projectName;
    private String designJson;
}