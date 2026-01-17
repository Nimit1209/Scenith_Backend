package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_processing_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "service_type", "year_month"}))
public class UserProcessingUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "service_type", nullable = false, length = 20)
    private String serviceType;

    @Column(name = "`year_month`", nullable = false, length = 7)  // ✅ Escaped with backticks
    private String yearMonth;

    @Column(name = "process_count", nullable = false)
    private Integer processCount = 0;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = LocalDateTime.now();
    }
}