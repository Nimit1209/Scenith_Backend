package com.example.Scenith.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_upgrade_emails")
public class ScheduledUpgradeEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "scheduled_time", nullable = false)
    private LocalDateTime scheduledTime;

    @Column(name = "sent", nullable = false)
    private boolean sent = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "trigger_action", nullable = false)
    private String triggerAction;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public ScheduledUpgradeEmail() {}

    public ScheduledUpgradeEmail(User user, LocalDateTime scheduledTime, String triggerAction) {
        this.user = user;
        this.scheduledTime = scheduledTime;
        this.triggerAction = triggerAction;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public LocalDateTime getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(LocalDateTime scheduledTime) { this.scheduledTime = scheduledTime; }
    public boolean isSent() { return sent; }
    public void setSent(boolean sent) { this.sent = sent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public String getTriggerAction() { return triggerAction; }
    public void setTriggerAction(String triggerAction) { this.triggerAction = triggerAction; }
}