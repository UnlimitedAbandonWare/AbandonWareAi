package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiModelSelectionPolicyTest {

    @Test
    void localProviderAllowsManualRemoteSelectionOnlyWhenOpenAiKeyIsUsable() {
        assertFalse(OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection("local", false, ""));
        assertFalse(OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection("local", true, ""));

        assertTrue(OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection(
                "local",
                false,
                com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void nonLocalProviderStillHonorsExplicitRemoteSelectionFlag() {
        assertFalse(OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection("openai", false, ""));
        assertTrue(OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection("openai", true, ""));
    }

    @Test
    void curatedCatalogKeepsChatModelsAndDropsResponsesOnlyProByDefault() {
        List<String> models = OpenAiModelSelectionPolicy.openAiChatModels("");

        assertTrue(models.contains("gpt-5.5"));
        assertTrue(models.contains("gpt-5.4-mini"));
        assertFalse(models.contains("gpt-5.5-pro"));
    }

    @Test
    void customCatalogIsTrimmedDeduplicatedAndChatOnly() {
        assertIterableEquals(
                List.of("gpt-5.5", "gpt-4.1-mini"),
                OpenAiModelSelectionPolicy.openAiChatModels(
                        " gpt-5.5, text-embedding-3-large, gpt-5.5, babbage-002, gpt-4.1-mini "));
    }
}
