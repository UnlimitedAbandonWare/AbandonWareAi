package com.example.lms.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiConfigSecurityTest {

    @Test
    void publicOpenAiModeRejectsPlaceholderApiKey() {
        OpenAiConfig config = new OpenAiConfig();
        ReflectionTestUtils.setField(config, "openAiKey", "sk-local");
        ReflectionTestUtils.setField(config, "localEnabled", false);
        ReflectionTestUtils.setField(config, "localBaseUrl", "");
        ReflectionTestUtils.setField(config, "openAiBaseUrl", "https://api.openai.com/");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::openAiService);

        assertTrue(ex.getMessage().contains("OpenAI API key is missing"));
    }
}
