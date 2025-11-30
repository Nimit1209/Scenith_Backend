package com.example.Scenith.dto.imagedto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.util.List;

@Data
public class LayerDTO {
  private String id;
  private String type;
  private Integer zIndex;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double x;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double y;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double width;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double height;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double rotation;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double opacity;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double cropTop;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double cropRight;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double cropBottom;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double cropLeft;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double scale; // For image layers only

  private String src;

  private String text;
  private String fontFamily;
  private Integer fontSize;
  private String fontWeight;
  private String fontStyle;
  private String color;
  private String textAlign;
  private Integer letterSpacing;
  private Integer lineHeight;
  private String  textDecoration;   // "underline" | "line-through" | "none"
  private String  textTransform;    // "uppercase" | "lowercase" | "capitalize" | "none"
  private Double  outlineWidth;     // stroke width for text outline
  private String  outlineColor;     // hex color of the outline
  private Double  backgroundOpacity; // 0-1, background fill behind the text
  private String  backgroundColor;   // hex, used with backgroundOpacity
  private String  verticalAlign;    // "top" | "middle" | "bottom"
  private Boolean wordWrap;         // true = wrap, false = single line
  private Double  curveRadius;

  private String shape;
  private String fill;
  private String stroke;
  private Integer strokeWidth;
  private Integer borderRadius;

  private List<FilterDTO> filters;
  private ShadowDTO shadow;
  private ClipPathDTO clipPath;

  // Add these fields to the LayerDTO class
  private String backgroundBorder;
  private Integer backgroundBorderRadius;

  @Data
  public static class TextSegmentDTO {
    private String text;
    private String color;
    private Integer startIndex;
    private Integer endIndex;
  }

  private List<TextSegmentDTO> textSegments;
  private Integer backgroundBorderWidth;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double backgroundHeight;

  @JsonSerialize(using = ToStringSerializer.class)
  private Double backgroundWidth;

  private String linethroughColor;

  @Data
  public static class FilterDTO {
    private String type;
    private Double value;

    // For filters that need multiple values (like temperature/tint)
    private Double value2;
  }
}