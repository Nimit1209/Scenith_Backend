package com.example.Scenith.dto;

import lombok.Data;

import java.util.*;

@Data
public class TextSegment implements Segment {
    private String id = UUID.randomUUID().toString();
    private String text;
    private String fontFamily = "ARIAL";
    private Double scale = 1.0; // Replaced fontSize with scale
    private String fontColor = "white";
    private String backgroundColor = "transparent";
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double opacity = 1.0;
    private double timelineStartTime;
    private double timelineEndTime;
    private Integer layer = 0;
    private String alignment = "left"; // Text alignment (left, right, center)

    // Background properties
    private Double backgroundOpacity = 1.0; // Opacity of background (0.0 to 1.0)
    private Integer backgroundBorderWidth = 0; // Border thickness in pixels
    private String backgroundBorderColor = "transparent"; // Border color
    private Integer backgroundH = 0; // Background height in pixels (replaced backgroundPadding)
    private Integer backgroundW = 0; // Background width in pixels (replaced backgroundPadding)
    private Integer backgroundBorderRadius = 0; // Border radius in pixels

    // Text border properties
    private String textBorderColor = "transparent"; // Text border (stroke) color
    private Integer textBorderWidth = 0; // Text border (stroke) width in pixels
    private Double textBorderOpacity = 1.0; // Opacity of text border (0.0 to 1.0)

    private Double letterSpacing = 0.0; // Letter spacing in pixels

    private Double lineSpacing = 1.2; // Default line spacing (1.2x font size, matching frontend)

    private Double rotation = 0.0; // Rotation in degrees, default to 0

    public Double getRotation() {
        return rotation;
    }

    public void setRotation(Double rotation) {
        this.rotation = rotation;
    }

    // Validate line spacing
    public void setLineSpacing(Double lineSpacing) {
        if (lineSpacing != null && lineSpacing < 0.0) {
            throw new IllegalArgumentException("Line spacing must be non-negative");
        }
        this.lineSpacing = lineSpacing != null ? lineSpacing : 1.2;
    }

    // Getter for line spacing
    public Double getLineSpacing() {
        return lineSpacing;
    }

    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    // Validate letter spacing
    public void setLetterSpacing(Double letterSpacing) {
        if (letterSpacing != null && letterSpacing < 0.0) {
            throw new IllegalArgumentException("Letter spacing must be non-negative");
        }
        this.letterSpacing = letterSpacing != null ? letterSpacing : 0.0;
    }

    // Getter for letter spacing
    public Double getLetterSpacing() {
        return letterSpacing;
    }

    // Validate alignment values
    public void setAlignment(String alignment) {
        if (alignment == null || (!alignment.equals("left") && !alignment.equals("right") && !alignment.equals("center"))) {
            throw new IllegalArgumentException("Alignment must be 'left', 'right', or 'center'");
        }
        this.alignment = alignment;
    }

    // Validate background opacity
    public void setBackgroundOpacity(Double backgroundOpacity) {
        if (backgroundOpacity != null && (backgroundOpacity < 0.0 || backgroundOpacity > 1.0)) {
            throw new IllegalArgumentException("Background opacity must be between 0.0 and 1.0");
        }
        this.backgroundOpacity = backgroundOpacity != null ? backgroundOpacity : 1.0;
    }

    // Validate background border width
    public void setBackgroundBorderWidth(Integer backgroundBorderWidth) {
        if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
            throw new IllegalArgumentException("Background border width must be non-negative");
        }
        this.backgroundBorderWidth = backgroundBorderWidth != null ? backgroundBorderWidth : 0;
    }

    // Validate background height
    public void setBackgroundH(Integer backgroundH) {
        if (backgroundH != null && backgroundH < 0) {
            throw new IllegalArgumentException("Background height must be non-negative");
        }
        this.backgroundH = backgroundH != null ? backgroundH : 0;
    }

    // Validate background width
    public void setBackgroundW(Integer backgroundW) {
        if (backgroundW != null && backgroundW < 0) {
            throw new IllegalArgumentException("Background width must be non-negative");
        }
        this.backgroundW = backgroundW != null ? backgroundW : 0;
    }

    // Validate background border radius
    public void setBackgroundBorderRadius(Integer backgroundBorderRadius) {
        if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
            throw new IllegalArgumentException("Background border radius must be non-negative");
        }
        this.backgroundBorderRadius = backgroundBorderRadius != null ? backgroundBorderRadius : 0;
    }

    // Validate text border width
    public void setTextBorderWidth(Integer textBorderWidth) {
        if (textBorderWidth != null && textBorderWidth < 0) {
            throw new IllegalArgumentException("Text border width must be non-negative");
        }
        this.textBorderWidth = textBorderWidth != null ? textBorderWidth : 0;
    }

    // Validate text border opacity
    public void setTextBorderOpacity(Double textBorderOpacity) {
        if (textBorderOpacity != null && (textBorderOpacity < 0.0 || textBorderOpacity > 1.0)) {
            throw new IllegalArgumentException("Text border opacity must be between 0.0 and 1.0");
        }
        this.textBorderOpacity = textBorderOpacity != null ? textBorderOpacity : 1.0;
    }


    public Map<String, List<Keyframe>> getKeyframes() {
        return keyframes;
    }

    public void setKeyframes(Map<String, List<Keyframe>> keyframes) {
        this.keyframes = keyframes;
    }

    public void addKeyframe(String property, Keyframe keyframe) {
        List<Keyframe> propertyKeyframes = keyframes.computeIfAbsent(property, k -> new ArrayList<>());
        propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - keyframe.getTime()) < 0.0001);
        propertyKeyframes.add(keyframe);
        propertyKeyframes.sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    public void updateKeyframe(String property, Keyframe updatedKeyframe) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            // Find and update the keyframe at the specified time
            for (int i = 0; i < propertyKeyframes.size(); i++) {
                Keyframe existing = propertyKeyframes.get(i);
                if (Math.abs(existing.getTime() - updatedKeyframe.getTime()) < 0.0001) {
                    propertyKeyframes.set(i, updatedKeyframe);
                    return; // Update complete, no need to sort since time didn't change
                }
            }
        }
        // Optionally throw an exception if no keyframe is found
        // throw new IllegalArgumentException("No keyframe found for property " + property + " at time " + updatedKeyframe.getTime());
    }

    public void removeKeyframe(String property, double time) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - time) < 0.0001);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Integer getLayer() {
        return layer;
    }

    @Override
    public double getTimelineStartTime() {
        return timelineStartTime;
    }

    @Override
    public double getTimelineEndTime() {
        return timelineEndTime;
    }

    // Getters and setters for remaining fields
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public Double getScale() { return scale; }
    public void setScale(Double scale) { this.scale = scale; }
    public String getFontColor() { return fontColor; }
    public void setFontColor(String fontColor) { this.fontColor = fontColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getOpacity() { return opacity; }
    public void setOpacity(Double opacity) { this.opacity = opacity; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public void setLayer(int layer) { this.layer = layer; }
    public String getAlignment() { return alignment; }
    public String getBackgroundBorderColor() { return backgroundBorderColor; }
    public void setBackgroundBorderColor(String backgroundBorderColor) { this.backgroundBorderColor = backgroundBorderColor; }
    public String getTextBorderColor() { return textBorderColor; }
    public void setTextBorderColor(String textBorderColor) { this.textBorderColor = textBorderColor; }
    public Integer getTextBorderWidth() { return textBorderWidth; }
    public Double getTextBorderOpacity() { return textBorderOpacity; }
    public Integer getBackgroundH() { return backgroundH; }
    public Integer getBackgroundW() { return backgroundW; }
    public Integer getBackgroundBorderWidth() { return backgroundBorderWidth; }
    public Double getBackgroundOpacity() { return backgroundOpacity; }
    public Integer getBackgroundBorderRadius() { return backgroundBorderRadius; }

}