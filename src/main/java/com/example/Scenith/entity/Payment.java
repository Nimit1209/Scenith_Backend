package com.example.Scenith.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String orderId; // Your internal order ID

    @Column
    private String paymentId; // Razorpay/PayPal payment ID (null for mock)

    @Column(nullable = false)
    private String planType; // Can be: CREATOR, STUDIO, AI_VOICE_PRO, AI_SUBTITLE_PRO, AI_SPEED_PRO

    // Add helper method to check if it's a bundled or individual plan
    public boolean isBundledPlan() {
        return planType.equals("CREATOR") || planType.equals("STUDIO");
    }

    public boolean isIndividualPlan() {
        return planType.equals("AI_VOICE_PRO") ||
                planType.equals("AI_SUBTITLE_PRO") ||
                planType.equals("AI_SPEED_PRO");
    }

    @Column(nullable = false)
    private Double amount;

    @Column(nullable = false)
    private String currency; // INR or USD

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime completedAt;

    public enum PaymentStatus {
        PENDING,
        SUCCESS,
        FAILED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

}