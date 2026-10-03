package com.example.lms.api;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.SettingsService;
import com.example.lms.repository.ConfigurationSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P6SettingsNumericContractTest {
    @ParameterizedTest @ValueSource(strings={"abc", "", "NaN", "Infinity", "-1", "2.1"})
    void invalidMixedPublicPatchDoesNotWrite(String invalid) {
        SettingsService service = mock(SettingsService.class);
        var response = new SettingsController(service).saveAllSettings(Map.of(
                SettingsService.KEY_TEMPERATURE, invalid, SettingsService.KEY_OPENAI_MODEL, "future-model"));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
        assertEquals(SettingsService.KEY_TEMPERATURE, response.getBody().get("key"));
        assertFalse(response.getBody().containsKey("value"));
    }

    @Test void serviceValidatesBeforeLookingUpOrMutatingAnyEntity() {
        var repo = mock(ConfigurationSettingRepository.class);
        var settings = new SettingsService(repo);
        assertThrows(IllegalArgumentException.class, () -> settings.saveAllSettings(Map.of(
                SettingsService.KEY_TOP_P, "2", SettingsService.KEY_OPENAI_MODEL, "future-model")));
        verifyNoInteractions(repo);
    }

    @ParameterizedTest @ValueSource(strings={"abc", "", "NaN", "Infinity", "-1", "3"})
    void corruptStoredNumericUsesDefault(String invalid) {
        var ui = ChatRequestDto.builder().message("fixture").temperature(null).build();
        var result = assertDoesNotThrow(() -> ChatRequestSettingsMerger.merge(ui,
                Map.of(SettingsService.KEY_TEMPERATURE, invalid), false,
                LoggerFactory.getLogger(P6SettingsNumericContractTest.class)));
        assertEquals(0.3, result.getTemperature());
    }

    @Test void integerDefaultKeepsItsType() throws Exception {
        var method = ChatRequestSettingsMerger.class.getDeclaredMethod(
                "firstNonNull", Object.class, String.class, Object.class);
        method.setAccessible(true);
        assertEquals(Integer.valueOf(7), method.invoke(null, null, "7", Integer.valueOf(2)));
        assertEquals(Integer.valueOf(2), method.invoke(null, null, "bad", Integer.valueOf(2)));
    }

    @Test void explicitValidValueWinsOverCorruptStorage() {
        var ui = ChatRequestDto.builder().message("fixture").temperature(0.7).build();
        var result = ChatRequestSettingsMerger.merge(ui,
                Map.of(SettingsService.KEY_TEMPERATURE, "bad"), false,
                LoggerFactory.getLogger(P6SettingsNumericContractTest.class));
        assertEquals(0.7, result.getTemperature());
    }
}
