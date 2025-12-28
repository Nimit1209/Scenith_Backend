package com.example.Scenith.service;

import com.example.Scenith.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Helper class for sending processing completion emails
 * Centralizes email configuration for different service types
 */
@Component
public class ProcessingEmailHelper {
    private static final Logger logger = LoggerFactory.getLogger(ProcessingEmailHelper.class);
    private final EmailService emailService;

    public ProcessingEmailHelper(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Send processing completion email for any service
     * 
     * @param user User who processed the media
     * @param serviceType Type of service (SUBTITLE, COMPRESSION, CONVERSION, TTS)
     * @param originalFileName Original file name
     * @param downloadUrl CDN URL for download
     * @param entityId ID of the processed entity (for logging)
     */
    public void sendProcessingCompleteEmail(
            User user,
            ServiceType serviceType, 
            String originalFileName, 
            String downloadUrl, 
            Long entityId) {
        
        try {
            Map<String, String> variables = buildEmailVariables(
                user, serviceType, originalFileName, downloadUrl
            );

            emailService.sendTemplateEmail(
                    user.getEmail(),
                    "ai-voice-generation-campaign",
                    "video-processing-complete",
                    variables
            );

            logger.info("Processing-complete email sent to {} for {} (entityId: {})", 
                    user.getEmail(), serviceType, entityId);
                    
        } catch (Exception e) {
            logger.error("Failed to send processing-complete email for {} (entityId: {})", 
                    serviceType, entityId, e);
            // Email failure does NOT affect processing
        }
    }

    /**
     * Build email variables based on service type
     */
    private Map<String, String> buildEmailVariables(
            User user, 
            ServiceType serviceType, 
            String originalFileName, 
            String downloadUrl) {
        
        Map<String, String> vars = new HashMap<>();
        vars.put("userName", Optional.ofNullable(user.getName()).orElse("Creator"));
        vars.put("originalFileName", originalFileName);
        vars.put("downloadUrl", downloadUrl);
        
        // Service-specific customization
        switch (serviceType) {
            case SUBTITLE:
                vars.put("serviceName", "Subtitled Video");
                vars.put("serviceEmoji", "Video");
                vars.put("serviceAction", "subtitle processing");
                vars.put("fileType", "video");
                vars.put("serviceType", "subtitle");
                vars.put("featuresList", 
                    "<li><strong>Professional Subtitles:</strong> Accurately synced with your video</li>" +
                    "<li><strong>High-Quality Output:</strong> Original video quality preserved</li>" +
                    "<li><strong>Ready to Share:</strong> Perfect for social media, YouTube & more</li>");
                vars.put("additionalTips", 
                    "<div style='background: #d1fae5; border: 1px solid #10b981; border-radius: 8px; padding: 15px; margin: 25px 0;'>" +
                    "<p style='margin: 0 0 10px 0; color: #065f46; font-size: 14px; font-weight: 600;'>💡 Pro Tips for Maximum Engagement:</p>" +
                    "<ul style='margin: 0; padding-left: 20px; color: #065f46; font-size: 13px; line-height: 1.6;'>" +
                    "<li>80% of social media videos are watched without sound – subtitles boost engagement!</li>" +
                    "<li>Share on Instagram Reels, TikTok, YouTube Shorts for wider reach</li>" +
                    "<li>Subtitled videos get 40% more views on average</li>" +
                    "</ul></div>");
                break;
                
            case COMPRESSION:
                vars.put("serviceName", "Compressed Video");
                vars.put("serviceEmoji", "Video");
                vars.put("serviceAction", "video compression");
                vars.put("fileType", "video");
                vars.put("serviceType", "compression");
                vars.put("featuresList", 
                    "<li><strong>Optimized Size:</strong> Reduced file size to your target specifications</li>" +
                    "<li><strong>Quality Maintained:</strong> Smart compression preserves visual quality</li>" +
                    "<li><strong>Fast Upload:</strong> Perfect for sharing on messaging apps & social media</li>" +
                    "<li><strong>Storage Friendly:</strong> Save space without compromising quality</li>");
                vars.put("additionalTips", 
                    "<div style='background: #dbeafe; border: 1px solid #3b82f6; border-radius: 8px; padding: 15px; margin: 25px 0;'>" +
                    "<p style='margin: 0 0 10px 0; color: #1e40af; font-size: 14px; font-weight: 600;'>💡 Compression Benefits:</p>" +
                    "<ul style='margin: 0; padding-left: 20px; color: #1e40af; font-size: 13px; line-height: 1.6;'>" +
                    "<li>Faster uploads and downloads for your viewers</li>" +
                    "<li>Reduced bandwidth costs for hosting</li>" +
                    "<li>Compatible with all major platforms and devices</li>" +
                    "</ul></div>");
                break;
                
            case CONVERSION:
                vars.put("serviceName", "Converted Media");
                vars.put("serviceEmoji", "File");
                vars.put("serviceAction", "format conversion");
                vars.put("fileType", "file");
                vars.put("serviceType", "conversion");
                vars.put("featuresList", 
                    "<li><strong>Format Converted:</strong> Your media is now in the desired format</li>" +
                    "<li><strong>Quality Preserved:</strong> Conversion maintains original quality</li>" +
                    "<li><strong>Universal Compatibility:</strong> Works across all devices & platforms</li>" +
                    "<li><strong>Ready to Use:</strong> Instant download and deployment</li>");
                vars.put("additionalTips", 
                    "<div style='background: #fce7f3; border: 1px solid #ec4899; border-radius: 8px; padding: 15px; margin: 25px 0;'>" +
                    "<p style='margin: 0 0 10px 0; color: #9f1239; font-size: 14px; font-weight: 600;'>💡 Format Conversion Tips:</p>" +
                    "<ul style='margin: 0; padding-left: 20px; color: #9f1239; font-size: 13px; line-height: 1.6;'>" +
                    "<li>Different formats serve different purposes – choose wisely!</li>" +
                    "<li>MP4 for videos, MP3 for audio, PNG for images with transparency</li>" +
                    "<li>Convert once, use everywhere across your platforms</li>" +
                    "</ul></div>");
                break;
                
            case TTS:
                vars.put("serviceName", "AI Voice Audio");
                vars.put("serviceEmoji", "Audio");
                vars.put("serviceAction", "AI voice generation");
                vars.put("fileType", "audio");
                vars.put("serviceType", "tts");
                vars.put("featuresList", 
                    "<li><strong>Natural AI Voice:</strong> Human-like speech synthesis</li>" +
                    "<li><strong>High-Quality Audio:</strong> Professional-grade output</li>" +
                    "<li><strong>Ready to Use:</strong> Perfect for videos, podcasts & presentations</li>" +
                    "<li><strong>Customizable:</strong> Multiple voices and languages available</li>");
                vars.put("additionalTips", 
                    "<div style='background: #e0e7ff; border: 1px solid #6366f1; border-radius: 8px; padding: 15px; margin: 25px 0;'>" +
                    "<p style='margin: 0 0 10px 0; color: #3730a3; font-size: 14px; font-weight: 600;'>💡 AI Voice Tips:</p>" +
                    "<ul style='margin: 0; padding-left: 20px; color: #3730a3; font-size: 13px; line-height: 1.6;'>" +
                    "<li>AI voices save time and resources in content creation</li>" +
                    "<li>Perfect for explainer videos, tutorials, and narrations</li>" +
                    "<li>Combine with video editing for complete productions</li>" +
                    "</ul></div>");
                break;
            case VIDEO_SPEED:
                vars.put("serviceName", "Speed-Adjusted Video");
                vars.put("serviceEmoji", "Video");
                vars.put("serviceAction", "video speed adjustment");
                vars.put("fileType", "video");
                vars.put("serviceType", "video-speed");
                vars.put("featuresList",
                        "<li><strong>Speed Adjusted:</strong> Video playback speed modified to your preference</li>" +
                                "<li><strong>Audio Synced:</strong> Audio pitch and tempo automatically adjusted</li>" +
                                "<li><strong>High Quality:</strong> Professional encoding maintains visual quality</li>" +
                                "<li><strong>Ready to Share:</strong> Perfect for time-lapse or slow-motion effects</li>");
                vars.put("additionalTips",
                        "<div style='background: #fef3c7; border: 1px solid #f59e0b; border-radius: 8px; padding: 15px; margin: 25px 0;'>" +
                                "<p style='margin: 0 0 10px 0; color: #92400e; font-size: 14px; font-weight: 600;'>💡 Speed Adjustment Tips:</p>" +
                                "<ul style='margin: 0; padding-left: 20px; color: #92400e; font-size: 13px; line-height: 1.6;'>" +
                                "<li>2x speed is perfect for tutorials and long-form content</li>" +
                                "<li>0.5x speed creates dramatic slow-motion effects</li>" +
                                "<li>Speed adjustments are great for matching music tempo</li>" +
                                "</ul></div>");
                break;

            default:
                vars.put("serviceName", "Processed Media");
                vars.put("serviceEmoji", "File");
                vars.put("serviceAction", "processing");
                vars.put("fileType", "file");
                vars.put("serviceType", "generic");
                vars.put("featuresList", 
                    "<li><strong>Processing Complete:</strong> Your media is ready</li>" +
                    "<li><strong>Quality Assured:</strong> Professional-grade output</li>" +
                    "<li><strong>Ready to Download:</strong> Instant access via CDN</li>");
                vars.put("additionalTips", "");
        }
        
        return vars;
    }

    /**
     * Service types supported
     */
    public enum ServiceType {
        SUBTITLE,
        COMPRESSION,
        CONVERSION,
        TTS,
        VIDEO_SPEED
    }
}