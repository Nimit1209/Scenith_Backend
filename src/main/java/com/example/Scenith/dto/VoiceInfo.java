package com.example.Scenith.dto;

import lombok.Data;

import java.util.Map;

@Data
public class VoiceInfo {

  private String language;
  private String languageCode;
  private String voiceName;
  private String gender;
  private String humanName;
  private String profileUrl;
  private String voiceStyle;
  private Map<String, String> ssmlConfig;

  public VoiceInfo() {}
}