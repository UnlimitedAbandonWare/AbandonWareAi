package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class AnchorTailTraceRedactionTest {

    private static final String RAW_ANCHOR = "privateAnchorSecretToken98765";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void guardrailAnchorTailAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                        "main/java/ai/abandonware/nova/orch/aop/GuardrailQueryPreprocessorAnchorTailAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Guardrail anchor-tail aspect needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void queryAnalysisAnchorTailTraceUsesHashOnlyAnchor() throws Throwable {
        ProceedingJoinPoint pjp = joinPointWithArgs(new Object[]{largeInput()});
        QueryAnalysisAnchorTailAspect aspect = new QueryAnalysisAnchorTailAspect(true, 512, 10, null);

        Object result = aspect.aroundAnalyze(pjp);

        assertEquals("ok", result);
        assertHashOnlyAnchor("nova.queryAnalysis.anchorTail");
    }

    @Test
    void queryTransformerAnchorTailTraceUsesHashOnlyAnchor() throws Throwable {
        // Keep this positive compression control free of an opaque exclusion clause.
        ProceedingJoinPoint pjp = joinPointWithArgs(new Object[]{largeInput()
                .replace("without leaking the private anchor", "with an actionable checklist")});
        QueryTransformerAnchorTailAspect aspect = new QueryTransformerAnchorTailAspect(true, 512, 10, null);

        Object result = aspect.aroundTransformEnhanced(pjp);

        assertEquals("ok", result);
        assertHashOnlyAnchor("nova.queryTransformer.anchorTail");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "site:dept.ac.kr after:2025-01-01 before:2025-12-31",
            "Java 17 LangChain4j 1.0.1",
            "Use internal documents only; exclude public documents",
            "A가 아니라 B 배포 방법",
            "A가 아니라 B, 한국어 자료만"
    })
    void queryTransformerDoesNotEraseLeadingSearchConstraints(String constraints) throws Throwable {
        String input = constraints + "\n" + "operational background filler ".repeat(180)
                + "\nPlease summarize the release changes?";
        ProceedingJoinPoint pjp = joinPointWithArgs(new Object[]{input});
        QueryTransformerAnchorTailAspect aspect = new QueryTransformerAnchorTailAspect(true, 256, 10, null);

        assertEquals("ok", aspect.aroundTransformEnhanced(pjp));

        verify(pjp).proceed();
        verify(pjp, never()).proceed(any(Object[].class));
        assertEquals("search_constraints_lost", TraceStore.get("nova.queryTransformer.anchorTail.skipped"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(constraints));
    }

    @Test
    void queryTransformerAnchorTailEarlyExitsLeaveSkippedBreadcrumbs() throws Throwable {
        assertQueryTransformerSkip(
                new QueryTransformerAnchorTailAspect(false, 512, 10, null),
                new Object[]{"prompt long enough for disabled branch"},
                "disabled");
        assertQueryTransformerSkip(
                new QueryTransformerAnchorTailAspect(true, 512, 10, null),
                new Object[]{42},
                "args_invalid");
        assertQueryTransformerSkip(
                new QueryTransformerAnchorTailAspect(true, 512, 50, null),
                new Object[]{"short"},
                "prompt_too_short");
        assertQueryTransformerSkip(
                new QueryTransformerAnchorTailAspect(true, 512, 0, null),
                new Object[]{"   "},
                "condensed_blank");
        assertQueryTransformerSkip(
                new QueryTransformerAnchorTailAspect(true, 512, 1, null),
                new Object[]{"long enough to trigger but shorter than max"},
                "not_shorter");
    }

    @Test
    void keywordSelectionAnchorTailTraceUsesHashOnlyAnchor() throws Throwable {
        ProceedingJoinPoint pjp = joinPointWithArgs(new Object[]{largeInput(), "general", 3});
        KeywordSelectionAnchorTailAspect aspect = new KeywordSelectionAnchorTailAspect(true, 512, 10, null);

        Object result = aspect.aroundSelect(pjp);

        assertEquals("ok", result);
        assertHashOnlyAnchor("nova.keywordSelection.anchorTail");
    }

    @Test
    void guardrailAnchorTailTraceUsesHashOnlyAnchor() throws Throwable {
        ProceedingJoinPoint pjp = joinPointWithArgs(new Object[]{largeInput()});
        when(pjp.getTarget()).thenReturn(null);
        GuardrailQueryPreprocessorAnchorTailAspect aspect =
                new GuardrailQueryPreprocessorAnchorTailAspect(true, 600, 80, 10, null);

        Object result = aspect.aroundEnrich(pjp);

        assertEquals("ok", result);
        assertHashOnlyAnchor("nova.guardrail.anchorTail");
    }

    private static ProceedingJoinPoint joinPointWithArgs(Object[] args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn("ok");
        when(pjp.proceed(any(Object[].class))).thenReturn("ok");
        return pjp;
    }

    private static void assertQueryTransformerSkip(
            QueryTransformerAnchorTailAspect aspect,
            Object[] args,
            String expectedReason) throws Throwable {
        TraceStore.clear();
        ProceedingJoinPoint pjp = joinPointWithArgs(args);

        Object result = aspect.aroundTransformEnhanced(pjp);

        assertEquals("ok", result);
        assertEquals(expectedReason, TraceStore.get("queryTransformer.anchorTail.skipped"));
        assertEquals(expectedReason, TraceStore.get("nova.queryTransformer.anchorTail.skipped"));
    }

    private static String largeInput() {
        return (RAW_ANCHOR + " filler ".repeat(80) + "\n").repeat(20)
                + "Please summarize the operational failure without leaking the private anchor.";
    }

    @SuppressWarnings("unchecked")
    private static void assertHashOnlyAnchor(String traceKey) {
        Object raw = TraceStore.get(traceKey);
        assertTrue(raw instanceof Map<?, ?>, String.valueOf(raw));
        Map<String, Object> trace = (Map<String, Object>) raw;
        assertFalse(trace.containsKey("anchor"));
        assertEquals(SafeRedactor.hashValue(RAW_ANCHOR), trace.get("anchorHash"));
        assertEquals(RAW_ANCHOR.length(), trace.get("anchorLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(RAW_ANCHOR));
    }
}
