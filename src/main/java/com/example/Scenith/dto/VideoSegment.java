package com.example.Scenith.dto;

import lombok.Data;

import java.util.*;

@Data
public class VideoSegment implements Segment {
    private String sourceVideoPath;
    private double startTime;
    private double endTime;
    private String id;
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double scale = 1.0;
    private Double opacity = 1.0;
    private Integer layer = 0;
    private double timelineStartTime;
    private double timelineEndTime;
    private String audioId;
    private Double cropL = 0.0; // Crop percentage from left (0 to 100)
    private Double cropR = 0.0; // Crop percentage from right (0 to 100)
    private Double cropT = 0.0; // Crop percentage from top (0 to 100)
    private Double cropB = 0.0; // Crop percentage from bottom (0 to 100)
    private Double speed = 1.0; // Default speed is 1.0 (normal speed)

    private Double rotation = 0.0; // Rotation in degrees, default to 0

    public Double getRotation() {
        return rotation;
    }

    public void setRotation(Double rotation) {
        this.rotation = rotation;
    }

    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    public VideoSegment() {
        this.id = UUID.randomUUID().toString();
    }

    public String getAudioId() {
        return audioId;
    }

    public Double getSpeed() {
        return speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    public void setAudioId(String audioId) {
        this.audioId = audioId;
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

    public String getSourceVideoPath() { return sourceVideoPath; }
    public void setSourceVideoPath(String sourceVideoPath) { this.sourceVideoPath = sourceVideoPath; }
    public double getStartTime() { return startTime; }
    public void setStartTime(double startTime) { this.startTime = startTime; }
    public double getEndTime() { return endTime; }
    public void setEndTime(double endTime) { this.endTime = endTime; }
    public void setId(String id) { this.id = id; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getScale() { return scale; }
    public void setScale(Double scale) { this.scale = scale; }
    public Double getOpacity() { return opacity; }
    public void setOpacity(Double opacity) { this.opacity = opacity; }
    public void setLayer(Integer layer) { this.layer = layer; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public Double getCropL() { return cropL; }
    public void setCropL(Double cropL) { this.cropL = cropL; }
    public Double getCropR() { return cropR; }
    public void setCropR(Double cropR) { this.cropR = cropR; }
    public Double getCropT() { return cropT; }
    public void setCropT(Double cropT) { this.cropT = cropT; }
    public Double getCropB() { return cropB; }
    public void setCropB(Double cropB) { this.cropB = cropB; }
}