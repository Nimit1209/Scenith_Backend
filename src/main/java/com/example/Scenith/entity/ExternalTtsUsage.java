package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.YearMonth;

@Entity
@Table(name = "external_tts_usage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider", "month"}))
public class ExternalTtsUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SoleTTS.TtsProvider provider;

    @Column(name = "month", nullable = false)
    private YearMonth month;

    @Column(name = "characters_used", nullable = false)
    private long charactersUsed = 0L;

    public ExternalTtsUsage() {}

    public ExternalTtsUsage(User user, SoleTTS.TtsProvider provider, YearMonth month) {
        this.user = user;
        this.provider = provider;
        this.month = month;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public SoleTTS.TtsProvider getProvider() { return provider; }
    public void setProvider(SoleTTS.TtsProvider provider) { this.provider = provider; }
    public YearMonth getMonth() { return month; }
    public void setMonth(YearMonth month) { this.month = month; }
    public long getCharactersUsed() { return charactersUsed; }
    public void setCharactersUsed(long charactersUsed) { this.charactersUsed = charactersUsed; }
}