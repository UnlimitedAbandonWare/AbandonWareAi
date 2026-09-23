package com.example.lms.service.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputSanitizerTest {

    private final OutputSanitizer sanitizer = new OutputSanitizer();

    @Test
    void blankOutputUsesDeterministicReadableFallback() {
        OutputSanitizer.Result result = sanitizer.sanitize("   ");

        assertEquals("The answer body was blank. Please retry the request.", result.content());
        assertEquals("blank_content", result.reasonCode());
        assertTrue(result.changed());
        assertEquals(0L, result.removedChars());
    }

    @Test
    void headDiagnosticMarkerDoesNotPromoteFollowingDiagnosticsOrAnswerText() {
        String raw = "<!-- NOVA_TRACE_INJECTED -->\n"
                + "private diagnostics ownerToken=secret\n\n"
                + "normal answer";

        OutputSanitizer.Result result = sanitizer.sanitize(raw);

        assertEquals(
                "The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertEquals("diagnostics_removed_empty", result.reasonCode());
        assertEquals("<!-- NOVA_TRACE_INJECTED -->:head", result.marker());
        assertTrue(result.changed());
        assertFalse(result.content().contains("ownerToken"));
        assertFalse(result.content().contains("normal answer"));
        assertEquals(0L, result.removedChars(),
                "fallback text may be longer than the removed diagnostic payload");
        assertNotNull(result.removedHash());
    }

    @Test
    void inlineDiagnosticNameInNormalAnswerIsNotCut() {
        String answer = "TRACE_JSON is the structured trace logger name, not user diagnostics.";

        OutputSanitizer.Result result = sanitizer.sanitize(answer);

        assertEquals(answer, result.content());
        assertEquals("none", result.reasonCode());
        assertFalse(result.changed());
        assertEquals(0L, result.removedChars());
    }

    @Test
    void lineStartDiagnosticTailIsRemovedWithLowCardinalityEvidence() {
        String raw = "normal answer\nTRACE_JSON {\"ownerToken\":\"secret\"}";

        OutputSanitizer.Result result = sanitizer.sanitize(raw);

        assertEquals("normal answer", result.content());
        assertEquals("diagnostics_removed", result.reasonCode());
        assertEquals("TRACE_JSON", result.marker());
        assertTrue(result.changed());
        assertTrue(result.removedChars() > 0L);
        assertNotNull(result.removedHash());
        assertFalse(result.content().contains("ownerToken"));
    }

    @Test
    void markerOnlyOutputUsesDiagnosticsRemovedFallback() {
        OutputSanitizer.Result result = sanitizer.sanitize("<!-- NOVA_TRACE_INJECTED -->");

        assertEquals(
                "The answer body was removed while cleaning diagnostics. Please retry the request.",
                result.content());
        assertEquals("diagnostics_removed_empty", result.reasonCode());
        assertEquals("<!-- NOVA_TRACE_INJECTED -->:head", result.marker());
        assertTrue(result.changed());
    }
}
