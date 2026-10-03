package com.example.lms.api;

import ai.abandonware.nova.orch.aop.SettingsControllerSecretMaskAspect;
import com.example.lms.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsExposurePolicyTest {
    private SettingsController proxy(SettingsService service) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new SettingsController(service));
        factory.addAspect(new SettingsControllerSecretMaskAspect(new MockEnvironment()));
        return factory.getProxy();
    }

    @Test void modelPreferenceSurvivesGetAndPostThroughAspect() {
        assertModelRoundTrip("gemma4:26b");
    }

    @Test void longModelIdIsNotAnOpaqueSecret() {
        assertModelRoundTrip("organisation-example-long-model-name-release-20261002");
    }

    private void assertModelRoundTrip(String model) {
        SettingsService service = mock(SettingsService.class);
        Map<String,String> values = Map.of(SettingsService.KEY_OPENAI_MODEL, model);
        when(service.getAllSettings()).thenReturn(values);
        SettingsController controller = proxy(service);
        assertEquals(model, controller.getAllSettings().getBody().get(SettingsService.KEY_OPENAI_MODEL));
        assertEquals(200, controller.saveAllSettings(values).getStatusCode().value());
        verify(service).saveAllSettings(values);
    }

    @Test void credentialKeyWritesAreExplicitlyRejectedWithoutPersistence() {
        for (String key : new String[]{"OPENAI_API_KEY", "token", "password"}) {
            SettingsService service = mock(SettingsService.class);
            ResponseEntity<Map<String,String>> response = proxy(service).saveAllSettings(Map.of(key,"synthetic-fixture"));
            assertEquals(400, response.getStatusCode().value(), key);
            assertEquals("SETTINGS_SECRET_VALUE_FORBIDDEN",response.getBody().get("code"));
            assertFalse(response.getBody().toString().contains("synthetic-fixture"));
            verify(service,never()).saveAllSettings(anyMap());
        }
    }

    @Test void credentialValueUnderModelPreferenceIsRejectedWithoutEcho() {
        SettingsService service = mock(SettingsService.class);
        String synthetic = "sk-" + "synthetic-settings-fixture";
        ResponseEntity<Map<String,String>> response = proxy(service).saveAllSettings(Map.of("OPENAI_MODEL",synthetic));
        assertEquals(400,response.getStatusCode().value());
        assertEquals("SETTINGS_SECRET_VALUE_FORBIDDEN",response.getBody().get("code"));
        assertFalse(response.getBody().toString().contains(synthetic));
        verify(service,never()).saveAllSettings(anyMap());
    }
}
