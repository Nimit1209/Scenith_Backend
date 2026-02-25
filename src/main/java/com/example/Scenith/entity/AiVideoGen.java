package com.example.Scenith.entity;

import com.example.Scenith.enums.VideoGenModel;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_video_gen")
public class AiVideoGen {

    public enum Status {
        PENDING,     // Job submitted to fal.ai, awaiting processing
        PROCESSING,  // fal.ai is generating the video
        COMPLETED,   // Video is ready and saved
        FAILED       // Generation failed
    }

    public enum GenerationType {
        TEXT_TO_VIDEO,
        IMAGE_TO_VIDEO
    }

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** fal.ai async request ID - used for status polling */
    @Column(name = "fal_request_id", nullable = false, unique = true)
    private String falRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "model", nullable = false)
    private VideoGenModel model;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_type", nullable = false)
    private GenerationType generationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false, length = 2000)
    private String prompt;

    @Column(length = 1000)
    private String negativePrompt;

    /** For image-to-video: path of the uploaded reference image */
    @Column(name = "reference_image_path")
    private String referenceImagePath;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "audio_enabled", nullable = false)
    private Boolean audioEnabled = false;

    @Column(name = "aspect_ratio", nullable = false)
    private String aspectRatio = "16:9"; // "16:9", "9:16", "1:1"

    /** Credits deducted for this generation */
    @Column(name = "credits_used", nullable = false)
    private Integer creditsUsed;

    /** Local path where the downloaded video is saved */
    @Column(name = "video_path")
    private String videoPath;

    /** Direct URL from fal.ai (temporary, usually expires in 24h) */
    @Column(name = "video_url", length = 1000)
    private String videoUrl;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getFalRequestId() { return falRequestId; }
    public void setFalRequestId(String falRequestId) { this.falRequestId = falRequestId; }

    public VideoGenModel getModel() { return model; }
    public void setModel(VideoGenModel model) { this.model = model; }

    public GenerationType getGenerationType() { return generationType; }
    public void setGenerationType(GenerationType generationType) { this.generationType = generationType; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }

    public String getReferenceImagePath() { return referenceImagePath; }
    public void setReferenceImagePath(String referenceImagePath) { this.referenceImagePath = referenceImagePath; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Boolean getAudioEnabled() { return audioEnabled; }
    public void setAudioEnabled(Boolean audioEnabled) { this.audioEnabled = audioEnabled; }

    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }

    public Integer getCreditsUsed() { return creditsUsed; }
    public void setCreditsUsed(Integer creditsUsed) { this.creditsUsed = creditsUsed; }

    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}