package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_conversions")
@Data
public class DocumentConversion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "operation_type", nullable = false)
    private String operationType; // MERGE_PDF, SPLIT_PDF, COMPRESS_PDF, ROTATE_PDF, IMAGES_TO_PDF, PDF_TO_IMAGES, ADD_WATERMARK, LOCK_PDF, UNLOCK_PDF

    @Column(name = "source_upload_ids", columnDefinition = "TEXT")
    private String sourceUploadIds; // JSON array of DocumentUpload IDs used as input

    @Column(name = "output_file_name")
    private String outputFileName;

    @Column(name = "output_path")
    private String outputPath;

    @Column(name = "output_cdn_url")
    private String outputCdnUrl;

    @Column(name = "output_presigned_url", length = 512)
    private String outputPresignedUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status", nullable = false)
    private String status; // PENDING, PROCESSING, SUCCESS, FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processing_options", columnDefinition = "TEXT")
    private String processingOptions; // JSON for operation-specific options

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    // Constructors
    public DocumentConversion() {
        this.createdAt = LocalDateTime.now();
        this.status = "PENDING";
    }
}