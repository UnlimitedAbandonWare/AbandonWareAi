package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptPoseRoutingPlanAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledModeProceedsUnchanged() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        PromptPoseRoutingPlanAspect aspect = new PromptPoseRoutingPlanAspect(props, null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(List.of("base"));

        Object out = aspect.aroundRoutingPlan(pjp);

        assertEquals(List.of("base"), out);
        verify(pjp).proceed();
        verify(pjp, never()).proceed(any(Object[].class));
    }

    @Test
    void enabledModeMergesSanitizedDraftBeforeRoutingPlanCacheKey() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseOrchestrator orchestrator = new PromptPoseOrchestrator(props, null, null) {
            @Override
            public PromptPosePlan plan(String finalQuery, int requestedMaxQueries) {
                return new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                        List.of("prefer official sources"), List.of(), 1, 2, 1,
                        Map.of(), 0.0d, 0.0d, 0, 0.8d, "ok");
            }
        };
        PromptPoseRoutingPlanAspect aspect = new PromptPoseRoutingPlanAspect(props, orchestrator);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "question", "existing draft", 5 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(inv -> {
            Object[] args = inv.getArgument(0);
            assertEquals("question", args[0]);
            assertTrue(String.valueOf(args[1]).contains("existing draft"));
            assertTrue(String.valueOf(args[1]).contains("prefer official sources"));
            assertEquals(2, args[2]);
            return List.of("planned");
        });

        Object out = aspect.aroundRoutingPlan(pjp);

        assertEquals(List.of("planned"), out);
        verify(pjp).proceed(any(Object[].class));
    }

    @Test
    void seedOnlyPlanMergesSearchSeedHintsWithoutRawSeedTrace() throws Throwable {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        PromptPoseOrchestrator orchestrator = new PromptPoseOrchestrator(props, null, null) {
            @Override
            public PromptPosePlan plan(String finalQuery, int requestedMaxQueries) {
                PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                        List.of(), List.of("official docs seed"), 0, 0, 0,
                        Map.of(), 0.0d, 0.0d, 0, 0.7d, "ok");
                PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                        false, "", "preview", "abc123abc123", "ko", "general", 10));
                return plan;
            }
        };
        PromptPoseRoutingPlanAspect aspect = new PromptPoseRoutingPlanAspect(props, orchestrator);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "question", "", 12 });
        when(pjp.proceed(any(Object[].class))).thenAnswer(inv -> {
            Object[] args = inv.getArgument(0);
            assertTrue(String.valueOf(args[1]).contains("PromptPose search seed hints:"));
            assertTrue(String.valueOf(args[1]).contains("\"official docs seed\""));
            assertEquals(12, args[2]);
            return List.of("planned");
        });

        Object out = aspect.aroundRoutingPlan(pjp);

        assertEquals(List.of("planned"), out);
        assertTrue(String.valueOf(TraceStore.get(PromptPoseTrace.QUERY_BURST_SEED_HASHES)).matches(".*[0-9a-f]{12}.*"));
        assertTrue(TraceStore.getAll().values().stream()
                .noneMatch(value -> String.valueOf(value).contains("official docs seed")));
        verify(pjp).proceed(any(Object[].class));
    }
}
