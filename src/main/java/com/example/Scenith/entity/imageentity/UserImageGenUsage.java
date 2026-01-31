package com.example.Scenith.entity.imageentity;

import com.example.Scenith.entity.User;
import jakarta.persistence.*;
import java.time.YearMonth;

@Entity
@Table(name = "user_image_gen_usage", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "year", "month"})
})
public class UserImageGenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @Column(nullable = false)
    private Long generationsUsed = 0L;

    // Constructors
    public UserImageGenUsage() {}

    public UserImageGenUsage(User user, YearMonth yearMonth) {
        this.user = user;
        this.year = yearMonth.getYear();
        this.month = yearMonth.getMonthValue();
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

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public Long getGenerationsUsed() {
        return generationsUsed;
    }

    public void setGenerationsUsed(Long generationsUsed) {
        this.generationsUsed = generationsUsed;
    }
}