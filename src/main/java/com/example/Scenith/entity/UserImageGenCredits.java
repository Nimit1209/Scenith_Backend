package com.example.Scenith.entity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import java.time.YearMonth;

@Entity
@Table(name = "user_image_gen_credits",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "credit_month"}))
public class UserImageGenCredits {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Stored as "YYYY-MM" e.g. "2026-03" */
    @Column(name = "credit_month", nullable = false, length = 7)
    private String creditMonth;

    @Column(name = "credits_used", nullable = false)
    private int creditsUsed = 0;

    @Column(name = "today_credits_used", nullable = false)
    private int todayCreditsUsed = 0;

    /** "YYYY-MM-DD" — used to detect day rollover */
    @Column(name = "last_reset_date", length = 10)
    private String lastResetDate;

    public UserImageGenCredits() {}

    public UserImageGenCredits(User user, YearMonth month) {
        this.user        = user;
        this.creditMonth = month.toString();
    }

    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }

    public User getUser()                        { return user; }
    public void setUser(User user)               { this.user = user; }

    public String getCreditMonth()               { return creditMonth; }
    public void setCreditMonth(String m)         { this.creditMonth = m; }

    public int getCreditsUsed()                  { return creditsUsed; }
    public void setCreditsUsed(int v)            { this.creditsUsed = v; }

    public int getTodayCreditsUsed()             { return todayCreditsUsed; }
    public void setTodayCreditsUsed(int v)       { this.todayCreditsUsed = v; }

    public String getLastResetDate()             { return lastResetDate; }
    public void setLastResetDate(String d)       { this.lastResetDate = d; }
}