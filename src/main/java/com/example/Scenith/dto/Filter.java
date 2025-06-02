package com.example.Scenith.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class Filter {
    private String filterId;    // Unique ID for the filter instance
    private String segmentId;   // ID of the segment this filter applies to
    private String filterName;  // Name of the filter (e.g., "brightness", "sepia")
    private String filterValue; // Value for the filter (e.g., "0.5" for brightness)

    public Filter() {
        this.filterId = UUID.randomUUID().toString();
    }

    public String getFilterId() {
        return filterId;
    }

    public void setFilterId(String filterId) {
        this.filterId = filterId;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public String getFilterName() {
        return filterName;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    public String getFilterValue() {
        return filterValue;
    }

    public void setFilterValue(String filterValue) {
        this.filterValue = filterValue;
    }
}