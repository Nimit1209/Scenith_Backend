// src/main/java/com/example/videoeditor/developer/controller/PublicElementController.java
package com.example.Scenith.developer.controller;


import com.example.Scenith.developer.service.GlobalElementService;
import com.example.Scenith.dto.ElementDto;
import com.example.Scenith.service.CloudflareR2Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class GlobalController {
    private final GlobalElementService globalElementService;
    private final CloudflareR2Service cloudflareR2Service; // Added field

    private String globalElementsDirectory = "elements/";

    public GlobalController(GlobalElementService globalElementService, CloudflareR2Service cloudflareR2Service) {
        this.globalElementService = globalElementService;
        this.cloudflareR2Service = cloudflareR2Service;
    }

    @GetMapping("/global-elements")
    public ResponseEntity<List<ElementDto>> getGlobalElements() {
        List<ElementDto> elements = globalElementService.getGlobalElements();
        return ResponseEntity.ok(elements);
    }

    @GetMapping("/global-elements/{filename:.+}")
    public ResponseEntity<Void> serveElement(@PathVariable String filename) throws IOException {
        String r2Path = "elements/" + filename;
        // Check if the file exists in R2
        if (!cloudflareR2Service.fileExists(r2Path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Generate a pre-signed URL for the file
        String preSignedUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600); // 1-hour expiration

        // Redirect the client to the pre-signed URL
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", preSignedUrl)
                .build();
    }

    private String determineContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}