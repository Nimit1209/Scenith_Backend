package com.example.Scenith.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class SubtitleDTO {
    private String id;
    private String text;
    private String fontFamily = "Arial";
    private Double scale = 1.0;
    private String fontColor = "white";
    private String backgroundColor = "transparent";
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double opacity = 1.0;
    private Double timelineStartTime;
    private Double timelineEndTime;
    private Integer layer = 0;
    private String alignment = "center"; // Default to center for subtitles
    private Double backgroundOpacity = 1.0;
    private Integer backgroundBorderWidth = 0;
    private String backgroundBorderColor = "transparent";
    private Integer backgroundH = 50;
    private Integer backgroundW = 50;
    private Integer backgroundBorderRadius = 15;
    private String textBorderColor = "transparent";
    private Integer textBorderWidth = 0;
    private Double textBorderOpacity = 1.0;
    private Double letterSpacing = 0.0;
    private Double lineSpacing = 1.2;
    private Double rotation = 0.0;
    @JsonProperty("isSubtitle")
    private boolean isSubtitle = true;

    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    // Validation methods (same as TextSegment)
    public void setLineSpacing(Double lineSpacing) {
        if (lineSpacing != null && lineSpacing < 0.0) {
            throw new IllegalArgumentException("Line spacing must be non-negative");
        }
        this.lineSpacing = lineSpacing != null ? lineSpacing : 1.2;
    }

    public void setAlignment(String alignment) {
        if (alignment == null || (!alignment.equals("left") && !alignment.equals("right") && !alignment.equals("center"))) {
            throw new IllegalArgumentException("Alignment must be 'left', 'right', or 'center'");
        }
        this.alignment = alignment;
    }

    public void setBackgroundOpacity(Double backgroundOpacity) {
        if (backgroundOpacity != null && (backgroundOpacity < 0.0 || backgroundOpacity > 1.0)) {
            throw new IllegalArgumentException("Background opacity must be between 0.0 and 1.0");
        }
        this.backgroundOpacity = backgroundOpacity != null ? backgroundOpacity : 1.0;
    }

    public void setBackgroundBorderWidth(Integer backgroundBorderWidth) {
        if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
            throw new IllegalArgumentException("Background border width must be non-negative");
        }
        this.backgroundBorderWidth = backgroundBorderWidth != null ? backgroundBorderWidth : 0;
    }

    public void setBackgroundH(Integer backgroundH) {
        if (backgroundH != null && backgroundH < 0) {
            throw new IllegalArgumentException("Background height must be non-negative");
        }
        this.backgroundH = backgroundH != null ? backgroundH : 50;
    }

    public void setBackgroundW(Integer backgroundW) {
        if (backgroundW != null && backgroundW < 0) {
            throw new IllegalArgumentException("Background width must be non-negative");
        }
        this.backgroundW = backgroundW != null ? backgroundW : 50;
    }

    public void setBackgroundBorderRadius(Integer backgroundBorderRadius) {
        if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
            throw new IllegalArgumentException("Background border radius must be non-negative");
        }
        this.backgroundBorderRadius = backgroundBorderRadius != null ? backgroundBorderRadius : 15;
    }

    public void setTextBorderWidth(Integer textBorderWidth) {
        if (textBorderWidth != null && textBorderWidth < 0) {
            throw new IllegalArgumentException("Text border width must be non-negative");
        }
        this.textBorderWidth = textBorderWidth != null ? textBorderWidth : 0;
    }

    public void setTextBorderOpacity(Double textBorderOpacity) {
        if (textBorderOpacity != null && (textBorderOpacity < 0.0 || textBorderOpacity > 1.0)) {
            throw new IllegalArgumentException("Text border opacity must be between 0.0 and 1.0");
        }
        this.textBorderOpacity = textBorderOpacity != null ? textBorderOpacity : 1.0;
    }

    public void setLetterSpacing(Double letterSpacing) {
        if (letterSpacing != null && letterSpacing < 0.0) {
            throw new IllegalArgumentException("Letter spacing must be non-negative");
        }
        this.letterSpacing = letterSpacing != null ? letterSpacing : 0.0;
    }
}