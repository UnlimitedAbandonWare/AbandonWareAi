package com.example.lms.service;

import com.example.lms.domain.ConfigurationSetting;
import com.example.lms.repository.ConfigurationSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptServiceTest {

    @Test
    void blankDbSystemPromptFallsBackToConfiguredDefault() {
        ConfigurationSettingRepository repository = mock(ConfigurationSettingRepository.class);
        when(repository.findById(PromptService.SYSTEM_PROMPT_KEY))
                .thenReturn(Optional.of(new ConfigurationSetting(PromptService.SYSTEM_PROMPT_KEY, "   ")));
        PromptService service = new PromptService(repository);
        ReflectionTestUtils.setField(service, "defaultSystemPrompt", "default prompt");

        assertEquals("default prompt", service.getSystemPrompt());
    }

    @Test
    void nonBlankDbSystemPromptWins() {
        ConfigurationSettingRepository repository = mock(ConfigurationSettingRepository.class);
        when(repository.findById(PromptService.SYSTEM_PROMPT_KEY))
                .thenReturn(Optional.of(new ConfigurationSetting(PromptService.SYSTEM_PROMPT_KEY, "custom prompt")));
        PromptService service = new PromptService(repository);
        ReflectionTestUtils.setField(service, "defaultSystemPrompt", "default prompt");

        assertEquals("custom prompt", service.getSystemPrompt());
    }
}
