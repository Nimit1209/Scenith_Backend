package com.example.Scenith.controller;

import com.example.Scenith.entity.Payment;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.PaymentService;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.*;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final RazorpayClient razorpayClient;
    private final PayPalHttpClient payPalHttpClient;

    @Value("${razorpay.key.id}")
    private String razorpayKeyId;

    @Value("${razorpay.key.secret}")
    private String razorpayKeySecret;

    public PaymentController(PaymentService paymentService, JwtUtil jwtUtil, UserRepository userRepository, RazorpayClient razorpayClient, PayPalHttpClient payPalHttpClient) {
        this.paymentService = paymentService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.razorpayClient = razorpayClient;
        this.payPalHttpClient = payPalHttpClient;
    }

    /**
     * MOCK PAYMENT API - Simulate payment success/failure
     * In production, replace this with Razorpay/PayPal webhook
     */
    @PostMapping("/mock-payment")
    public ResponseEntity<?> mockPayment(@RequestBody Map<String, Object> request) {
        try {
            String orderId = (String) request.get("orderId");
            Boolean success = (Boolean) request.getOrDefault("success", true);

            // Simulate payment ID from gateway
            String paymentId = success ? "MOCK_PAY_" + System.currentTimeMillis() : null;

            Payment payment = paymentService.verifyAndUpgrade(orderId, paymentId, success);

            Map<String, Object> response = new HashMap<>();
            response.put("status", payment.getStatus().toString());
            response.put("orderId", payment.getOrderId());
            response.put("paymentId", payment.getPaymentId());
            response.put("planType", payment.getPlanType());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Payment verification failed: " + e.getMessage());
        }
    }

    /**
     * Verify payment status
     */
    @GetMapping("/status/{orderId}")
    public ResponseEntity<?> getPaymentStatus(
            @RequestHeader("Authorization") String token,
            @PathVariable String orderId) {
        try {
            User user = getUserFromToken(token);
            Payment payment = paymentService.getPaymentByOrderId(orderId);

            if (!payment.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(403).body("Unauthorized");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("orderId", payment.getOrderId());
            response.put("status", payment.getStatus().toString());
            response.put("planType", payment.getPlanType());
            response.put("amount", payment.getAmount());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching status: " + e.getMessage());
        }
    }
    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String planType = (String) request.get("planType");
            Double amount = ((Number) request.get("amount")).doubleValue();
            String currency = (String) request.get("currency");

            // ✅ UPDATED: Support individual plans too
            if (!isValidPlanType(planType)) {
                return ResponseEntity.badRequest().body("Invalid plan type. Must be one of: CREATOR, STUDIO, AI_VOICE_PRO, AI_SUBTITLE_PRO, AI_SPEED_PRO");
            }

            // Create internal payment
            Payment payment = paymentService.createOrder(user, planType, amount, currency);

            Map<String, Object> response = new HashMap<>();
            response.put("internalOrderId", payment.getOrderId());
            response.put("amount", amount);
            response.put("currency", currency);
            response.put("planType", planType);

            String gatewayOrderId = null;
            String gateway = null;

            if ("INR".equalsIgnoreCase(currency)) {
                // Razorpay
                JSONObject orderRequest = new JSONObject();
                orderRequest.put("amount", (int)(amount * 100)); // paise
                orderRequest.put("currency", "INR");
                orderRequest.put("receipt", payment.getOrderId());

                Order razorpayOrder = razorpayClient.orders.create(orderRequest);
                gatewayOrderId = razorpayOrder.get("id");
                gateway = "razorpay";
                response.put("keyId", razorpayKeyId);
            } else if ("USD".equalsIgnoreCase(currency)) {
                // PayPal
                com.paypal.orders.Order paypalOrder = createPayPalOrder(amount);
                gatewayOrderId = paypalOrder.id();
                gateway = "paypal";
            }

            response.put("gatewayOrderId", gatewayOrderId);
            response.put("gateway", gateway);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    // ADD THIS helper method
    private boolean isValidPlanType(String planType) {
        return "CREATOR".equals(planType) ||
                "STUDIO".equals(planType) ||
                "AI_VOICE_PRO".equals(planType) ||
                "AI_SUBTITLE_PRO".equals(planType) ||
                "BG_REMOVAL_PRO".equals(planType) ||
                "SVG_PRO".equals(planType) ||
                "AI_SPEED_PRO".equals(planType);
    }

    // Helper for PayPal order creation
    private com.paypal.orders.Order createPayPalOrder(Double amount) throws IOException {
        OrdersCreateRequest request = new OrdersCreateRequest();
        request.prefer("return=representation");

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.checkoutPaymentIntent("CAPTURE");

        // Application Context (set on OrderRequest)
        ApplicationContext appContext = new ApplicationContext()
                .brandName("Scenith")
                .landingPage("BILLING")
                .userAction("PAY_NOW")
                .returnUrl("https://scenith.in/success")
                .cancelUrl("https://scenith.in/cancel");
        orderRequest.applicationContext(appContext);

        // Purchase Unit
        PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest()
                .amountWithBreakdown(new AmountWithBreakdown()
                        .currencyCode("USD")
                        .value(String.format("%.2f", amount)));

        orderRequest.purchaseUnits(List.of(purchaseUnit));

        request.requestBody(orderRequest);

        HttpResponse<com.paypal.orders.Order> httpResponse = payPalHttpClient.execute(request);
        return httpResponse.result();
    }

    @PostMapping("/verify-razorpay")
    public ResponseEntity<?> verifyRazorpay(@RequestBody Map<String, String> body) {
        try {
            String internalOrderId = body.get("internalOrderId");
            String razorpayPaymentId = body.get("razorpay_payment_id");
            String razorpayOrderId = body.get("razorpay_order_id");
            String razorpaySignature = body.get("razorpay_signature");

            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", razorpayOrderId);
            options.put("razorpay_payment_id", razorpayPaymentId);
            options.put("razorpay_signature", razorpaySignature);

            boolean isValid = Utils.verifyPaymentSignature(options, razorpayKeySecret);

            Payment updated = paymentService.verifyAndUpgrade(internalOrderId, razorpayPaymentId, isValid);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", updated.getStatus().toString());
            return ResponseEntity.ok(resp);
        } catch (RazorpayException e) {
            return ResponseEntity.badRequest().body("Verification failed: " + e.getMessage());
        }
    }

    @PostMapping("/capture-paypal")
    public ResponseEntity<?> capturePayPal(@RequestBody Map<String, String> body) {
        try {
            String internalOrderId = body.get("internalOrderId");
            String paypalOrderId = body.get("paypalOrderId");

            OrdersCaptureRequest request = new OrdersCaptureRequest(paypalOrderId);
            HttpResponse<com.paypal.orders.Order> response = payPalHttpClient.execute(request);

            com.paypal.orders.Order order = response.result();
            boolean isCaptured = "COMPLETED".equals(order.status());

            String paymentId = isCaptured && !order.purchaseUnits().isEmpty()
                    && order.purchaseUnits().get(0).payments() != null
                    && !order.purchaseUnits().get(0).payments().captures().isEmpty()
                    ? order.purchaseUnits().get(0).payments().captures().get(0).id() : null;

            Payment updated = paymentService.verifyAndUpgrade(internalOrderId, paymentId, isCaptured);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", updated.getStatus().toString());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Capture failed: " + e.getMessage());
        }
    }


    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}