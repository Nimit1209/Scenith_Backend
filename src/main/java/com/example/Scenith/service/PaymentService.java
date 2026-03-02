package com.example.Scenith.service;

import com.example.Scenith.entity.Payment;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserPlan;
import com.example.Scenith.enums.PlanType;
import com.example.Scenith.repository.PaymentRepository;
import com.example.Scenith.repository.UserPlanRepository;
import com.example.Scenith.repository.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository; // ADD THIS

    public PaymentService(
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            UserPlanRepository userPlanRepository) { // ADD THIS
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.userPlanRepository = userPlanRepository; // ADD THIS
    }

    /**
     * Create a new payment order (called when user clicks "Upgrade")
     */
    public Payment createOrder(User user, String planType, Double amount, String currency) {
        // Validate plan type
        if (!isValidPlanType(planType)) {
            throw new IllegalArgumentException("Invalid plan type: " + planType);
        }

        Payment payment = new Payment();
        payment.setUser(user);
        String uuid = UUID.randomUUID().toString().replaceAll("-", "");
        String shortOrderId = "ORD_" + uuid.substring(0, 15);
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

            User user = payment.getUser();
            String planType = payment.getPlanType();
            PlanType plan = PlanType.valueOf(planType);

            // Deactivate existing plan of same type if present
            userPlanRepository.findActiveUserPlan(user, plan, LocalDateTime.now())
                    .ifPresent(existingPlan -> {
                        existingPlan.setActive(false);
                        userPlanRepository.save(existingPlan);
                    });

            // All plans (bundled + video gen) go into user_plans table
            UserPlan userPlan = new UserPlan();
            userPlan.setUser(user);
            userPlan.setPlanType(plan);
            userPlan.setStartDate(LocalDateTime.now());
            userPlan.setExpiryDate(LocalDateTime.now().plusDays(30));
            userPlan.setActive(true);
            userPlanRepository.save(userPlan);
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

    /**
     * Scheduled job to downgrade expired bundled plans
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void downgradeExpiredUsers() {
        LocalDateTime now = LocalDateTime.now();

        List<UserPlan> expiredPlans = userPlanRepository.findByActiveTrueAndExpiryDateBefore(now);
        expiredPlans.forEach(plan -> {
            System.out.println("Deactivating expired plan: " + plan.getPlanType() +
                    " for user: " + plan.getUser().getEmail() +
                    " (expired on: " + plan.getExpiryDate() + ")");
            plan.setActive(false);
            userPlanRepository.save(plan);
        });
    }

    // Helper methods
    private boolean isValidPlanType(String planType) {
        try {
            PlanType.valueOf(planType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isBundledPlan(String planType) {
        return "CREATOR".equals(planType) || "STUDIO".equals(planType);
    }

    private boolean isIndividualPlan(String planType) {
        return "VIDEO_GEN_PRO".equals(planType) ||
                "VIDEO_GEN_ELITE".equals(planType);
    }
}