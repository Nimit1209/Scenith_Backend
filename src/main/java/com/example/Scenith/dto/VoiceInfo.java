package com.example.Scenith.dto;


public class VoiceInfo {

  private String language;
  private String languageCode;
  private String voiceName;
  private String gender;
  private String humanName;
  private String profileUrl;

  public VoiceInfo() {}

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getLanguageCode() {
    return languageCode;
  }

  public void setLanguageCode(String languageCode) {
    this.languageCode = languageCode;
  }

  public String getVoiceName() {
    return voiceName;
  }

  public void setVoiceName(String voiceName) {
    this.voiceName = voiceName;
  }

  public String getGender() {
    return gender;
  }

  public void setGender(String gender) {
    this.gender = gender;
  }

  public String getHumanName() {
    return humanName;
  }

  public void setHumanName(String humanName) {
    this.humanName = humanName;
  }

  public String getProfileUrl() {
    return profileUrl;
  }

  public void setProfileUrl(String profileUrl) {
    this.profileUrl = profileUrl;
  }
}