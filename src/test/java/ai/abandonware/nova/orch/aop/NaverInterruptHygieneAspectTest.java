package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaverInterruptHygieneAspectTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void naverInterruptHygieneDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/NaverInterruptHygieneAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Naver interrupt hygiene needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void handledNaverInterruptCatchesUseFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/NaverInterruptHygieneAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"caught.searchWithTraceSync\");"));
        assertTrue(source.contains("traceSuppressed(\"caught.searchSnippetsSync\");"));
    }

    @Test
    void searchWithTraceSwallowedInterruptedFailureUsesOperationalTraceLabel() throws Throwable {
        String tokenMarker = "owner" + "Token";
        NaverInterruptHygieneAspect aspect = new NaverInterruptHygieneAspect();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new InterruptedException("interrupted " + tokenMarker + "=secret"));

        Object result = aspect.aroundSearchWithTraceSync(pjp);

        assertInstanceOf(NaverSearchService.SearchResult.class, result);
        assertEquals(Boolean.TRUE, TraceStore.get("interrupt.cleaned"));
        assertEquals("interrupted", TraceStore.get("naver.interruptHygiene.swallowed.error"));
        assertEquals("INTERRUPTED", TraceStore.get("naver.interruptHygiene.swallowed.kind"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("InterruptedException"), trace);
        assertFalse(trace.contains(tokenMarker), trace);
    }

    @Test
    void searchSnippetsSwallowedInterruptedFailureUsesOperationalTraceLabel() throws Throwable {
        String tokenMarker = "owner" + "Token";
        NaverInterruptHygieneAspect aspect = new NaverInterruptHygieneAspect();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new InterruptedException("interrupted " + tokenMarker + "=secret"));

        Object result = aspect.aroundSearchSnippetsSync(pjp);

        assertEquals(List.of(), result);
        assertEquals(Boolean.TRUE, TraceStore.get("interrupt.cleaned"));
        assertEquals("interrupted", TraceStore.get("naver.interruptHygiene.swallowed.error"));
        assertEquals("INTERRUPTED", TraceStore.get("naver.interruptHygiene.swallowed.kind"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("InterruptedException"), trace);
        assertFalse(trace.contains(tokenMarker), trace);
    }

    @Test
    void swallowedFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/NaverInterruptHygieneAspect.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(t.getMessage(), 180)"));
        assertFalse(source.contains("String.valueOf(t)"));
        assertEquals(2, source.split("errorHash=\\{\\} errorLength=\\{\\}", -1).length - 1);
        assertEquals(2, source.split("SafeRedactor.hashValue\\(messageOf\\(t\\)\\)", -1).length - 1);
    }
}
