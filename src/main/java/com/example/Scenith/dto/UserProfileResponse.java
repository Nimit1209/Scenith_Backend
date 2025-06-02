package com.example.Scenith.dto;

public class UserProfileResponse {
    private String email;
    private String name;
    private String picture;
    private boolean googleAuth;
    private String role; // Changed to String to match enum name

    public UserProfileResponse(String email, String name, String picture, boolean googleAuth, String role) {
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.googleAuth = googleAuth;
        this.role = role;
    }

    // Getters and setters
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }

    public boolean isGoogleAuth() {
        return googleAuth;
    }

    public void setGoogleAuth(boolean googleAuth) {
        this.googleAuth = googleAuth;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}