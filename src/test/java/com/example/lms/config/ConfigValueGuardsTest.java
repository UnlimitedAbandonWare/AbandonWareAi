package com.example.lms.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValueGuardsTest {

    @Test
    void treatsExternalCredentialPlaceholdersAsMissing() {
        assertTrue(ConfigValueGuards.isMissing(""));
        assertTrue(ConfigValueGuards.isMissing("dummy"));
        assertTrue(ConfigValueGuards.isMissing("null"));
        assertTrue(ConfigValueGuards.isMissing("test"));
        assertTrue(ConfigValueGuards.isMissing("changeme"));
        assertTrue(ConfigValueGuards.isMissing("<TOKEN>"));
        assertTrue(ConfigValueGuards.isMissing("ollama"));
        assertTrue(ConfigValueGuards.isMissing("sk-local"));
        assertTrue(ConfigValueGuards.isMissing("${SERPAPI_API_KEY}"));
    }

    @Test
    void keepsNonPlaceholderValuesPresent() {
        assertFalse(ConfigValueGuards.isMissing("real-looking-value-123"));
        assertFalse(ConfigValueGuards.isMissing("naver-client-id-value"));
    }

    @Test
    void localOpenAiCompatAllowsOnlyOllamaPlaceholder() {
        assertFalse(ConfigValueGuards.isMissingLocalOpenAiCompatKey("ollama"));
        assertFalse(ConfigValueGuards.isMissingLocalOpenAiCompatKey(" OLLAMA "));
        assertTrue(ConfigValueGuards.isMissingLocalOpenAiCompatKey("dummy"));
        assertTrue(ConfigValueGuards.isMissingLocalOpenAiCompatKey("sk-local"));
        assertTrue(ConfigValueGuards.isMissingLocalOpenAiCompatKey("test"));
        assertTrue(ConfigValueGuards.isMissingLocalOpenAiCompatKey("changeme"));
        assertTrue(ConfigValueGuards.isMissingLocalOpenAiCompatKey("${LLM_API_KEY:}"));
    }
}
