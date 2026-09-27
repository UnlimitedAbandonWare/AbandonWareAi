package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Characterizes current opt-in composition; does not define a new command grammar. */
class Zero100ActivationCharacterizationTest {
    @ParameterizedTest
    @CsvSource(value = {
            "master-off|false|true|zero100.v1|zero100|false",
            "ordinary|true|none|none|ordinary catalogue request|false",
            "explicit-true|true|true|none|ordinary catalogue request|true",
            "explicit-false|true|false|none|ordinary catalogue request|false",
            "plan-id|true|none|zero100.v1|ordinary catalogue request|true",
            "false-plus-plan|true|false|zero100.v1|ordinary catalogue request|true",
            "plain-marker|true|none|none|zero100|true",
            "quoted-marker|true|none|none|The document quotes [zero100] as a label.|true",
            "negated-marker|true|none|none|Do not activate zero100 for this request.|true",
            "false-plus-marker|true|false|none|zero100|true",
            "embedded-marker|true|none|none|prefixzero100suffix|true",
            "case-normalized|true|none|none|EMPEROR TIME|true"
    }, delimiter = '|')
    void recordsActualEntryActivationRegistryAndHintCounts(String id, boolean engine,
            String override, String planId, String text, boolean enabled) throws Throwable {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        GuardContext previousGuard = GuardContextHolder.get();
        TraceStore.clear();
        try {
            MDC.clear();
            String sid = "activation-fixture-" + id;
            MDC.put("sessionId", sid);
            GuardContext guard = GuardContext.defaultContext();
            guard.setUserQuery(text);
            if (!override.equals("none")) guard.putPlanOverride("search.zero100.enabled", Boolean.valueOf(override));
            if (!planId.equals("none")) guard.setPlanId(planId);
            GuardContextHolder.set(guard);

            Zero100EngineProperties props = new Zero100EngineProperties();
            props.setEngineEnabled(engine);
            Zero100SessionRegistry registry = spy(new Zero100SessionRegistry(props));
            Zero100SessionAspect aspect = new Zero100SessionAspect(props, registry, new MockEnvironment());
            ProceedingJoinPoint entry = mock(ProceedingJoinPoint.class);
            when(entry.getArgs()).thenReturn(new Object[]{ChatRequestDto.builder().message(text).build()});
            when(entry.proceed()).thenReturn("fixture-complete");

            assertEquals("fixture-complete", aspect.aroundChatEntry(entry));
            verify(entry, times(1)).proceed();
            assertEquals(enabled, Boolean.TRUE.equals(TraceStore.get("zero100.enabled")));
            assertEquals(enabled, registry.isActive(sid));
            verify(registry, times(enabled ? 1 : 0)).touch(eq(sid), eq(text),
                    anyLong(), anyLong(), anyLong(), anyLong());
            verify(registry, times(enabled ? 1 : 0)).recordBudgetFeedback(eq(sid), anyString(), any());
            assertNull(TraceStore.get("zero100.scheduler.failureClass"));
            Object ratios = TraceStore.get("zero100.branch.callRatios");
            Object timeboxes = TraceStore.get("zero100.branch.timeboxMs");
            if (enabled) {
                assertEquals(3, assertInstanceOf(Map.class, ratios).size());
                assertEquals(3, assertInstanceOf(Map.class, timeboxes).size());
                assertNotNull(TraceStore.get("zero100.activeLane"));
            } else {
                assertNull(ratios);
                assertNull(timeboxes);
                assertNull(TraceStore.get("zero100.activeLane"));
            }
            // These are scheduler projections, not evidence of launched provider jobs.
            assertFalse(TraceStore.getAll().containsValue(text));
        } finally {
            TraceStore.clear();
            if (previousGuard == null) GuardContextHolder.clear(); else GuardContextHolder.set(previousGuard);
            if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
        }
    }
}
