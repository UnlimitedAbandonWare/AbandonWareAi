package com.example.lms.service;

import com.example.lms.domain.TranslationSample;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.domain.enums.TranslationRoute;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.repository.ConfigRepository;
import com.example.lms.repository.MemoryRepository;
import com.example.lms.repository.SampleRepository;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.ml.BanditSelector;
import com.example.lms.service.ml.PerformanceMetricService;
import com.example.lms.util.TextSimilarityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdaptiveTranslationServiceTest {
    @Mock RuleEngine rules;
    @Mock MemoryRepository memories;
    @Mock SampleRepository samples;
    @Mock GeminiClient gemini;
    @Mock QualityMetricService quality;
    @Mock TextSimilarityUtil similarity;
    @Mock BanditSelector bandit;
    @Mock PerformanceMetricService metrics;
    @Mock HyperparameterService params;
    @Mock ConfigRepository config;
    @InjectMocks AdaptiveTranslationService service;

    @BeforeEach
    void preserveRuleBoundary() {
        when(rules.apply(anyString(), anyString(), any(RulePhase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void memoryMissUsesGeminiAndRecordsItsRoute() {
        noMemory();
        when(gemini.translate("hello", "en", "ko")).thenReturn(Mono.just("안녕하세요"));

        assertEquals("안녕하세요", service.translate("hello", "en", "ko").block(Duration.ofSeconds(3)));
        assertSample("안녕하세요", TranslationRoute.GEMINI);
    }

    @Test
    void acceptedMemoryPreservesTextWithoutProviderCall() {
        TranslationMemory memory = new TranslationMemory();
        memory.setCorrected("저장된 번역");
        memory.setCosineSimilarity(1.0);
        when(memories.findBySourceHash(anyString())).thenReturn(Optional.of(memory));
        when(bandit.decideWithBoltzmann(memory)).thenReturn(true);

        assertEquals("저장된 번역", service.translate("hello", "en", "ko").block(Duration.ofSeconds(3)));
        assertSample("저장된 번역", TranslationRoute.MEMORY);
        verifyNoInteractions(gemini);
    }

    @Test
    void emptyProviderResultPreservesOriginalWithFailedRoute() {
        noMemory();
        when(gemini.translate("hello", "en", "ko")).thenReturn(Mono.empty());

        assertEquals("hello", service.translate("hello", "en", "ko").block(Duration.ofSeconds(3)));
        assertSample("hello", TranslationRoute.FAILED);
    }

    private void noMemory() {
        when(memories.findBySourceHash(anyString())).thenReturn(Optional.empty());
        when(memories.findAll()).thenReturn(List.of());
    }

    private void assertSample(String text, TranslationRoute route) {
        ArgumentCaptor<TranslationSample> saved = ArgumentCaptor.forClass(TranslationSample.class);
        verify(samples).save(saved.capture());
        assertEquals(text, saved.getValue().getTranslated());
        assertEquals(route, saved.getValue().getRoute());
        assertEquals("en", saved.getValue().getSrcLang());
        assertEquals("ko", saved.getValue().getTgtLang());
    }
}
