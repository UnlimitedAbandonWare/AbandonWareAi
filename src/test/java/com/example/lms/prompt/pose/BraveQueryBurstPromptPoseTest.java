package com.example.lms.prompt.pose;

import ai.abandonware.nova.orch.aop.BraveQueryBurstAspect;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BraveQueryBurstPromptPoseTest {

    @BeforeEach
    void clearContextBefore() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void clearContextAfter() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void poseCapCannotExceedGlobalCapAndSeedsStayHashed() throws Throwable {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of("private raw seed"), 1, 2, 1,
                Map.of(), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "abc123abc123", "ko", "general", 10));

        MockEnvironment env = new MockEnvironment()
                .withProperty("nova.orch.brave-query-burst.min", "1")
                .withProperty("nova.orch.brave-query-burst.max", "18")
                .withProperty("nova.orch.brave-query-burst.cap", "4");
        BraveQueryBurstAspect aspect = new BraveQueryBurstAspect(env, null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] { "root query", null, 8 });
        when(pjp.proceed()).thenReturn(List.of("root query", "second query", "third query"));

        Object out = aspect.expandQueriesInBraveMode(pjp);

        assertTrue(out instanceof List<?>);
        assertTrue(((List<?>) out).size() <= 2);
        assertFalse(String.valueOf(TraceStore.get(PromptPoseTrace.QUERY_BURST_SEED_HASHES)).contains("private raw seed"));
        assertTrue(String.valueOf(TraceStore.get(PromptPoseTrace.QUERY_BURST_SEED_HASHES)).matches(".*[0-9a-f]{12}.*"));
    }

    @Test
    void seedHashesAloneDoNotEnableQueryBurst() throws Throwable {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of("seed-only candidate"), 0, 0, 0,
                Map.of(), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "abc123abc123", "ko", "general", 10));

        BraveQueryBurstAspect aspect = new BraveQueryBurstAspect(new MockEnvironment(), null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(List.of("base query"));

        Object out = aspect.expandQueriesInBraveMode(pjp);

        assertEquals(List.of("base query"), out);
        assertNull(TraceStore.get("web.brave.queryBurst.promptPose.capApplied"));
        assertNull(TraceStore.get("web.brave.queryBurst.promptPose.seedHashCount"));
    }
}
