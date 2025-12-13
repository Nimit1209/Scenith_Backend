package com.example.Scenith.service;

import com.example.Scenith.entity.Payment;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.PaymentRepository;
import com.example.Scenith.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    public PaymentService(PaymentRepository paymentRepository, UserRepository userRepository) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new payment order (called when user clicks "Upgrade")
     */
    public Payment createOrder(User user, String planType, Double amount, String currency) {
        Payment payment = new Payment();
        payment.setUser(user);

        String uuid = UUID.randomUUID().toString().replaceAll("-", ""); // Remove dashes → 32 chars
        String shortOrderId = "ORD_" + uuid.substring(0, 15); // → 18 chars total (safe)
        payment.setOrderId(shortOrderId);

        payment.setPlanType(planType);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(Payment.PaymentStatus.PENDING);

        return paymentRepository.save(payment);
    }

    /**
     * Verify payment and upgrade user (called after payment gateway responds)
     */
    @Transactional
    public Payment verifyAndUpgrade(String orderId, String paymentId, boolean isSuccess) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (payment.getStatus() != Payment.PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment already processed");
        }

        if (isSuccess) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setPaymentId(paymentId);
            payment.setCompletedAt(LocalDateTime.now());

            // Upgrade user role + set expiry (30 days from now)
            User user = payment.getUser();
            User.Role newRole = User.Role.valueOf(payment.getPlanType());
            user.setRole(newRole);
            user.setPlanExpiresAt(LocalDateTime.now().plusDays(30)); // 30-day access
            userRepository.save(user);
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
        }

        return paymentRepository.save(payment);
    }

    /**
     * Get payment by order ID
     */
    public Payment getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    @Scheduled(cron = "0 0 3 * * ?") // Every day at 3:00 AM
    @Transactional
    public void downgradeExpiredUsers() {
        LocalDateTime now = LocalDateTime.now();

        userRepository.findAllExpiredPremiumUsers(now)
                .forEach(user -> {
                    System.out.println("Downgrading expired user: " + user.getEmail() + " (expired on: " + user.getPlanExpiresAt() + ")");
                    user.setRole(User.Role.BASIC);
                    user.setPlanExpiresAt(null); // clean up
                    userRepository.save(user);
                });
    }
}