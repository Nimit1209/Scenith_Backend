package com.example.Scenith.developer.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "global_elements")
public class GlobalElement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Required for JPA entity

    @Column(name = "globalElement_json", columnDefinition = "TEXT")
    private String globalElementJson; // Stores {"filePath": "elements/fileName", "fileName": "fileName"}

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGlobalElementJson() {
        return globalElementJson;
    }

    public void setGlobalElementJson(String globalElementJson) {
        this.globalElementJson = globalElementJson;
    }
}