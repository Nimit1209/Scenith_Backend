package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    public enum Role {
        BASIC,
        CREATOR,
        STUDIO,
        ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column
    private String name;

    @Column(name = "google_auth")
    private boolean googleAuth;

    @Column(name = "first_login", nullable = false)
    private boolean firstLogin = true;

    @Column(name = "is_email_verified", nullable = false)
    private boolean emailVerified = false; // Default to false

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.BASIC; // Default to BASIC

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Add this field to your User.java entity class
    private String profilePicture;

    @Column
    private LocalDateTime planExpiresAt;

    // Add getter and setter
    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }

    // Getters and setters (unchanged)
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isGoogleAuth() {
        return googleAuth;
    }

    public void setGoogleAuth(boolean googleAuth) {
        this.googleAuth = googleAuth;
    }

    public boolean isFirstLogin() {
        return firstLogin;
    }

    public void setFirstLogin(boolean firstLogin) {
        this.firstLogin = firstLogin;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public LocalDateTime getPlanExpiresAt() {
        return planExpiresAt;
    }

    public void setPlanExpiresAt(LocalDateTime planExpiresAt) {
        this.planExpiresAt = planExpiresAt;
    }

    public long getMonthlyTtsLimit() {
        return switch (this.role) {
            case BASIC -> 4500;
            case CREATOR -> 50000;
            case STUDIO -> 150000;
            case ADMIN -> -1;
        };
    }

    public long getDailyTtsLimit() {
        return switch (this.role) {
            case BASIC -> 1000;      // 500 characters/day
            case CREATOR -> 5000;    // 5,000 characters/day
            case STUDIO -> -1;       // -1 means no daily limit
            case ADMIN -> -1;
        };
    }

    public long getMaxCharsPerRequest() {
        return switch (this.role) {
            case BASIC -> 350;
            case CREATOR -> 2500;
            case STUDIO -> 5000;
            case ADMIN -> 10000;
        };
    }

    public int getMaxVideoProcessingPerMonth() {
        return switch (this.role) {
            case BASIC -> 5;
            case CREATOR -> 10;
            case STUDIO, ADMIN -> -1; // unlimited
        };
    }

    public int getMaxVideoLengthMinutes() {
        return switch (this.role) {
            case BASIC -> 5;
            case CREATOR -> 15;
            case STUDIO, ADMIN -> -1; // unlimited
        };
    }

    public String getMaxAllowedQuality() {
        return switch (this.role) {
            case BASIC -> "720p";
            case CREATOR -> "1440p";
            case STUDIO, ADMIN -> "4k";
        };
    }

    public boolean isQualityAllowed(String quality) {
        int requestedQuality = parseQuality(quality);
        int maxQuality = parseQuality(getMaxAllowedQuality());
        return requestedQuality <= maxQuality;
    }

    private int parseQuality(String quality) {
        return switch (quality.toLowerCase()) {
            case "144p" -> 144;
            case "240p" -> 240;
            case "360p" -> 360;
            case "480p" -> 480;
            case "720p" -> 720;
            case "1080p" -> 1080;
            case "1440p" -> 1440;
            case "2k" -> 1440;
            case "4k" -> 2160;
            default -> 0;
        };
    }
    public boolean isAdmin() {
        return this.role == Role.ADMIN;
    }
}