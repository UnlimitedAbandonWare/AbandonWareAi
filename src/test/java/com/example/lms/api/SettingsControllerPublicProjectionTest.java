package com.example.lms.api;

import com.example.lms.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingsControllerPublicProjectionTest {

    @Test
    void getAllSettingsReturnsOnlyAllowlistedKeys() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getAllSettings()).thenReturn(Map.of(
                "SYSTEM_PROMPT", "synthetic internal prompt",
                SettingsService.KEY_TEMPERATURE, "0.7",
                SettingsService.KEY_OPENAI_MODEL, "synthetic-model",
                "display_theme", "dark",
                "future_unknown_setting", "synthetic value"));
        SettingsController controller = new SettingsController(settingsService);

        ResponseEntity<Map<String, String>> response = controller.getAllSettings();

        assertEquals(200, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(Map.of(
                SettingsService.KEY_TEMPERATURE, "0.7",
                SettingsService.KEY_OPENAI_MODEL, "synthetic-model"), body);
        assertFalse(body.containsKey("SYSTEM_PROMPT"));
        assertFalse(body.containsKey("display_theme"));
        assertFalse(body.containsKey("future_unknown_setting"));
    }

    @Test
    void saveAllSettingsRejectsNonPublicKeys() {
        SettingsService settingsService = mock(SettingsService.class);
        SettingsController controller = new SettingsController(settingsService);

        var response = controller.saveAllSettings(Map.of(
                "SYSTEM_PROMPT", "synthetic injection attempt",
                SettingsService.KEY_TEMPERATURE, "0.5"));

        assertEquals(400, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("unsupported setting keys", body.get("message"));
        assertTrue(body.get("rejected").contains("SYSTEM_PROMPT"));
        verify(settingsService, never()).saveAllSettings(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void saveAllSettingsPersistsOnlyPublicKeys() {
        SettingsService settingsService = mock(SettingsService.class);
        SettingsController controller = new SettingsController(settingsService);

        var response = controller.saveAllSettings(Map.of(
                SettingsService.KEY_TEMPERATURE, "0.5",
                SettingsService.KEY_OPENAI_MODEL, "synthetic-model"));

        assertEquals(200, response.getStatusCode().value());
        verify(settingsService).saveAllSettings(Map.of(
                SettingsService.KEY_TEMPERATURE, "0.5",
                SettingsService.KEY_OPENAI_MODEL, "synthetic-model"));
    }
}
