package com.example.Scenith.dto;

import lombok.Data;
import java.util.List;

@Data
public class LayerDTO {
  private String id;
  private String type;
  private Integer zIndex;
  private Double opacity;

  private Double x;
  private Double y;
  private Double width;
  private Double height;
  private Double rotation;

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

  private String shape;
  private String fill;
  private String stroke;
  private Integer strokeWidth;
  private Integer borderRadius;

  private List<FilterDTO> filters;
  private ShadowDTO shadow;
  private ClipPathDTO clipPath;
}