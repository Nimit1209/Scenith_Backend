package com.example.Scenith.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class Transition {
    private String id = UUID.randomUUID().toString();
    private String type; // e.g., "fade", "dissolve", "wipe", "slide", "zoom", "rotate"
    private double duration; // in seconds
    private String segmentId; // ID of the segment to which the transition applies
    private boolean start; // true if transition applies at the segment's start
    private boolean end; // true if transition applies at the segment's end
    private int layer; // Layer where the transition occurs
    private double timelineStartTime; // When the transition starts on the timeline
    private Map<String, String> parameters = new HashMap<>(); // Additional settings, e.g., {"direction": "left"}

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public boolean isStart() {
        return start;
    }

    public void setStart(boolean start) {
        this.start = start;
    }

    public boolean isEnd() {
        return end;
    }

    public void setEnd(boolean end) {
        this.end = end;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public double getTimelineStartTime() {
        return timelineStartTime;
    }

    public void setTimelineStartTime(double timelineStartTime) {
        this.timelineStartTime = timelineStartTime;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }
}