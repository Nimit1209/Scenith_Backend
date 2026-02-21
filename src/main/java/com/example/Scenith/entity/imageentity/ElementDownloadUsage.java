package com.example.Scenith.entity.imageentity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.YearMonth;

@Entity
@Table(name = "element_download_usage",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "usage_date"}))
@Data
public class ElementDownloadUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "daily_count", nullable = false)
    private int dailyCount = 0;

    @Column(name = "year_month", nullable = false)
    private String yearMonth; // Format: "2025-06"

    @Column(name = "monthly_count", nullable = false)
    private int monthlyCount = 0;

    public ElementDownloadUsage() {}

    public ElementDownloadUsage(Long userId) {
        this.userId = userId;
        this.usageDate = LocalDate.now();
        this.yearMonth = YearMonth.now().toString();
    }
}