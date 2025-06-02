package com.example.Scenith.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class TimelineState {
    private List<VideoSegment> segments;
    private List<TextSegment> textSegments;
    private Map<String, Object> metadata;
    private Long lastModified;
    private List<AudioSegment> audioSegments = new ArrayList<>();
    private List<ImageSegment> imageSegments = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    private Integer canvasWidth;
    private Integer canvasHeight;
    private List<Transition> transitions = new ArrayList<>(); // NEW: List of transitions

    public TimelineState() {
        this.segments = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.textSegments = new ArrayList<>();
    }

    public List<Transition> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<Transition> transitions) {
        this.transitions = transitions;
    }

    // Getters and setters (unchanged)
    public List<VideoSegment> getSegments() {
        if (segments == null) {
            segments = new ArrayList<>();
        }
        return segments;
    }

    public void setSegments(List<VideoSegment> segments) {
        this.segments = segments;
    }

    public List<TextSegment> getTextSegments() {
        if (textSegments == null) {
            textSegments = new ArrayList<>();
        }
        return textSegments;
    }

    public void setTextSegments(List<TextSegment> textSegments) {
        this.textSegments = textSegments;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Long getLastModified() {
        return lastModified;
    }

    public void setLastModified(Long lastModified) {
        this.lastModified = lastModified;
    }

    public List<AudioSegment> getAudioSegments() {
        if (audioSegments == null) {
            audioSegments = new ArrayList<>();
        }
        return audioSegments;
    }

    public void setAudioSegments(List<AudioSegment> audioSegments) {
        this.audioSegments = audioSegments;
    }

    public List<ImageSegment> getImageSegments() {
        return imageSegments;
    }

    public void setImageSegments(List<ImageSegment> imageSegments) {
        this.imageSegments = imageSegments;
    }

    public List<Filter> getFilters() {
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public Integer getCanvasWidth() {
        return canvasWidth;
    }

    public void setCanvasWidth(Integer canvasWidth) {
        this.canvasWidth = canvasWidth;
    }

    public Integer getCanvasHeight() {
        return canvasHeight;
    }

    public void setCanvasHeight(Integer canvasHeight) {
        this.canvasHeight = canvasHeight;
    }

    public List<VideoSegment> getSegmentsByLayer(int layer) {
        List<VideoSegment> layerSegments = new ArrayList<>();
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer) {
                layerSegments.add(segment);
            }
        }
        return layerSegments;
    }

    public int getMaxLayer() {
        int maxLayer = 0;
        for (VideoSegment segment : segments) {
            if (segment.getLayer() > maxLayer) {
                maxLayer = segment.getLayer();
            }
        }
        return maxLayer;
    }

    public boolean isTimelinePositionAvailable(double startTime, double endTime, int layer) {
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer &&
                    startTime < segment.getTimelineEndTime() &&
                    endTime > segment.getTimelineStartTime()) {
                return false;
            }
        }
        for (TextSegment segment : textSegments) {
            if (segment.getLayer() == layer &&
                    startTime < segment.getTimelineEndTime() &&
                    endTime > segment.getTimelineStartTime()) {
                return false;
            }
        }
        for (AudioSegment segment : audioSegments) {
            if (segment.getLayer() == layer && layer < 0) {
                if (startTime < segment.getTimelineEndTime() &&
                        endTime > segment.getTimelineStartTime()) {
                    return false;
                }
            }
        }

        for (ImageSegment segment : imageSegments) {
            if (segment.getLayer() == layer &&
                    startTime < segment.getTimelineEndTime() &&
                    endTime > segment.getTimelineStartTime()) {
                return false;
            }
        }

        return true;
    }
}