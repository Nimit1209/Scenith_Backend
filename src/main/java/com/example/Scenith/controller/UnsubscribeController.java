package com.example.Scenith.controller;

import com.example.Scenith.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/unsubscribe")
public class UnsubscribeController {

    private final UserRepository userRepository;

    public UnsubscribeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional
    public ResponseEntity<String> unsubscribe(@RequestParam String email) {

        userRepository.unsubscribeUser(email);

        return ResponseEntity.ok("""
            <html>
            <body style="font-family:sans-serif;text-align:center;padding:40px;">
            <h2>✅ You have been unsubscribed.</h2>
            <p>You will no longer receive marketing emails from Scenith.</p>
            </body>
            </html>
        """);
    }
}