package com.example.lms.api;

import com.example.lms.repository.ConfigurationSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SettingsControllerNullPayloadTest {

    @Test
    void saveAllSettingsRejectsNullPayloadWithoutTouchingRepository() {
        ConfigurationSettingRepository repository = mock(ConfigurationSettingRepository.class);
        SettingsController controller = new SettingsController(repository);

        var response = controller.saveAllSettings(null);

        assertEquals(400, response.getStatusCode().value());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals("settings body is required", body.get("message"));
        verifyNoInteractions(repository);
    }
}
