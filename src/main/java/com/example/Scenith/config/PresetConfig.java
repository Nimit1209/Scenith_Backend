package com.example.Scenith.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class PresetConfig {

    private final Map<String, Map<String, Double>> presets = new HashMap<>();

    @Getter
    private List<Map<String, Object>> presetList; // if you also want raw list for APIs

    @PostConstruct
    public void loadPresets() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("presets.json");
            Map<String, List<Map<String, Object>>> json = mapper.readValue(
                inputStream, new TypeReference<>() {}
            );
            presetList = json.get("presets");

            for (Map<String, Object> preset : presetList) {
                String name = (String) preset.get("name");
                Map<String, Double> values = new HashMap<>();
                for (Map.Entry<String, Object> entry : preset.entrySet()) {
                    if (!entry.getKey().equals("name")) {
                        values.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
                    }
                }
                presets.put(name, values);
            }

            log.info("Loaded {} presets from presets.json", presets.size());

        } catch (Exception e) {
            log.error("Failed to load presets.json", e);
        }
    }

    public Map<String, Double> getPreset(String name) {
        return presets.get(name);
    }
}