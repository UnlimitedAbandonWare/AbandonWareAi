package com.example.lms.guard;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeyResolverProviderKeyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void groqResolverDoesNotUseGenericLocalKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key", "sk-local");

        KeyResolver resolver = new KeyResolver(env);

        assertNull(resolver.resolveGroqApiKeyStrict());
    }

    @Test
    void externalResolversTreatPlaceholdersAsMissing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key-openai", "ollama")
                .withProperty("llm.groq.api-key", "dummy")
                .withProperty("llm.cerebras.api-key", "${CEREBRAS_API_KEY:}")
                .withProperty("llm.openrouter.api-key", "changeme")
                .withProperty("llm.opencode.api-key", "test");

        KeyResolver resolver = new KeyResolver(env);

        assertNull(resolver.resolveOpenAiApiKeyStrict());
        assertNull(resolver.resolveGroqApiKeyStrict());
        assertNull(resolver.resolveCerebrasApiKeyStrict());
        assertNull(resolver.resolveOpenRouterApiKeyStrict());
        assertNull(resolver.resolveOpenCodeApiKeyStrict());
    }

    @Test
    void localResolverAcceptsOllamaPlaceholder() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key", "ollama");

        KeyResolver resolver = new KeyResolver(env);

        assertEquals("ollama", resolver.resolveLocalApiKeyStrict());
    }

    @Test
    void localResolverTreatsSkLocalAsMissingAndUsesEnvKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.api-key", "sk-local")
                .withProperty("LLM_API_KEY", "local_real_key");

        KeyResolver resolver = new KeyResolver(env);

        assertEquals("local_real_key", resolver.resolveLocalApiKeyStrict());
    }

    @Test
    void externalPlaceholderDoesNotConflictWithRealEnvKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.groq.api-key", "dummy")
                .withProperty("GROQ_API_KEY", "gsk_real_provider_key");

        KeyResolver resolver = new KeyResolver(env);

        assertEquals("gsk_real_provider_key", resolver.resolveGroqApiKeyStrict());
    }

    @Test
    void groqResolverUsesProviderSpecificProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.groq.api-key", "gsk_test_provider_key");

        KeyResolver resolver = new KeyResolver(env);

        assertEquals("gsk_test_provider_key", resolver.resolveGroqApiKeyStrict());
    }

    @Test
    void cerebrasResolverRejectsDuplicateSources() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.cerebras.api-key", "csk_property")
                .withProperty("CEREBRAS_API_KEY", "csk_env");

        KeyResolver resolver = new KeyResolver(env);

        assertThrows(IllegalStateException.class, resolver::resolveCerebrasApiKeyStrict);
    }

    @Test
    void openRouterResolverRejectsDuplicateSources() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.openrouter.api-key", "sk-or-property")
                .withProperty("OPENROUTER_API_KEY", "sk-or-env");

        KeyResolver resolver = new KeyResolver(env);

        assertThrows(IllegalStateException.class, resolver::resolveOpenRouterApiKeyStrict);
    }

    @Test
    void openCodeResolverUsesProviderSpecificProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.opencode.api-key", "ock_test_provider_key");

        KeyResolver resolver = new KeyResolver(env);

        assertEquals("ock_test_provider_key", resolver.resolveOpenCodeApiKeyStrict());
    }

    @Test
    void openCodeResolverRejectsDuplicateSources() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("llm.opencode.api-key", "ock_property")
                .withProperty("OPENCODE_API_KEY", "ock_env");

        KeyResolver resolver = new KeyResolver(env);

        assertThrows(IllegalStateException.class, resolver::resolveOpenCodeApiKeyStrict);
    }

    @Test
    void naverResolverUsesThreeStepLadderAndPublishesOnlyPresenceTrace() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("naver.keys", "dummy")
                .withProperty("NAVER_KEYS", "env-id:env-secret")
                .withProperty("naver.client-id", "env-id")
                .withProperty("naver.client-secret", "env-secret");

        KeyResolver resolver = new KeyResolver(env);
        Method method = KeyResolver.class.getMethod("resolveNaverKeysCsvSafe");

        assertEquals("env-id:env-secret", method.invoke(resolver));
        assertEquals("NAVER_KEYS", TraceStore.get("naver.cred.sourceName"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.cred.keysPresent"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.cred.clientPairPresent"));
        assertEquals(1, TraceStore.get("naver.cred.parsedCount"));
        assertEquals("NAVER_KEYS", TraceStore.get("naver.credential.sourceName"));
        assertEquals("NAVER_KEYS", TraceStore.get("naver.credential.source"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.credential.keysPresent"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.credential.clientPairPresent"));
        assertEquals(1, TraceStore.get("naver.credential.parsedCount"));
        assertEquals("", TraceStore.get("naver.credential.disabledReason"));
        assertEquals("NAVER_KEYS", TraceStore.get("naver.sourceName"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.keysPresent"));
        assertEquals(Boolean.TRUE, TraceStore.get("naver.clientPairPresent"));
        assertEquals(1, TraceStore.get("naver.parsedCount"));
        assertEquals("", TraceStore.get("naver.disabledReason"));
        assertNull(TraceStore.get("naver.cred.raw"));
        assertNull(TraceStore.get("naver.cred.secret"));
    }

    @Test
    void naverResolverMissingKeysPublishesDisabledReason() {
        KeyResolver resolver = new KeyResolver(new MockEnvironment());

        assertEquals("", resolver.resolveNaverKeysCsvSafe());
        assertEquals("none", TraceStore.get("naver.cred.sourceName"));
        assertEquals(Boolean.FALSE, TraceStore.get("naver.cred.keysPresent"));
        assertEquals(0, TraceStore.get("naver.cred.parsedCount"));
        assertEquals("no_valid_keys_found", TraceStore.get("naver.cred.disabledReason"));
        assertEquals("none", TraceStore.get("naver.credential.sourceName"));
        assertEquals("none", TraceStore.get("naver.credential.source"));
        assertEquals(Boolean.FALSE, TraceStore.get("naver.credential.keysPresent"));
        assertEquals(Boolean.FALSE, TraceStore.get("naver.credential.clientPairPresent"));
        assertEquals(0, TraceStore.get("naver.credential.parsedCount"));
        assertEquals("no_valid_keys_found", TraceStore.get("naver.credential.disabledReason"));
        assertEquals("none", TraceStore.get("naver.sourceName"));
        assertEquals(Boolean.FALSE, TraceStore.get("naver.keysPresent"));
        assertEquals(0, TraceStore.get("naver.parsedCount"));
        assertEquals("no_valid_keys_found", TraceStore.get("naver.disabledReason"));
    }

    @Test
    void naverResolverProviderFailurePublishesStableReasonOnly() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KeyResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(
                new BeanCreationException("keyResolver", "provider unavailable api_key=redacted-test-token"));

        assertEquals("", KeyResolver.resolveNaverKeysCsvSafe(provider));
        assertEquals("key_resolver_unavailable", TraceStore.get("naver.cred.keyResolver.failureClass"));
        assertEquals("key_resolver_unavailable", TraceStore.get("naver.cred.disabledReason"));
        assertEquals("key_resolver_unavailable", TraceStore.get("naver.credential.disabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("naver.keysPresent"));
        assertEquals("key_resolver_unavailable", TraceStore.get("naver.disabledReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("BeanCreationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("redacted-test-token"));
    }
}
