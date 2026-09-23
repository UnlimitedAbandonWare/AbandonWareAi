package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlanMisrouteHatchAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void ap1MisrouteTraceStoresQueryDiagnosticOnly() throws Throwable {
        String rawQuery = "Gemini API official private-plan-misroute-query";
        WorkflowPlanMisrouteHatchAspect aspect = new WorkflowPlanMisrouteHatchAspect(
                new MockEnvironment().withProperty("plans.auto-select.safe", "safe_autorun.v1"));
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ap1_auth_web.v1");
        when(pjp.getArgs()).thenReturn(new Object[]{rawQuery});

        Object result = aspect.aroundEnsurePlanSelected(pjp);

        assertEquals("safe_autorun.v1", result);
        assertFalse(TraceStore.getAll().containsKey("plan.hatch.ap1Misroute.query"),
                String.valueOf(TraceStore.getAll()));
        assertTrue(String.valueOf(TraceStore.get("plan.hatch.ap1Misroute.queryHash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("plan.hatch.ap1Misroute.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("private-plan-misroute-query"), trace);
    }

    @Test
    void workflowPlanMisrouteHatchAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/WorkflowPlanMisrouteHatchAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "workflow plan misroute hatch needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }
}
