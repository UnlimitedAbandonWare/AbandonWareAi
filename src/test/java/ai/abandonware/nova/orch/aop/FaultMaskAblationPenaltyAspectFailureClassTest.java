package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultMaskAblationPenaltyAspectFailureClassTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void recordsDistinctFailureClassesFromSameFaultmaskStage() throws Throwable {
        FaultMaskAblationPenaltyAspect aspect = new FaultMaskAblationPenaltyAspect(new MockEnvironment());
        TraceStore.put("uaw.autolearn", true);

        record(aspect, "websearch", "provider timed out after budget");
        record(aspect, "websearch", "request cancelled by caller");
        record(aspect, "websearch", "HTTP 429 rate limit");
        record(aspect, "websearch", "provider disabled because key is missing");
        record(aspect, "websearch", "zero-result-after-filter after strict filter");

        Object penaltiesObj = TraceStore.get("ablation.penalties");
        List<?> penalties = assertInstanceOf(List.class, penaltiesObj);
        Set<String> guards = penalties.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(p -> String.valueOf(p.get("guard")))
                .collect(Collectors.toSet());

        assertTrue(guards.contains("timeout"), String.valueOf(penalties));
        assertTrue(guards.contains("cancelled"), String.valueOf(penalties));
        assertTrue(guards.contains("rate-limit"), String.valueOf(penalties));
        assertTrue(guards.contains("provider-disabled"), String.valueOf(penalties));
        assertTrue(guards.contains("zero-result-after-filter"), String.valueOf(penalties));
        assertEquals("zero-result-after-filter", TraceStore.get("uaw.faultmask.lastFailureClass"));
    }

    private static void record(FaultMaskAblationPenaltyAspect aspect, String stage, String note) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {stage, 1.0d, "ctx", note});
        when(pjp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.aroundFaultmaskRecord(pjp));
    }
}
