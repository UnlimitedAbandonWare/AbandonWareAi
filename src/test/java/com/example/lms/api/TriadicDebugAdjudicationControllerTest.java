package com.example.lms.api;

import com.example.lms.ensemble.EnsembleJudgeService;
import com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicator;
import com.example.lms.service.trace.DebugCopilotService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriadicDebugAdjudicationControllerTest {

    @Test
    void exposesReadOnlyLatestAndExplicitPostRunEndpoints() throws Exception {
        DebugCopilotService copilot = mock(DebugCopilotService.class);
        var result = new EvidenceGroundedTriadicDebugAdjudicator.Adjudication(
                EnsembleJudgeService.DebugPatchDecision.HOLD,
                EnsembleJudgeService.DebugPatchConfidence.LOW,
                "feature_disabled",
                "none",
                0,
                0,
                0.0d,
                0.0d,
                0,
                true);
        when(copilot.latestTriadicAdjudication()).thenReturn(result);
        when(copilot.adjudicateLatestPatchCandidate(any())).thenReturn(result);
        TriadicDebugAdjudicationController controller = new TriadicDebugAdjudicationController(copilot);
        var request = new TriadicDebugAdjudicationController.PatchCandidateRequest(
                "Keep the completed peer trace when a later worker fails.",
                java.util.List.of("main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java"),
                "a".repeat(64));

        assertEquals("HOLD", controller.latest().get("decision"));
        assertEquals("feature_disabled", controller.run(request).get("reasonCode"));
        var candidateCaptor = forClass(EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate.class);
        verify(copilot).adjudicateLatestPatchCandidate(candidateCaptor.capture());
        assertEquals("a".repeat(64), candidateCaptor.getValue().diffSha256());
        RequestMapping root = TriadicDebugAdjudicationController.class.getAnnotation(RequestMapping.class);
        assertNotNull(root);
        assertEquals("/api/diagnostics/debug/triadic-adjudication", root.value()[0]);
        Method latest = TriadicDebugAdjudicationController.class.getMethod("latest");
        Method run = TriadicDebugAdjudicationController.class.getMethod(
                "run", TriadicDebugAdjudicationController.PatchCandidateRequest.class);
        assertNotNull(latest.getAnnotation(GetMapping.class));
        assertNotNull(run.getAnnotation(PostMapping.class));
        RequestBody requestBody = run.getParameters()[0].getAnnotation(RequestBody.class);
        assertNotNull(requestBody);
        assertEquals(false, requestBody.required());
    }
}
