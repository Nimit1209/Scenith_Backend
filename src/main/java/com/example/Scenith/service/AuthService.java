package com.example.Scenith.service;


import com.example.Scenith.developer.entity.Developer;
import com.example.Scenith.developer.repository.DeveloperRepository;
import com.example.Scenith.dto.AuthRequest;
import com.example.Scenith.dto.AuthResponse;
import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VerificationToken;
import com.example.Scenith.repository.UserRepository;
import com.example.Scenith.repository.VerificationTokenRepository;
import com.example.Scenith.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!test") // Only active in non-test profiles
public class AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final DeveloperRepository developerRepository;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public AuthService(UserRepository userRepository, VerificationTokenRepository verificationTokenRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService,
                       DeveloperRepository developerRepository) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.developerRepository = developerRepository;
    }

    @Transactional
    public AuthResponse register(AuthRequest request) throws MessagingException, IOException {
        if (!isValidEmail(request.getEmail())) {
            throw new RuntimeException("Please enter a valid email address. The email does not exist or cannot receive mail.");
        }

        if (!isEmailDomainValid(request.getEmail())) {
            throw new RuntimeException("The email domain is not valid");
        }

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new RuntimeException("Name is required");
        }

        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.isGoogleAuth()) {
                throw new RuntimeException("Email is already associated with a Google account. Please log in with Google.");
            }
            throw new RuntimeException("This email is already registered");
        }

        if (!isPasswordValid(request.getPassword())) {
            throw new RuntimeException("Password must be at least 8 characters with at least one letter and one number");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setName(request.getName().trim());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setGoogleAuth(false);
        user.setEmailVerified(false);
        user.setRole(User.Role.BASIC);
        userRepository.save(user);

        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        logger.info("Saving verification token for user: {}", user.getEmail());
        verificationTokenRepository.save(verificationToken);

        try {
            String firstName = request.getName().split(" ")[0];
            emailService.sendVerificationEmail(user.getEmail(), firstName, token);
            logger.info("Verification email sent to: {}", user.getEmail());
        } catch (MessagingException e) {
            userRepository.delete(user);
            logger.error("Failed to send verification email to: {}", user.getEmail(), e);
            throw new RuntimeException("Failed to send verification email. Please check your email address.");
        }

        return new AuthResponse(
                null,
                user.getEmail(),
                user.getName(),
                "Verification email sent. Please check your inbox to complete registration.",
                false
        );
    }

    @Transactional
    public AuthResponse verifyEmail(String token) {
        logger.info("Verifying token: {}", token);
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> {
                    logger.warn("Token not found: {}", token);
                    return new RuntimeException("Invalid or unknown verification token");
                });

        if (verificationToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(verificationToken);
            logger.warn("Token expired: {}", token);
            throw new RuntimeException("Verification token has expired. Please request a new verification email.");
        }

        User user = verificationToken.getUser();
        logger.info("User email: {}, verified: {}", user.getEmail(), user.isEmailVerified());

        if (user.isEmailVerified() || verificationToken.isVerified()) {
            String jwtToken = jwtUtil.generateToken(user.getEmail());
            logger.info("Email already verified for user: {}, generated JWT token", user.getEmail());
            return new AuthResponse(
                    jwtToken,
                    user.getEmail(),
                    user.getName(),
                    "Email already verified",
                    true
            );
        }

        user.setEmailVerified(true);
        verificationToken.setVerified(true);
        userRepository.save(user);
        verificationTokenRepository.save(verificationToken);
        logger.info("Email verified successfully for user: {}", user.getEmail());

        String jwtToken = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                jwtToken,
                user.getEmail(),
                user.getName(),
                "Email verified successfully",
                true
        );
    }

    @Transactional
    public void resendVerificationEmail(String email) throws MessagingException, IOException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Email already verified");
        }

        logger.info("Deleting existing verification tokens for user: {}", email);
        verificationTokenRepository.deleteByUser(user);
        verificationTokenRepository.flush();

        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        logger.info("Saving new verification token for user: {}", email);
        verificationTokenRepository.saveAndFlush(verificationToken);

        emailService.sendVerificationEmail(
                user.getEmail(),
                user.getName() != null ? user.getName().split(" ")[0] : "",
                token
        );
        logger.info("Verification email resent to: {}", email);
    }

    private boolean isEmailDomainValid(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1);
            return domain.matches("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$");
        } catch (Exception e) {
            logger.warn("Invalid email domain: {}", email, e);
            return false;
        }
    }

    private boolean isPasswordValid(String password) {
        return password != null && password.length() >= 8 &&
                password.matches(".*[a-zA-Z].*") &&
                password.matches(".*[0-9].*");
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (user.isGoogleAuth()) {
            throw new RuntimeException("This account is linked to Google. Please log in with Google.");
        }

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Email not verified. Please check your inbox for the verification email.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Login successful",
                true
        );
    }

    public AuthResponse googleLogin(String idTokenString) throws Exception {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) {
            logger.warn("Invalid Google ID token");
            throw new RuntimeException("Invalid Google ID token");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");

        logger.info("Google login for email: {}, name: {}, picture: {}", email, name, picture);

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (name != null && (user.getName() == null || user.getName().isEmpty())) {
                user.setName(name);
            }
            if (picture != null) {
                user.setProfilePicture(picture);
            }
            user.setGoogleAuth(true);
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            }
            user.setEmailVerified(true);
        } else {
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setProfilePicture(picture);
            user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            user.setGoogleAuth(true);
            user.setEmailVerified(true);
            user.setRole(User.Role.BASIC);
        }
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Google login successful",
                true
        );
    }

    private boolean isValidEmail(String email) {
        String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
        if (email == null || !email.matches(emailRegex)) {
            logger.warn("Email format invalid: {}", email);
            return false;
        }
        logger.info("Skipping SMTP check for email: {}", email);
        return true;
    }

    private boolean verifyEmailExists(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1);
            Lookup lookup = new Lookup(domain, Type.MX);
            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                logger.warn("No MX records found for domain: {}", domain);
                return false;
            }

            MXRecord mxRecord = null;
            for (Record record : records) {
                if (record instanceof MXRecord) {
                    mxRecord = (MXRecord) record;
                    break;
                }
            }
            if (mxRecord == null) {
                logger.warn("No MXRecord found for domain: {}", domain);
                return false;
            }

            String mxHost = mxRecord.getTarget().toString();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(mxHost, 25), 5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                String greeting = reader.readLine();
                if (greeting == null || !greeting.startsWith("220")) {
                    logger.warn("Invalid SMTP greeting: {}", greeting);
                    return false;
                }

                writer.write("HELO example.com\r\n");
                writer.flush();
                String heloResponse = reader.readLine();
                if (heloResponse == null || !heloResponse.startsWith("250")) {
                    logger.warn("Invalid HELO response: {}", heloResponse);
                    return false;
                }

                writer.write("MAIL FROM:<verify@example.com>\r\n");
                writer.flush();
                String mailFromResponse = reader.readLine();
                if (mailFromResponse == null || !mailFromResponse.startsWith("250")) {
                    logger.warn("Invalid MAIL FROM response: {}", mailFromResponse);
                    return false;
                }

                writer.write("RCPT TO:<" + email + ">\r\n");
                writer.flush();
                String rcptToResponse = reader.readLine();
                if (rcptToResponse == null) {
                    logger.warn("No response for RCPT TO");
                    return false;
                }

                boolean isValid = rcptToResponse.startsWith("250");
                if (!isValid) {
                    logger.warn("RCPT TO response indicates email does not exist: {}", rcptToResponse);
                }
                return isValid;
            }
        } catch (TextParseException e) {
            logger.warn("Failed to parse domain for MX lookup: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            logger.warn("Email verification failed for: {}", email, e);
            return false;
        }
    }

    @Transactional
    public AuthResponse developerLogin(AuthRequest request) {
        Developer developer = developerRepository.findByUsername(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid developer credentials"));

        if (!developer.isActive()) {
            throw new RuntimeException("Developer account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), developer.getPassword())) {
            throw new RuntimeException("Invalid developer credentials");
        }

        String token = jwtUtil.generateToken(developer.getUsername(), "DEVELOPER");
        return new AuthResponse(
                token,
                developer.getUsername(),
                null,
                "Developer login successful",
                true
        );
    }

    @Transactional
    public Developer createDeveloper(String username, String password) {
        if (developerRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Developer username already exists");
        }

        if (!isPasswordValid(password)) {
            throw new RuntimeException("Password must be at least 8 characters with at least one letter and one number");
        }

        Developer developer = new Developer();
        developer.setUsername(username);
        developer.setPassword(passwordEncoder.encode(password));
        developer.setActive(true);
        return developerRepository.save(developer);
    }
}