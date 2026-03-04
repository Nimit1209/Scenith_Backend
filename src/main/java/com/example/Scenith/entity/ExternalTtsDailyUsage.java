package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "external_tts_daily_usage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider", "usage_date"}))
public class ExternalTtsDailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SoleTTS.TtsProvider provider;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "characters_used", nullable = false)
    private long charactersUsed = 0L;

    public ExternalTtsDailyUsage() {}

    public ExternalTtsDailyUsage(User user, SoleTTS.TtsProvider provider, LocalDate usageDate) {
        this.user = user;
        this.provider = provider;
        this.usageDate = usageDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public SoleTTS.TtsProvider getProvider() { return provider; }
    public void setProvider(SoleTTS.TtsProvider provider) { this.provider = provider; }
    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }
    public long getCharactersUsed() { return charactersUsed; }
    public void setCharactersUsed(long charactersUsed) { this.charactersUsed = charactersUsed; }
}