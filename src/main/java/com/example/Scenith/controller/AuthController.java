package com.example.Scenith.controller;


import com.example.Scenith.dto.AuthRequest;
import com.example.Scenith.dto.AuthResponse;
import com.example.Scenith.dto.UserProfileResponse;
import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.example.Scenith.service.AuthService;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    public AuthController(AuthService authService, JwtUtil jwtUtil, UserRepository userRepository) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @GetMapping("/google")
    public ResponseEntity<String> initiateGoogleLogin() {
        String googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + clientId +
                "&redirect_uri=https://videoeditor-app.onrender.com/login/oauth2/code/google" +
                "&response_type=code" +
                "&scope=email%20profile" +
                "&access_type=offline";
        logger.info("Initiating Google OAuth2 flow: {}", googleAuthUrl);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", googleAuthUrl)
                .build();
    }

    @GetMapping("/success")
    public ResponseEntity<AuthResponse> handleOAuth2Success(@RequestParam String token) {
        String email = jwtUtil.extractEmail(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Google login successful",
                true
        ));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestParam String token) {
        try {
            AuthResponse response = authService.verifyEmail(token);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Email verification error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new AuthResponse(
                    null,
                    null,
                    null,
                    e.getMessage(),
                    false
            ));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getUserProfile(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            String userEmail = jwtUtil.extractEmail(token);
            logger.info("Fetching profile for email: {}", userEmail);

            User user = userRepository.findByEmail(userEmail)
                    .orElseThrow(() -> {
                        logger.error("User not found for email: {}", userEmail);
                        return new RuntimeException("User not found");
                    });

            logger.info("User found: email={}, name={}, profilePicture={}, googleAuth={}",
                    user.getEmail(), user.getName(), user.getProfilePicture(), user.isGoogleAuth());

            UserProfileResponse profileResponse = new UserProfileResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getName() != null ? user.getName() : "",
                    user.getProfilePicture() != null ? user.getProfilePicture() : "",
                    user.isGoogleAuth(),
                    user.getRole().name()
            );

            return ResponseEntity.ok(profileResponse);
        } catch (Exception e) {
            logger.error("Error fetching user profile: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new UserProfileResponse(null, null, null, null, false, null));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Registration error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new AuthResponse(null, request.getEmail(), null, e.getMessage(), false));
        } catch (MessagingException e) {
            logger.error("Email sending error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(null, request.getEmail(), null,
                            "Failed to send verification email", false));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<String> resendVerification(@RequestParam String email) {
        try {
            authService.resendVerificationEmail(email);
            return ResponseEntity.ok("Verification email resent successfully");
        } catch (RuntimeException e) {
            logger.error("Resend verification error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (MessagingException e) {
            logger.error("Email sending error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to send verification email. Please try again.");
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An error occurred while resending the verification email. Please try again.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(@RequestBody GoogleAuthRequest request) throws Exception {
        return ResponseEntity.ok(authService.googleLogin(request.getToken()));
    }

    @PostMapping("/developer-login")
    public ResponseEntity<AuthResponse> developerLogin(@RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.developerLogin(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            logger.error("Developer login error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(null, request.getEmail(), null, e.getMessage(), false));
        }
    }
}

class GoogleAuthRequest {
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}