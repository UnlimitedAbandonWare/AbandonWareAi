package com.example.lms.service.guard;

import com.example.lms.service.guard.EvidenceAwareGuard.DraftQuality;
import com.example.lms.service.guard.EvidenceAwareGuard.EvidenceStrength;
import com.example.lms.service.guard.EvidenceAwareGuard.GuardAction;
import com.example.lms.service.guard.EvidenceAwareGuard.GuardDecision;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardPipelineTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void delegatesToEvidenceAwareGuardBeforeReturningFinalDraft() {
        EvidenceAwareGuard guard = mock(EvidenceAwareGuard.class);
        when(guard.guardWithEvidence(eq("draft"), anyList(), anyInt()))
                .thenReturn(new GuardDecision(
                        "guarded final",
                        false,
                        false,
                        false,
                        false,
                        0.92,
                        EvidenceStrength.STRONG,
                        DraftQuality.OK,
                        GuardAction.ALLOW,
                        List.of(),
                        false
                ));

        GuardPipeline pipeline = new GuardPipeline(guard);

        assertThat(pipeline.guardOrRegenerate("draft", List.of(), signal -> null, null, 2))
                .isEqualTo("guarded final");
    }

    @Test
    void failsSoftToSanitizedDraftWhenGuardThrows() {
        EvidenceAwareGuard guard = mock(EvidenceAwareGuard.class);
        when(guard.guardWithEvidence(eq("[redacted-email]"), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        GuardPipeline pipeline = new GuardPipeline(guard);

        assertThat(pipeline.guardOrRegenerate("secret@example.com", List.of(), signal -> null, null, 0))
                .isEqualTo("[redacted-email]");
        assertThat(TraceStore.get("guard.pipeline.disabledReason")).isEqualTo("guard_exception");
        assertThat(TraceStore.get("guard.pipeline.errorType")).isEqualTo("guard_pipeline_failed");
        assertThat(String.valueOf(TraceStore.get("guard.pipeline.errorType")))
                .doesNotContain("IllegalStateException");
    }
}
