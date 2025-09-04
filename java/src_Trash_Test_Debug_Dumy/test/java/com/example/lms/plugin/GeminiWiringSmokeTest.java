package com.example.lms.plugin;

import com.example.lms.plugin.image.GeminiImagePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that the Spring context starts up when the Gemini image
 * integration is disabled and that a no-op {@link GeminiImagePort}
 * bean is injected.  When disabled, the {@link GeminiImagePort}
 * should report that it is not configured and return empty lists for
 * generation requests.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "gemini.image.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class GeminiWiringSmokeTest {

    @Autowired
    GeminiImagePort port;

    @Test
    void contextLoads_andNoopPresent() {
        assert port != null;
        assert !port.isConfigured();
        assert port.generate("x", 1, null).isEmpty();
    }
}