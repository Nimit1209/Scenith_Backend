package com.example.Scenith.controller;

import com.example.Scenith.dto.VoiceInfo;
import com.example.Scenith.service.AiVoicesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-voices")
public class AiVoicesController {

  private final AiVoicesService aiVoicesService;

  public AiVoicesController(AiVoicesService aiVoicesService) {
    this.aiVoicesService = aiVoicesService;
  }

  @GetMapping("/get-all-voices")
  public ResponseEntity<List<VoiceInfo>> getAllVoices() {
    return ResponseEntity.ok(aiVoicesService.getAvailableVoices());
  }

  @GetMapping("/voices-by-language")
  public ResponseEntity<List<VoiceInfo>> getVoicesByLanguage(@RequestParam String language) {
    return ResponseEntity.ok(aiVoicesService.getVoicesByLanguage(language));
  }

  @GetMapping("/voices-by-gender")
  public ResponseEntity<List<VoiceInfo>> getVoicesByGender(@RequestParam String gender) {
    return ResponseEntity.ok(aiVoicesService.getVoicesByGender(gender));
  }

  @GetMapping("/voices-by-language-and-gender")
  public ResponseEntity<List<VoiceInfo>> getVoicesByLanguageAndGender(
      @RequestParam String language,
      @RequestParam String gender
  ) {
    List<VoiceInfo> filteredVoices = aiVoicesService.getVoicesByLanguageAndGender(language, gender);
    return ResponseEntity.ok(filteredVoices);
  }
}