package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceListSnippetFallbackAspectProceedOnceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void ensureCoverageDoesNotRetryOriginalProceedWhenDelegateFails() throws Throwable {
        EvidenceListSnippetFallbackAspect aspect =
                new EvidenceListSnippetFallbackAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        RuntimeException targetFailure = new RuntimeException("target failed");

        when(pjp.getArgs()).thenReturn(new Object[] {
                "answer",
                List.of(urlOnlyDoc()),
                "reason",
                0.0d,
                List.of()
        });
        when(pjp.proceed(any(Object[].class))).thenThrow(targetFailure);
        when(pjp.proceed()).thenReturn("retried");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> aspect.aroundEnsureCoverage(pjp));

        assertSame(targetFailure, thrown);
        verify(pjp).proceed(any(Object[].class));
        verify(pjp, never()).proceed();
    }

    @Test
    void evidenceAnswerComposerDoesNotRetryOriginalProceedWhenDelegateFails() throws Throwable {
        EvidenceListSnippetFallbackAspect aspect =
                new EvidenceListSnippetFallbackAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        RuntimeException targetFailure = new RuntimeException("target failed");

        when(pjp.getArgs()).thenReturn(new Object[] {
                "draft",
                List.of(urlOnlyDoc()),
                "reason"
        });
        when(pjp.proceed(any(Object[].class))).thenThrow(targetFailure);
        when(pjp.proceed()).thenReturn("retried");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> aspect.aroundEvidenceAnswerComposer(pjp));

        assertSame(targetFailure, thrown);
        verify(pjp).proceed(any(Object[].class));
        verify(pjp, never()).proceed();
    }

    @Test
    void directDegradePathTracesChangedStateWhenUrlOnlyEvidenceIsRewritten() throws Throwable {
        EvidenceListSnippetFallbackAspect aspect =
                new EvidenceListSnippetFallbackAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        when(pjp.getArgs()).thenReturn(new Object[] {
                List.of(urlOnlyDoc())
        });
        when(pjp.proceed(any(Object[].class))).thenReturn("evidence-list");

        Object out = aspect.aroundDegradeToEvidenceList(pjp);

        assertEquals("evidence-list", out);
        assertEquals(1, TraceStore.get("guard.degradedToEvidence.titleFallback.count"));
        assertEquals(1, TraceStore.get("guard.degradedToEvidence.snippetFallback.count"));
        assertEquals(Boolean.TRUE, TraceStore.get("guard.degradedToEvidence.snippetFallback.degrade.changed"));
        verify(pjp).proceed(any(Object[].class));
        verify(pjp, never()).proceed();
    }

    @Test
    void evidenceAnswerComposerTracesChangedStateWhenUrlOnlyEvidenceIsRewritten() throws Throwable {
        EvidenceListSnippetFallbackAspect aspect =
                new EvidenceListSnippetFallbackAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        when(pjp.getArgs()).thenReturn(new Object[] {
                "draft",
                List.of(urlOnlyDoc()),
                "reason"
        });
        when(pjp.proceed(any(Object[].class))).thenReturn("composed-answer");

        Object out = aspect.aroundEvidenceAnswerComposer(pjp);

        assertEquals("composed-answer", out);
        assertEquals(1, TraceStore.get("guard.degradedToEvidence.titleFallback.count"));
        assertEquals(1, TraceStore.get("guard.degradedToEvidence.snippetFallback.count"));
        assertEquals(Boolean.TRUE,
                TraceStore.get("guard.degradedToEvidence.snippetFallback.evidenceAnswerComposer.changed"));
        verify(pjp).proceed(any(Object[].class));
        verify(pjp, never()).proceed();
    }

    @Test
    void failSoftErrorBreadcrumbsUseStableOperationalLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListSnippetFallbackAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                        "TraceStore.put(\"guard.degradedToEvidence.snippetFallback.ensureCoverage.error\", t.getClass().getSimpleName())"),
                "ensureCoverage fallback error trace must not expose Java exception class names");
        assertFalse(source.contains(
                        "TraceStore.put(\"guard.degradedToEvidence.snippetFallback.evidenceAnswerComposer.error\", t.getClass().getSimpleName())"),
                "EvidenceAnswerComposer fallback error trace must not expose Java exception class names");
        assertTrue(source.contains(
                        "TraceStore.put(\"guard.degradedToEvidence.snippetFallback.ensureCoverage.error\", \"ensure_coverage_failed\")"),
                "ensureCoverage fallback should leave a stable operational label");
        assertTrue(source.contains(
                        "TraceStore.put(\"guard.degradedToEvidence.snippetFallback.evidenceAnswerComposer.error\", \"evidence_answer_composer_failed\")"),
                "EvidenceAnswerComposer fallback should leave a stable operational label");
    }

    @Test
    void evidenceListSnippetFallbackDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListSnippetFallbackAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Evidence list fallback needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void urlParsingFallbacksLeaveRedactedErrorBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListSnippetFallbackAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"url.host\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"url.pathTail\", ignore);"));
        assertTrue(source.contains("[nova][evidence-list-snippet-fallback] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(error)), messageLength(error)"));
        assertFalse(source.contains("String.valueOf(url)"));
    }

    private static EvidenceAwareGuard.EvidenceDoc urlOnlyDoc() {
        return new EvidenceAwareGuard.EvidenceDoc(
                "https://example.com/docs/url-only-evidence",
                "",
                "");
    }
}
