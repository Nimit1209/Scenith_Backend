package com.example.Scenith.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl; // e.g., http://localhost:3000

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String to, String firstName, String token) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setTo(to);
        helper.setSubject("Welcome to Scenith – Verify Your Email to Start Creating! 🎬");

        String verificationUrl = frontendUrl + "/verify-email?token=" + token;
        String htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: 'Montserrat', Arial, sans-serif; background-color: #FAFAFA; margin: 0; padding: 0; }
                    .container { max-width: 600px; margin: 20px auto; background: #FFFFFF; border-radius: 10px; box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1); overflow: hidden; }
                    .header { background: linear-gradient(90deg, #3F8EFC, #B76CFD); padding: 20px; text-align: center; }
                    .header h1 { color: #FFFFFF; font-size: 24px; margin: 0; font-weight: 700; text-transform: uppercase; letter-spacing: 1.5px; }
                    .content { padding: 30px; color: #333333; }
                    .content p { font-size: 16px; line-height: 1.6; margin: 0 0 20px; }
                    .cta-button { display: inline-block; padding: 12px 24px; background: #B76CFD; color: #FFFFFF; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 16px; transition: background 0.3s; }
                    .cta-button:hover { background: #9446EB; }
                    .footer { background: #F4F4F9; padding: 20px; text-align: center; font-size: 14px; color: #666666; }
                    .footer a { color: #3F8EFC; text-decoration: none; }
                    .footer a:hover { color: #9446EB; }
                    @media (max-width: 600px) {
                        .container { margin: 10px; }
                        .header h1 { font-size: 20px; }
                        .content { padding: 20px; }
                        .content p { font-size: 14px; }
                        .cta-button { padding: 10px 20px; font-size: 14px; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Welcome to Scenith!</h1>
                    </div>
                    <div class="content">
                        <p>Dear %s,</p>
                        <p>Welcome to <strong>Scenith</strong>, where your creative vision reaches its zenith! We're thrilled to have you join our community of storytellers, editors, and creators. To get started on your journey to crafting stunning videos, please verify your email address.</p>
                        <p style="text-align: center;">
                            <a href="%s" class="cta-button">Verify My Email</a>
                        </p>
                        <p>This link will take you to a secure page to complete the verification process. It’s quick, easy, and ensures your account is ready to dive into our intuitive timeline, dynamic transitions, and robust editing tools. The link will expire in 24 hours.</p>
                        <p><strong>Why Scenith?</strong><br>At Scenith, we’re more than just a video editor—we’re your creative partner. Built by creators for creators, our platform empowers you to tell compelling stories with precision and flair.</p>
                        <p><strong>What’s Next?</strong><br>Once verified, you’ll gain access to your personalized dashboard, where you can:<br>
                            - Start new projects with presets for YouTube, Instagram Reels, TikTok, and more.<br>
                            - Explore our comprehensive toolkit for audio, video, and keyframe editing.<br>
                            - Join a community passionate about visual storytelling.</p>
                        <p>If you didn’t sign up for Scenith or have any questions, please contact us at <a href="mailto:scenith.spprt@gmail.com">scenith.spprt@gmail.com</a> </p>
                    </div>
                    <div class="footer">
                        <p><strong>Scenith</strong> – Elevating Visual Storytelling<br>
                        <a href="https://www.scenith.com">www.scenith.com</a> | <a href="mailto:sscenith.spprt@gmail.com">scenith.spprt@gmail.com</a></p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(firstName.isEmpty() ? "Creator" : firstName, verificationUrl);

        helper.setText(htmlContent, true);
        mailSender.send(message);
    }
}