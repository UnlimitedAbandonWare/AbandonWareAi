package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.util.HashUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CleanOutputRedactionAspectTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void blankChatResultUsesReadableFallback() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ChatResult.of("", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was blank. Please retry the request.", result.content());
        assertEquals(Boolean.TRUE, TraceStore.get("orch.output.blank.prevented"));
        assertEquals("blank_content", TraceStore.get("orch.output.blank.prevented.reason"));
        assertFalse(result.content().contains("?"));
    }

    @Test
    void markerOnlyChatResultUsesReadableFallback() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ChatResult.of("<!-- NOVA_TRACE_INJECTED -->", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertEquals(Boolean.TRUE, TraceStore.get("orch.output.blank.prevented"));
        assertEquals("diagnostics_removed_empty", TraceStore.get("orch.output.blank.prevented.reason"));
        assertEquals("<!-- NOVA_TRACE_INJECTED -->:head", TraceStore.get("orch.output.blank.prevented.marker"));
        assertFalse(result.content().contains("?"));
    }

    @Test
    void headMarkerWithDiagnosticsDoesNotBecomeVisibleAnswer() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String diagnosticsFirst = "<!-- NOVA_TRACE_INJECTED -->\n"
                + "private diagnostics ownerToken=secret\n\n"
                + "normal answer";
        when(pjp.proceed()).thenReturn(ChatResult.of(diagnosticsFirst, "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertFalse(result.content().contains("private diagnostics"));
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(result.content().contains("normal answer"));
    }

    @Test
    void inlineDiagnosticTokenInNormalAnswerIsNotCut() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        String answer = "TRACE_JSON is the dedicated structured trace logger name, not user diagnostics.";
        when(pjp.proceed()).thenReturn(ChatResult.of(answer, "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals(answer, result.content());
        assertFalse(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
    }

    @Test
    void canonicalFinalizedResultSkipsOuterPostprocessor() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        ChatResult original = ChatResult.of("canonical final answer", "model", false);
        TraceStore.put("finalAnswer.postprocess.reason", "none");
        TraceStore.put("finalAnswer.memorySaveAllowed", false);
        TraceStore.put("finalAnswer.postprocess.contentHash", HashUtil.sha256(original.content()));
        when(pjp.proceed()).thenReturn(original);

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertSame(original, result);
        assertEquals("canonical_postprocess", TraceStore.get("orch.output.redaction.skipped"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
    }

    @Test
    void reasonAloneCannotBypassEarlyReturnSanitization() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        TraceStore.put("finalAnswer.postprocess.reason", "none");
        when(pjp.proceed()).thenReturn(
                ChatResult.of("normal\nTRACE_JSON {\"ownerToken\":\"secret\"}", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("normal", result.content());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(TraceStore.getAll().containsKey("orch.output.redaction.skipped"));
    }

    @Test
    void staleValidMarkersCannotBypassSanitizationForDifferentResult() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        TraceStore.put("finalAnswer.memorySaveAllowed", false);
        TraceStore.put("finalAnswer.postprocess.reason", "none");
        TraceStore.put("finalAnswer.postprocess.contentHash", HashUtil.sha256("older clean answer"));
        when(pjp.proceed()).thenReturn(
                ChatResult.of("normal\nTRACE_JSON {\"ownerToken\":\"secret\"}", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("normal", result.content());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(TraceStore.getAll().containsKey("orch.output.redaction.skipped"));
    }

    @Test
    void unknownReasonCannotBypassEarlyReturnSanitization() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        TraceStore.put("finalAnswer.memorySaveAllowed", false);
        TraceStore.put("finalAnswer.postprocess.reason", "forged");
        when(pjp.proceed()).thenReturn(
                ChatResult.of("normal\nTRACE_JSON {\"ownerToken\":\"secret\"}", "model", false));

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("normal", result.content());
        assertTrue(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(TraceStore.getAll().containsKey("orch.output.redaction.skipped"));
    }

    @Test
    void legacyLineStartTraceMarkerStillCutsDiagnosticTail() throws Throwable {
        CleanOutputRedactionAspect aspect = new CleanOutputRedactionAspect(new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        com.example.lms.dto.RagEvidenceMetadata metadata =
                new com.example.lms.dto.RagEvidenceMetadata(
                        "W1", "WEB", "Public title", "https://example.test/evidence",
                        null, null, null, 1, 0.9, "test");
        ChatResult original = ChatResult.of(
                "normal answer\nTRACE_JSON {\"ownerToken\":\"secret\"}",
                "model-route",
                true,
                java.util.Set.of("WEB"),
                java.util.List.of(metadata));
        when(pjp.proceed()).thenReturn(original);

        ChatResult result = (ChatResult) aspect.redactUserVisibleOutput(pjp);

        assertEquals("normal answer", result.content());
        assertEquals(original.modelUsed(), result.modelUsed());
        assertEquals(original.ragUsed(), result.ragUsed());
        assertEquals(original.evidence(), result.evidence());
        assertEquals(original.evidenceMetadata(), result.evidenceMetadata());
        assertFalse(result.content().contains("ownerToken"));
        assertTrue(Boolean.TRUE.equals(TraceStore.get("orch.output.redaction.applied")));
    }

    @Test
    void failSoftTraceAndHashFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String aspectSource = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/CleanOutputRedactionAspect.java"));
        String sanitizerSource = Files.readString(Path.of(
                "main/java/com/example/lms/service/postprocess/OutputSanitizer.java"));

        assertTrue(aspectSource.contains("traceSuppressed(\"blank.prevented\", ignored);"));
        assertTrue(aspectSource.contains("traceSuppressed(\"blank.prevented.marker\", ignored);"));
        assertTrue(aspectSource.contains("traceSuppressed(\"redaction.applied\", ignored);"));
        assertTrue(aspectSource.contains(
                "CleanOutputRedactionAspect trace fallback (stage={} errorHash={} errorLength={})"));
        assertTrue(aspectSource.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(aspectSource.contains("SafeRedactor.hashValue(messageOf(error)), messageLength(error)"));
        assertTrue(sanitizerSource.contains("MessageDigest.getInstance(\"SHA-1\")"));
        assertTrue(sanitizerSource.contains("SafeRedactor.redact(clip(removed, 2_048))"));
        assertTrue(sanitizerSource.contains("catch (Exception failure)"));
        assertTrue(sanitizerSource.contains(
                "Output sanitizer hash unavailable valueLength={0} errorType={1}"));
        assertTrue(sanitizerSource.contains(
                "value.length(), failure.getClass().getSimpleName()"));
    }
}
