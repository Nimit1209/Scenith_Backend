package com.example.Scenith.entity.imageentity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "sole_image_gen")
public class SoleImageGen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 2000)
    private String prompt;

    @Column(length = 2000)
    private String negativePrompt;

    @Column(nullable = false)
    private String imagePath; // Path to generated image

    @Column(nullable = false)
    private String resolution; // e.g., "512x512", "768x768"

    @Column(nullable = false)
    private Integer steps;

    @Column(nullable = false)
    private Double cfgScale;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // Constructors
    public SoleImageGen() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }

    public void setNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Integer getSteps() {
        return steps;
    }

    public void setSteps(Integer steps) {
        this.steps = steps;
    }

    public Double getCfgScale() {
        return cfgScale;
    }

    public void setCfgScale(Double cfgScale) {
        this.cfgScale = cfgScale;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}