package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "standalone_image_usage")
public class StandaloneImageUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "usage_month", nullable = false)
    private String usageMonth; // Format: "YYYY-MM"

    @Column(name = "count", nullable = false)
    private int count = 0;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    // Constructors
    public StandaloneImageUsage() {
        this.lastUpdated = LocalDateTime.now();
    }

    public StandaloneImageUsage(User user, String usageMonth) {
        this.user = user;
        this.usageMonth = usageMonth;
        this.count = 0;
        this.lastUpdated = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getUsageMonth() { return usageMonth; }
    public void setUsageMonth(String usageMonth) { this.usageMonth = usageMonth; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public void incrementCount() {
        this.count++;
        this.lastUpdated = LocalDateTime.now();
    }
}