package com.example.Scenith.dto;

import lombok.Data;

@Data
public class Keyframe {
    private double time; // Time relative to segment's timelineStartTime (in seconds)
    private Object value; // Value of the property (e.g., Integer for position, Double for scale)
    private String interpolationType = "linear"; // Default to linear; could extend to "ease-in", "ease-out", etc.

    public Keyframe() {}

    public Keyframe(double time, Object value, String interpolationType) {
        this.time = time;
        this.value = value;
        this.interpolationType = interpolationType != null ? interpolationType : "linear";
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getInterpolationType() {
        return interpolationType;
    }

    public void setInterpolationType(String interpolationType) {
        this.interpolationType = interpolationType;
    }
}