package com.example.lms.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCredentialResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "llm.api-key-openai",
            "llm.openai.api-key",
            "OPENAI_API_KEY",
            "openai.api.key",
            "openai.api-key",
            "spring.ai.openai.api-key",
            "openai.image.api-key",
            "embedding.fallback.api-key",
            "OPENAI_EMBED_FALLBACK_KEY"
    })
    void everyLiveOpenAiAliasUsesTheSingleResolverPolicy(String alias) {
        String credential = "openai-alias-contract-value";
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment().withProperty(alias, "  " + credential + "  "));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.OPENAI);

        assertTrue(result.enabled());
        assertEquals(credential, result.valueOrNull());
        assertEquals(alias, result.sourceName());
        assertEquals(1, result.configuredAliasCount());
        assertFalse(result.diagnostics().toString().contains(credential));
    }

    @Test
    void singleGeminiAliasEnablesOnlyThatCredential() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment().withProperty("GEMINI_API_KEY", "gemini-single-value"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.GEMINI);

        assertTrue(result.enabled());
        assertTrue(result.credentialPresent());
        assertEquals("gemini-single-value", result.valueOrNull());
        assertEquals("GEMINI_API_KEY", result.sourceName());
        assertEquals(1, result.configuredAliasCount());
        assertFalse(result.duplicateAlias());
        assertEquals("", result.disabledReason());
    }

    @Test
    void equalGeminiAliasesRemainEnabledAndReportOnlyDuplicateCount() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("gemini.api-key", "gemini-same-value")
                        .withProperty("gemini.api.key", "gemini-same-value")
                        .withProperty("GEMINI_API_KEY", "gemini-same-value"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.GEMINI);

        assertTrue(result.enabled());
        assertEquals("gemini-same-value", result.valueOrNull());
        assertEquals("gemini.api-key", result.sourceName());
        assertEquals(3, result.configuredAliasCount());
        assertTrue(result.duplicateAlias());
        assertFalse(result.diagnostics().toString().contains("gemini-same-value"));
    }

    @Test
    void differentGeminiAliasesDisableOnlyGeminiWithoutChoosingAValue() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("gemini.api-key", "gemini-first-value")
                        .withProperty("GEMINI_API_KEY", "gemini-second-value")
                        .withProperty("llm.api-key-openai", "openai-unrelated-value"));

        ProviderCredentialResolver.Resolution gemini =
                resolver.resolve(ProviderCredentialResolver.Provider.GEMINI);
        ProviderCredentialResolver.Resolution openAi =
                resolver.resolve(ProviderCredentialResolver.Provider.OPENAI);

        assertFalse(gemini.enabled());
        assertTrue(gemini.credentialPresent());
        assertNull(gemini.valueOrNull());
        assertEquals("conflicting-credential-aliases", gemini.disabledReason());
        assertFalse(gemini.diagnostics().toString().contains("gemini-first-value"));
        assertFalse(gemini.diagnostics().toString().contains("gemini-second-value"));
        assertTrue(openAi.enabled());
        assertEquals("openai-unrelated-value", openAi.valueOrNull());
    }

    @Test
    void placeholderAliasesDoNotConflictWithARealGeminiValue() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("gemini.api-key", "dummy")
                        .withProperty("gemini.api.key", "${UNSET_GEMINI_KEY:}")
                        .withProperty("GEMINI_API_KEY", "gemini-real-value"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.GEMINI);

        assertTrue(result.enabled());
        assertEquals("gemini-real-value", result.valueOrNull());
        assertEquals(1, result.configuredAliasCount());
    }

    @Test
    void retiredBraveSubscriptionAliasCannotInitializeTheProvider() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment().withProperty("brave.subscription.token", "brave-compatible-value"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.BRAVE);

        assertFalse(result.enabled());
        assertNull(result.valueOrNull());
        assertEquals("missing-credential", result.disabledReason());
        assertEquals("brave", result.diagnostics().provider());
    }

    @Test
    void approvedBraveMainKeyIgnoresRetiredAliasWithoutExposingEitherValue() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("gpt-search.brave.subscription-token", "brave-first-value")
                        .withProperty("BRAVE_API_KEY", "brave-second-value"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.BRAVE);

        assertTrue(result.enabled());
        assertTrue(result.credentialPresent());
        assertEquals("brave-second-value", result.valueOrNull());
        assertEquals("BRAVE_API_KEY", result.sourceName());
        assertEquals(1, result.configuredAliasCount());
        assertFalse(result.duplicateAlias());
        assertEquals("brave", result.diagnostics().provider());
        assertFalse(result.diagnostics().toString().contains("brave-first-value"));
        assertFalse(result.diagnostics().toString().contains("brave-second-value"));
    }

    @Test
    void equalNaverCsvAndClientPairRemainEnabled() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("naver.keys", "naver-id:naver-secret")
                        .withProperty("NAVER_CLIENT_ID", "naver-id")
                        .withProperty("NAVER_CLIENT_SECRET", "naver-secret"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.NAVER);

        assertTrue(result.enabled());
        assertEquals("naver-id:naver-secret", result.valueOrNull());
        assertEquals("naver.keys", result.sourceName());
        assertEquals(2, result.configuredAliasCount());
        assertTrue(result.duplicateAlias());
        assertEquals("naver", result.diagnostics().provider());
    }

    @Test
    void differentNaverPairsDisableOnlyNaver() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment()
                        .withProperty("naver.client-id", "property-id")
                        .withProperty("naver.client-secret", "property-secret")
                        .withProperty("NAVER_CLIENT_ID", "environment-id")
                        .withProperty("NAVER_CLIENT_SECRET", "environment-secret")
                        .withProperty("GEMINI_API_KEY", "gemini-still-valid"));

        ProviderCredentialResolver.Resolution naver =
                resolver.resolve(ProviderCredentialResolver.Provider.NAVER);
        ProviderCredentialResolver.Resolution gemini =
                resolver.resolve(ProviderCredentialResolver.Provider.GEMINI);

        assertFalse(naver.enabled());
        assertEquals("conflicting-credential-aliases", naver.disabledReason());
        assertTrue(gemini.enabled());
    }

    @Test
    void optionalSearchProviderAliasesRemainSupported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("TAVILY_API_KEY", "tavily-value")
                .withProperty("search.serpapi.api-key", "serpapi-value");
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(environment);

        ProviderCredentialResolver.Resolution tavily =
                resolver.resolve(ProviderCredentialResolver.Provider.TAVILY);
        ProviderCredentialResolver.Resolution serpApi =
                resolver.resolve(ProviderCredentialResolver.Provider.SERPAPI);

        assertEquals("tavily-value", tavily.valueOrNull());
        assertEquals("TAVILY_API_KEY", tavily.sourceName());
        assertEquals("serpapi-value", serpApi.valueOrNull());
        assertEquals("search.serpapi.api-key", serpApi.sourceName());
    }

    @Test
    void localCompatibleOllamaSentinelRemainsUsable() {
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                new MockEnvironment().withProperty("llm.api-key", "ollama"));

        ProviderCredentialResolver.Resolution result =
                resolver.resolve(ProviderCredentialResolver.Provider.LOCAL_LLM);

        assertTrue(result.enabled());
        assertEquals("ollama", result.valueOrNull());
        assertEquals("local_llm", result.diagnostics().provider());
    }

    @Test
    void keyResolverCompatibilityAcceptsEqualGeminiAliases() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gemini.api-key", "gemini-equal-value")
                .withProperty("GEMINI_API_KEY", "gemini-equal-value");

        KeyResolver resolver = new KeyResolver(environment);

        assertEquals("gemini-equal-value", resolver.resolveGeminiApiKeyStrict());
    }

    @Test
    void keyResolverCompatibilityFailsGeminiConflictClosedWithoutThrowing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("gemini.api-key", "gemini-property-value")
                .withProperty("GEMINI_API_KEY", "gemini-environment-value");

        KeyResolver resolver = new KeyResolver(environment);

        assertNull(resolver.resolveGeminiApiKeyStrict());
    }

    @Test
    void keyResolverCompatibilityAcceptsEqualOpenAiAliases() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.api-key-openai", "openai-equal-value")
                .withProperty("OPENAI_API_KEY", "openai-equal-value");

        KeyResolver resolver = new KeyResolver(environment);

        assertEquals("openai-equal-value", resolver.resolveOpenAiApiKeyStrict());
    }

    @Test
    void keyResolverCompatibilityFailsOpenAiConflictClosed() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.api-key-openai", "openai-property-value")
                .withProperty("OPENAI_API_KEY", "openai-environment-value");

        KeyResolver resolver = new KeyResolver(environment);

        assertNull(resolver.resolveOpenAiApiKeyStrict());
    }

    @Test
    void keyResolverCompatibilityAcceptsEqualLocalAliases() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.api-key", "ollama")
                .withProperty("LLM_API_KEY", "ollama");

        KeyResolver resolver = new KeyResolver(environment);

        assertEquals("ollama", resolver.resolveLocalApiKeyStrict());
    }

    @Test
    void keyResolverCompatibilityFailsNaverConflictClosed() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("NAVER_KEYS", "environment-id:environment-secret")
                .withProperty("naver.client-id", "property-id")
                .withProperty("naver.client-secret", "property-secret");

        KeyResolver resolver = new KeyResolver(environment);

        assertEquals("", resolver.resolveNaverKeysCsvSafe());
    }
}
