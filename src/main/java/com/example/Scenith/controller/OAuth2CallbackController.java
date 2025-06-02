package com.example.Scenith.controller;


import com.example.Scenith.entity.User;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/login/oauth2/code")
public class OAuth2CallbackController {
    private static final Logger logger = LoggerFactory.getLogger(OAuth2CallbackController.class);
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    public OAuth2CallbackController(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @GetMapping("/google")
    public ResponseEntity<String> handleGoogleCallback(@RequestParam("code") String code) {
        try {
            // Exchange authorization code for access token and ID token
            GoogleTokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    "https://oauth2.googleapis.com/token",
                    clientId,
                    clientSecret,
                    code,
                    "https://videoeditor-app.onrender.com/login/oauth2/code/google"
            ).execute();

            String idTokenString = tokenResponse.getIdToken();
            GoogleIdToken idToken = GoogleIdToken.parse(GsonFactory.getDefaultInstance(), idTokenString);
            GoogleIdToken.Payload payload = idToken.getPayload();

            String email = payload.getEmail();
            String name = (String) payload.get("name");
            String picture = (String) payload.get("picture");

            logger.info("Google login callback for email: {}, name: {}, picture: {}", email, name, picture);

            // Find or create user
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setName(name);
                newUser.setProfilePicture(picture);
                newUser.setGoogleAuth(true);
                newUser.setEmailVerified(true);
                newUser.setRole(User.Role.BASIC);
                return userRepository.save(newUser);
            });

            if (!user.isGoogleAuth()) {
                user.setGoogleAuth(true);
                user.setProfilePicture(picture);
                user.setEmailVerified(true);
                userRepository.save(user);
            }

            // Generate JWT token
            String token = jwtUtil.generateToken(user.getEmail());
            // Redirect to success endpoint with token
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", "/auth/success?token=" + token)
                    .build();
        } catch (Exception e) {
            logger.error("Error handling Google OAuth2 callback: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error during Google login: " + e.getMessage());
        }
    }
}