package com.example.Scenith.service;

import com.example.Scenith.dto.VoiceInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AiVoicesService {

  public List<VoiceInfo> getAvailableVoices() {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("voices.json")) {
      return mapper.readValue(is, new TypeReference<List<VoiceInfo>>() {});
    } catch (IOException e) {
      e.printStackTrace();
      return Collections.emptyList();
    }
  }

  public List<VoiceInfo> getVoicesByLanguage(String language) {
    return getAvailableVoices().stream()
        .filter(voice -> voice.getLanguage().equalsIgnoreCase(language))
        .collect(Collectors.toList());
  }

  public List<VoiceInfo> getVoicesByGender(String gender) {
    return getAvailableVoices().stream()
        .filter(voice -> voice.getGender().equalsIgnoreCase(gender))
        .collect(Collectors.toList());
  }

  public List<VoiceInfo> getVoicesByLanguageAndGender(String language, String gender) {
    return getAvailableVoices().stream()
        .filter(voice -> voice.getLanguage().equalsIgnoreCase(language))
        .filter(voice -> voice.getGender().equalsIgnoreCase(gender))
        .collect(Collectors.toList());
  }
}