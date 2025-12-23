package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "user_daily_tts_usage", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "usage_date"}))
public class UserDailyTtsUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "characters_used", nullable = false)
    private Long charactersUsed = 0L;

    // Constructors
    public UserDailyTtsUsage() {}

    public UserDailyTtsUsage(User user, LocalDate usageDate) {
        this.user = user;
        this.usageDate = usageDate;
        this.charactersUsed = 0L;
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

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public Long getCharactersUsed() {
        return charactersUsed;
    }

    public void setCharactersUsed(Long charactersUsed) {
        this.charactersUsed = charactersUsed;
    }
}