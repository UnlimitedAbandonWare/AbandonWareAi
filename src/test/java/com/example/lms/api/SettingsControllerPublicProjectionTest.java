package com.example.lms.api;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsControllerPublicProjectionTest {

    @Test
    void getAllSettingsDefaultsToDenyingInternalAndUnknownSettings() {
        ConfigurationSettingRepository repository = mock(ConfigurationSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ConfigurationSetting("SYSTEM_PROMPT", "synthetic internal prompt"),
                new ConfigurationSetting("display_theme", "dark"),
                new ConfigurationSetting("future_unknown_setting", "synthetic value")));
        SettingsController controller = new SettingsController(repository);

        ResponseEntity<Map<String, String>> response = controller.getAllSettings();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(Map.of(), response.getBody());
    }
}
