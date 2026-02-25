package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.YearMonth;

/**
 * Tracks a user's video generation credit usage for the current month.
 * One row per user per month — resets automatically each month via
 * the YearMonth key (new row = fresh credits).
 */
@Entity
@Table(name = "user_video_gen_credits",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "credit_month"}))
public class UserVideoGenCredits {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Stored as "YYYY-MM" string (e.g. "2026-02").
     * YearMonth doesn't map directly in all JPA setups, so we use String.
     */
    @Column(name = "credit_month", nullable = false, length = 7)
    private String creditMonth;

    /** Total credits consumed this month */
    @Column(name = "credits_used", nullable = false)
    private int creditsUsed = 0;

    /** Total credits consumed today (denormalized for fast daily-cap checks) */
    @Column(name = "today_credits_used", nullable = false)
    private int todayCreditsUsed = 0;

    @Column(name = "last_reset_date", length = 10)
    private String lastResetDate; // "YYYY-MM-DD" — used to detect day rollover

    public UserVideoGenCredits() {}

    public UserVideoGenCredits(User user, YearMonth month) {
        this.user = user;
        this.creditMonth = month.toString();
        this.creditsUsed = 0;
        this.todayCreditsUsed = 0;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getCreditMonth() { return creditMonth; }
    public void setCreditMonth(String creditMonth) { this.creditMonth = creditMonth; }

    public int getCreditsUsed() { return creditsUsed; }
    public void setCreditsUsed(int creditsUsed) { this.creditsUsed = creditsUsed; }

    public int getTodayCreditsUsed() { return todayCreditsUsed; }
    public void setTodayCreditsUsed(int todayCreditsUsed) { this.todayCreditsUsed = todayCreditsUsed; }

    public String getLastResetDate() { return lastResetDate; }
    public void setLastResetDate(String lastResetDate) { this.lastResetDate = lastResetDate; }
}