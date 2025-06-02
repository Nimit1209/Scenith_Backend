package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "none", value = ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime lastModified;

    @Column(columnDefinition = "TEXT")
    private String timelineState;

    private Integer width;
    private Integer height;

    @Column(nullable = false, columnDefinition = "FLOAT DEFAULT 25.0")
    private Float fps = 25.0f;

    private String exportedVideoPath;

    @Column(columnDefinition = "TEXT")
    private String videosJson;

    @Column(columnDefinition = "TEXT")
    private String imagesJson;

    @Column(columnDefinition = "TEXT")
    private String audioJson;

    @Column(columnDefinition = "TEXT")
    private String extractedAudioJson;

    @Column(name = "element_json", columnDefinition = "TEXT")
    private String elementJson;

    @Column(columnDefinition = "TEXT") // New field for multiple exports
    private String exportsJson;

    @Column(name = "edit_session") // New column for session ID
    private String editSession;

    public String getEditSession() {
        return editSession;
    }

    public void setEditSession(String editSession) {
        this.editSession = editSession;
    }

    public String getExportsJson() {
        return exportsJson;
    }

    public void setExportsJson(String exportsJson) {
        this.exportsJson = exportsJson;
    }


    public String getExportedVideoPath() {
        return exportedVideoPath;
    }

    public void setExportedVideoPath(String exportedVideoPath) {
        this.exportedVideoPath = exportedVideoPath;
    }

    public String getElementJson() {
        return elementJson;
    }

    public void setElementJson(String elementJson) {
        this.elementJson = elementJson;
    }

    public String getExtractedAudioJson() {
        return extractedAudioJson;
    }

    public void setExtractedAudioJson(String extractedAudioJson) {
        this.extractedAudioJson = extractedAudioJson;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public String getTimelineState() {
        return timelineState;
    }

    public void setTimelineState(String timelineState) {
        this.timelineState = timelineState;
    }

    public String getVideosJson() {
        return videosJson;
    }

    public void setVideosJson(String videosJson) {
        this.videosJson = videosJson;
    }

    public String getImagesJson() {
        return imagesJson;
    }

    public void setImagesJson(String imagesJson) {
        this.imagesJson = imagesJson;
    }

    public String getAudioJson() {
        return audioJson;
    }

    public void setAudioJson(String audioJson) {
        this.audioJson = audioJson;
    }

    public Float getFps() {
        return fps;
    }

    public void setFps(Float fps) {
        this.fps = fps;
    }
}