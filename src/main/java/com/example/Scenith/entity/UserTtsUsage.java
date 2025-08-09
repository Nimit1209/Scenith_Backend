package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.YearMonth;

@Entity
@Table(name = "user_tts_usage")
public class UserTtsUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "month", nullable = false)
    private YearMonth month;

    @Column(name = "characters_used", nullable = false)
    private long charactersUsed = 0;

    // Constructors
    public UserTtsUsage() {}

    public UserTtsUsage(User user, YearMonth month) {
        this.user = user;
        this.month = month;
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

    public YearMonth getMonth() {
        return month;
    }

    public void setMonth(YearMonth month) {
        this.month = month;
    }

    public long getCharactersUsed() {
        return charactersUsed;
    }

    public void setCharactersUsed(long charactersUsed) {
        this.charactersUsed = charactersUsed;
    }
}