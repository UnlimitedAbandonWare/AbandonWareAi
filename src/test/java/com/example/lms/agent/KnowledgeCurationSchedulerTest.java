package com.example.lms.agent;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.vector.VectorBackendHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeCurationSchedulerTest {

    @Test
    void skipsSingleCodepointEntityBeforeSynthesis() {
        CuriosityTriggerService curiosity = mock(CuriosityTriggerService.class);
        SynthesisService synthesis = mock(SynthesisService.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        KnowledgeCurationScheduler scheduler = scheduler(curiosity, synthesis, knowledgeBase);

        when(curiosity.findKnowledgeGap()).thenReturn(Optional.of(
                new CuriosityTriggerService.KnowledgeGap("desc", "query", "GENERAL", "e")));

        scheduler.runCycle();

        verify(curiosity).findKnowledgeGap();
        verifyNoInteractions(synthesis, knowledgeBase);
    }

    @Test
    void skipsWhenEmbeddingProbeFails() {
        CuriosityTriggerService curiosity = mock(CuriosityTriggerService.class);
        SynthesisService synthesis = mock(SynthesisService.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        VectorBackendHealthService health = mock(VectorBackendHealthService.class);
        KnowledgeCurationScheduler scheduler = scheduler(curiosity, synthesis, knowledgeBase);
        ReflectionTestUtils.setField(scheduler, "vectorBackendHealth", health);

        when(curiosity.findKnowledgeGap()).thenReturn(Optional.of(
                new CuriosityTriggerService.KnowledgeGap("desc", "query", "GENERAL", "Qwen")));
        when(health.probeEmbeddingOnlyNow()).thenReturn(false);

        scheduler.runCycle();

        verify(health).probeEmbeddingOnlyNow();
        verifyNoInteractions(synthesis, knowledgeBase);
    }

    @Test
    void integratesWhenEntityAndEmbeddingProbePass() {
        CuriosityTriggerService curiosity = mock(CuriosityTriggerService.class);
        SynthesisService synthesis = mock(SynthesisService.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        VectorBackendHealthService health = mock(VectorBackendHealthService.class);
        KnowledgeCurationScheduler scheduler = scheduler(curiosity, synthesis, knowledgeBase);
        ReflectionTestUtils.setField(scheduler, "vectorBackendHealth", health);

        VerifiedKnowledge verified = new VerifiedKnowledge(
                "GENERAL", "Qwen", "{\"entity\":\"Qwen\"}", List.of("https://example.com"), 0.82);

        when(curiosity.findKnowledgeGap()).thenReturn(Optional.of(
                new CuriosityTriggerService.KnowledgeGap("desc", "query", "GENERAL", " Qwen ")));
        when(health.probeEmbeddingOnlyNow()).thenReturn(true);
        when(synthesis.synthesizeAndVerify(anyList(), any())).thenReturn(Optional.of(verified));
        when(knowledgeBase.integrateVerifiedKnowledge(anyString(), anyString(), anyString(), anyList(), anyDouble()))
                .thenReturn(KnowledgeBaseService.IntegrationStatus.CREATED);

        scheduler.runCycle();

        verify(health).probeEmbeddingOnlyNow();
        verify(synthesis).synthesizeAndVerify(anyList(), any());
        verify(knowledgeBase).integrateVerifiedKnowledge(
                "GENERAL", "Qwen", "{\"entity\":\"Qwen\"}", List.of("https://example.com"), 0.82);
    }

    private static KnowledgeCurationScheduler scheduler(CuriosityTriggerService curiosity,
                                                        SynthesisService synthesis,
                                                        KnowledgeBaseService knowledgeBase) {
        KnowledgeCurationScheduler scheduler = new KnowledgeCurationScheduler(curiosity, synthesis, knowledgeBase);
        ReflectionTestUtils.setField(scheduler, "minEntityCodepoints", 2);
        ReflectionTestUtils.setField(scheduler, "blockedEntitiesCsv", "e,unknown,n/a,na,none,null");
        return scheduler;
    }
}
