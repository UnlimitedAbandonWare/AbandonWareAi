package com.example.lms.settings;

import com.example.lms.config.ChatDefaultsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ChatDefaultsBindingTest {
    @Test void actualFactoryYamlBindsTypedValuesAndOffRemainsString() throws Exception {
        var env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("factory", new ClassPathResource("application-llm.yaml")))
            env.getPropertySources().addLast(source);
        var defaults = Binder.get(env).bind("chat.defaults", ChatDefaultsProperties.class).get();
        assertEquals("OFF", defaults.getSearchMode());
        assertFalse(defaults.getUseWebSearch());
        assertTrue(defaults.isFinite());
        assertEquals(2048, defaults.getMaxTokens());
        assertEquals(11, defaults.values().size());
    }
}
