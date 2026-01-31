package com.example.Scenith.entity.imageentity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "user_daily_image_gen_usage", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "usage_date"})
})
public class UserDailyImageGenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "usage_date", nullable = false)  // ADD THIS - explicitly specify column name
    private LocalDate usageDate;

    @Column(nullable = false)
    private Long generationsUsed = 0L;

    // Constructors
    public UserDailyImageGenUsage() {}

    public UserDailyImageGenUsage(User user, LocalDate usageDate) {
        this.user = user;
        this.usageDate = usageDate;
        this.generationsUsed = 0L;
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

    public Long getGenerationsUsed() {
        return generationsUsed;
    }

    public void setGenerationsUsed(Long generationsUsed) {
        this.generationsUsed = generationsUsed;
    }
}