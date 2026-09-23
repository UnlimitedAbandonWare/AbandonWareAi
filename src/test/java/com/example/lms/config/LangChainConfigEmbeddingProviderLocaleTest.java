package com.example.lms.config;

import com.example.lms.service.embedding.OllamaEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class LangChainConfigEmbeddingProviderLocaleTest {

    @Test
    void configuredOpenAiProviderIsLocaleStable() {
        OllamaEmbeddingModel ollama = mock(OllamaEmbeddingModel.class);

        assertAll(
                () -> assertInstanceOf(OpenAiEmbeddingModel.class,
                        delegateFor("OPENAI", Locale.forLanguageTag("tr-TR"), ollama)),
                () -> assertInstanceOf(OpenAiEmbeddingModel.class,
                        delegateFor("openai", Locale.forLanguageTag("tr-TR"), ollama)),
                () -> assertInstanceOf(OpenAiEmbeddingModel.class,
                        delegateFor("OPENAI", Locale.US, ollama)),
                () -> org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> delegateFor("CUSTOM", Locale.forLanguageTag("tr-TR"), ollama)));
    }

    private static EmbeddingModel delegateFor(
            String provider,
            Locale locale,
            OllamaEmbeddingModel ollama) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            LangChainConfig config = new LangChainConfig(null, null);
            ReflectionTestUtils.setField(config, "embeddingProvider", provider);
            ReflectionTestUtils.setField(config, "openAiKey", "unit-non-secret-credential");
            ReflectionTestUtils.setField(config, "embeddingModelName", "text-embedding-3-small");
            ReflectionTestUtils.setField(config, "openAiTimeoutSec", 30L);
            ReflectionTestUtils.setField(config, "embeddingDimensions", 1536);

            EmbeddingModel decorated = config.embeddingModel(ollama, null);
            return (EmbeddingModel) ReflectionTestUtils.getField(decorated, "delegate");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
