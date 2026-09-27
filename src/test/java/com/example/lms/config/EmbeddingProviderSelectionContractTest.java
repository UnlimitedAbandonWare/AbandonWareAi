package com.example.lms.config;

import com.example.lms.service.embedding.OllamaEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingProviderSelectionContractTest {
    @Test void legacyAliasAndFingerprintAgreeAndConflictsFail() throws Exception {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("embeddings.provider", " NONE ");
        var config = new LangChainConfig(null, null);
        for (String name : new String[]{"embeddingProvider", "legacyEmbeddingProvider"}) {
            var field = LangChainConfig.class.getDeclaredField(name);
            String expression = field.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
            ReflectionTestUtils.setField(config, name, environment.resolveRequiredPlaceholders(expression));
        }
        ReflectionTestUtils.setField(config, "embeddingDimensions", 2);
        var ollama = mock(OllamaEmbeddingModel.class);
        var decorated = config.embeddingModel(ollama, null);
        assertInstanceOf(com.example.lms.llm.NoopEmbeddingModel.class,
                ReflectionTestUtils.getField(decorated, "delegate"));
        var fp = new com.example.lms.vector.EmbeddingFingerprint();
        ReflectionTestUtils.setField(fp, "provider", "");
        ReflectionTestUtils.setField(fp, "legacyProvider", "NONE");
        assertEquals("none", fp.provider());
        ReflectionTestUtils.setField(config, "embeddingProvider", "ollama");
        var failure = assertThrows(IllegalArgumentException.class, () -> config.embeddingModel(ollama, fp));
        assertEquals("embedding_provider_conflict", failure.getMessage());
        verifyNoInteractions(ollama);
    }
    @Test void legacyOpenAiDisablesTheUnselectedLocalDelegate() {
        var local = new OllamaEmbeddingModel(org.springframework.web.reactive.function.client.WebClient.create());
        ReflectionTestUtils.setField(local, "provider", "");
        ReflectionTestUtils.setField(local, "legacyProvider", "openai");
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(local, "isOllamaProvider"));
    }
    @ParameterizedTest @ValueSource(strings = {"hf", "unrecognized"})
    void configuredUnsupportedProviderNeverSilentlySelectsOllama(String provider) {
        var config = new LangChainConfig(null, null);
        ReflectionTestUtils.setField(config, "embeddingProvider", provider);
        var ollama = mock(OllamaEmbeddingModel.class);
        assertThrows(IllegalArgumentException.class, () -> config.embeddingModel(ollama, null));
        verifyNoInteractions(ollama);
    }
    @Test void explicitOllamaKeepsDecoratorAndDelegate() {
        var config = new LangChainConfig(null, null);
        ReflectionTestUtils.setField(config, "embeddingProvider", " OLLAMA ");
        var ollama = mock(OllamaEmbeddingModel.class);
        var decorated = config.embeddingModel(ollama, null);
        assertNotSame(ollama, decorated);
        assertSame(ollama, ReflectionTestUtils.getField(decorated, "delegate"));
    }
}
