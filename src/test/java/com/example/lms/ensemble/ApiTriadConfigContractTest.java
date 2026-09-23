package com.example.lms.ensemble;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTriadConfigContractTest {

    @Test
    void applicationLlmExposesDefaultDisabledThreeProviderRoleRoutes() throws Exception {
        String yaml = Files.readString(
                Path.of("main/resources/application-llm.yaml"),
                StandardCharsets.UTF_8);
        String normalizedYaml = yaml.replace("\r\n", "\n");

        assertTrue(yaml.contains("enabled: ${ENSEMBLE_ALTERNATIVE_SUPPORT_ENABLED:false}"));
        assertTrue(yaml.contains("enabled: ${ENSEMBLE_SAMPLING_ENABLED:false}"));
        assertTrue(yaml.contains("fallback-when-openai-missing: ${LLMROUTER_FALLBACK_WHEN_OPENAI_MISSING:true}"),
                "the API triad must fail its preflight until fallback is explicitly disabled");
        assertTrue(yaml.contains("enabled: ${LLMROUTER_OPENAI_PREMIUM_ENABLED:false}"));
        assertTrue(yaml.contains("enabled: ${LLMROUTER_GEMINI_PRO_ENABLED:false}"));
        assertTrue(yaml.contains(
                "support-primary-route: ${ENSEMBLE_API_TRIAD_SUPPORT_PRIMARY_ROUTE:llmrouter.openai-premium}"));
        assertTrue(yaml.contains(
                "support-alternative-route: ${ENSEMBLE_API_TRIAD_SUPPORT_ALTERNATIVE_ROUTE:llmrouter.api3}"));
        assertTrue(yaml.contains(
                "falsify-route: ${ENSEMBLE_API_TRIAD_FALSIFY_ROUTE:llmrouter.gemini-pro}"));
        assertTrue(normalizedYaml.contains("""
                api3:
                      enabled: ${LLMROUTER_API3_ENABLED:true}
                      provider: groq
                      stage: chat
                      fallback-only: ${LLMROUTER_API3_FALLBACK_ONLY:true}
                      response-model-verification-required: true
                """));
        assertTrue(normalizedYaml.contains("""
                openai-premium:
                      enabled: ${LLMROUTER_OPENAI_PREMIUM_ENABLED:false}
                      provider: openai
                      stage: chat
                      fallback-only: true
                      response-model-verification-required: true
                """));
        assertTrue(normalizedYaml.contains("""
                gemini-pro:
                      enabled: ${LLMROUTER_GEMINI_PRO_ENABLED:false}
                      provider: gemini
                      stage: chat
                      fallback-only: true
                      response-model-verification-required: true
                """));
    }
}
