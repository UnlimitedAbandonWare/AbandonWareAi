package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.retriever.OcrRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPropertyResolutionCharacterizationTest {
    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    @Test
    void defaultLocalProfileEnablesGenericOcrButNotRagOcr() {
        verify(Map.of(), true, false);
    }

    @Test
    void dottedGenericPropertyDoesNotBecomeTheUppercaseEnvironmentFallback() {
        verify(Map.of(), true, false, "ocr.enabled=true");
    }

    @Test
    void dottedGenericDisableIsIndependentOfRagFallback() {
        verify(Map.of(), false, false, "ocr.enabled=false");
    }

    @Test
    void sharedEnvironmentEnableReachesBothKeysAndActualRagOwners() {
        verify(Map.of("OCR_ENABLED", "true"), true, true);
    }

    @Test
    void sharedEnvironmentDisableOverridesGenericFileDefault() {
        verify(Map.of("OCR_ENABLED", "false"), false, false);
    }

    @Test
    void specificRagEnvironmentEnableOverridesSharedDisable() {
        verify(Map.of("OCR_ENABLED", "false", "RAG_OCR_ENABLED", "true"), false, true);
    }

    @Test
    void specificRagEnvironmentDisableOverridesSharedEnable() {
        verify(Map.of("OCR_ENABLED", "true", "RAG_OCR_ENABLED", "false"), true, false);
    }

    @Test
    void explicitRagPropertyOverridesSharedEnvironmentFallback() {
        verify(Map.of("OCR_ENABLED", "false"), false, true, "rag.ocr.enabled=true");
    }

    @Test
    void devProfileAlsoKeepsGenericAndRagDefaultsSeparate() {
        verify(Map.of(), true, false, "spring.profiles.active=dev");
    }

    private static void verify(Map<String, Object> environment, boolean genericEnabled,
            boolean ragEnabled, String... properties) {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    // Model only the declared synthetic environment, without reading host credentials.
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource("auditSyntheticEnvironment", environment));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(Owners.class)
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("ocr.enabled", Boolean.class)).isEqualTo(genericEnabled);
                    assertThat(context.getEnvironment().getProperty("rag.ocr.enabled", Boolean.class)).isEqualTo(ragEnabled);
                    var retriever = context.getBean(OcrRetriever.class);
                    var chain = context.getBean(DynamicRetrievalHandlerChain.class);
                    assertThat(ReflectionTestUtils.getField(retriever, "enabled")).isEqualTo(ragEnabled);
                    assertThat(ReflectionTestUtils.getField(chain, "ocrEnabled")).isEqualTo(ragEnabled);
                    assertThat(ReflectionTestUtils.getField(chain, "ocrRetriever")).isSameAs(retriever);
                    assertThat(retriever.retrieve(new Query("synthetic OCR configuration probe"))).isEmpty();
                    assertThat(TraceStore.get("retrieval.dependency.ocr.status"))
                            .isEqualTo(ragEnabled ? "missing_delegate" : "disabled");
                    assertThat(TraceStore.get("retrieval.dependency.ocr.attempted")).isEqualTo(ragEnabled);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OcrRetriever.class)
    static class Owners {
        @Bean
        DynamicRetrievalHandlerChain chain() {
            return new DynamicRetrievalHandlerChain(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }

        @Bean(destroyMethod = "shutdown")
        ExecutorService ownedRecoveryExecutor(DynamicRetrievalHandlerChain chain) {
            return (ExecutorService) ReflectionTestUtils.getField(chain, "recoveryExecutor");
        }
    }
}
