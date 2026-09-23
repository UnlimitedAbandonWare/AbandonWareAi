package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardDebugTraceAspectPurityTest {
    private static final String QUERY = "Synthetic guard snapshot fixture.";

    @AfterEach
    void cleanContext() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false,NORMAL", "false,false,true,COMPRESSION",
            "false,true,true,STRIKE", "true,true,true,BYPASS"})
    void snapshotsPreserveCallerPlanAndReportObservedModes(
            boolean bypass, boolean strike, boolean compression, String expectedMode) throws Throwable {
        GuardContext ctx = context();
        ctx.setBypassMode(bypass);
        ctx.setStrikeMode(strike);
        ctx.setCompressionMode(compression);
        ctx.putPlanOverride("overdrive.enabled", true);
        Map<String, Object> before = new LinkedHashMap<>(ctx.getPlanOverrides());
        ProceedingJoinPoint pjp = joinPoint();
        when(pjp.proceed()).thenAnswer(call -> {
            assertEquals(before, ctx.getPlanOverrides(), "before snapshot must not normalize the caller plan");
            return "synthetic_result";
        });

        assertEquals("synthetic_result", aspect(true, null).aroundChatWorkflow(pjp));
        assertEquals(before, ctx.getPlanOverrides(), "finally snapshot must not apply another plan");
        assertEquals(bypass, ctx.isBypassMode());
        assertEquals(strike, ctx.isStrikeMode());
        assertEquals(compression, ctx.isCompressionMode());
        List<Map<String, Object>> snapshots = snapshots();
        assertEquals(2, snapshots.size());
        assertEquals(List.of("before", "after"), snapshots.stream().map(s -> s.get("phase")).toList());
        for (Map<String, Object> snapshot : snapshots) {
            assertEquals(expectedMode, snapshot.get("orch.mode"));
            assertFalse(snapshot.toString().contains(QUERY));
            assertTrue(snapshot.containsKey("queryHash"));
        }
        verify(pjp).proceed();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finallySnapshotPreservesBusinessWritesAndOriginalOutcome(boolean fail) throws Throwable {
        GuardContext ctx = context();
        Map<String, Object> expected = new LinkedHashMap<>(ctx.getPlanOverrides());
        expected.put("business.completed", true);
        RuntimeException businessFailure = new IllegalStateException("synthetic_business_failure");
        ProceedingJoinPoint pjp = joinPoint();
        when(pjp.proceed()).thenAnswer(call -> {
            ctx.putPlanOverride("business.completed", true);
            ctx.setBypassMode(true);
            ctx.setBypassReason("synthetic_business_reason");
            if (fail) throw businessFailure;
            return "synthetic_result";
        });
        GuardDebugTraceAspect aspect = aspect(true, null);

        if (fail) assertSame(businessFailure, assertThrows(IllegalStateException.class, () -> aspect.aroundChatWorkflow(pjp)));
        else assertEquals("synthetic_result", aspect.aroundChatWorkflow(pjp));
        assertEquals(expected, ctx.getPlanOverrides());
        assertTrue(ctx.isBypassMode());
        assertEquals("BYPASS", snapshots().get(1).get("orch.mode"));
        assertEquals("synthetic_business_reason", snapshots().get(1).get("orch.reason"));
        verify(pjp).proceed();
    }

    @Test
    void silentFailureObservationDoesNotRunRoutingOrPublishRoutingDecisions() throws Throwable {
        GuardContext ctx = context();
        ctx.setAuxHardDown(true);
        ctx.setIrregularityScore(.4);
        TraceStore.put("fixture.marker", true);
        Map<String, Object> before = new LinkedHashMap<>(ctx.getPlanOverrides());
        ProceedingJoinPoint pjp = joinPoint();
        when(pjp.proceed()).thenReturn("synthetic_result");

        assertEquals("synthetic_result", aspect(true, null).aroundChatWorkflow(pjp));
        assertEquals(before, ctx.getPlanOverrides());
        assertEquals(Set.of("fixture.marker", "orch.debug.snapshots"), TraceStore.getAll().keySet(),
                "diagnostics must not publish sampled escape or execution-plan decisions");
        assertEquals(Boolean.TRUE, snapshots().get(0).get("bypass.silentFailure"));
        assertEquals("NORMAL", snapshots().get(0).get("orch.mode"));
    }

    @Test
    void breakerObservationsDoNotReplaceTheCurrentContextMode() throws Throwable {
        context();
        NightmareBreaker breaker = mock(NightmareBreaker.class);
        when(breaker.isOpenOrHalfOpen(NightmareKeys.CHAT_DRAFT)).thenReturn(true);
        ProceedingJoinPoint pjp = joinPoint();
        when(pjp.proceed()).thenReturn("synthetic_result");

        assertEquals("synthetic_result", aspect(true, breaker).aroundChatWorkflow(pjp));
        assertEquals("NORMAL", snapshots().get(0).get("orch.mode"));
        assertEquals(Boolean.TRUE, snapshots().get(0).get("nb.chatOpenOrHalf"));
        assertEquals(Boolean.TRUE, snapshots().get(0).get("bypass.chatDown"));
        verify(breaker, never()).isAnyOpenPrefix(anyString());
    }

    @Test
    void disabledTracerPassesThroughWithoutSnapshotsOrContextChanges() throws Throwable {
        GuardContext ctx = context();
        Map<String, Object> before = new LinkedHashMap<>(ctx.getPlanOverrides());
        ProceedingJoinPoint pjp = joinPoint();
        when(pjp.proceed()).thenReturn("synthetic_result");

        assertEquals("synthetic_result", aspect(false, null).aroundChatWorkflow(pjp));
        assertEquals(before, ctx.getPlanOverrides());
        assertNull(TraceStore.get("orch.debug.snapshots"));
        verify(pjp).proceed();
        verify(pjp, never()).getArgs();
    }

    private static GuardContext context() {
        GuardContext ctx = new GuardContext();
        ctx.putPlanOverride("caller.explicit", true);
        GuardContextHolder.set(ctx);
        return ctx;
    }

    private static ProceedingJoinPoint joinPoint() {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {QUERY});
        return pjp;
    }

    @SuppressWarnings("unchecked")
    private static GuardDebugTraceAspect aspect(boolean enabled, NightmareBreaker breaker) {
        ObjectProvider<NightmareBreaker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(breaker);
        return new GuardDebugTraceAspect(new MockEnvironment()
                .withProperty("nova.orch.debug.guard-trace.enabled", String.valueOf(enabled)), provider);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> snapshots() {
        return (List<Map<String, Object>>) TraceStore.get("orch.debug.snapshots");
    }
}
