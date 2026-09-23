package com.example.lms.service;

import com.example.lms.search.TraceStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemoryReinforcementRedactionContractTest {

    @Test
    void memoryReinforcementTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();
        try {
            MemoryReinforcementTraceSuppressions.traceSuppressed(
                    "recoveredPenalty.config",
                    new NumberFormatException("ownerToken=raw-secret"));

            assertEquals("recoveredPenalty.config",
                    TraceStore.get("memory.reinforce.suppressed.stage"));
            assertEquals("invalid_number",
                    TraceStore.get("memory.reinforce.suppressed.errorType"));
            assertEquals("invalid_number",
                    TraceStore.get("memory.reinforce.suppressed.recoveredPenalty.config.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("NumberFormatException"), trace);
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void memoryReinforcementServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String code = Files.readString(Path.of("main/java/com/example/lms/service/MemoryReinforcementService.java"),
                StandardCharsets.UTF_8);

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(code).results().count(),
                "memory reinforcement fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
        assertFalse(code.contains("MemoryReinforcementTraceSuppressions.trace(\""));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"citation.urlPattern\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"citation.markerPattern\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"sourceHash.incrementHit\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"turnMemory.vectorEnqueue\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"snippet.vectorEnqueue\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"recoveredPenalty.config\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"context.load\", e);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"recentSnippetCache.put\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"snippetV2.vectorEnqueue\", ignore);"));
        assertTrue(code.contains("MemoryReinforcementTraceSuppressions.traceSuppressed(\"hash.sha1Fallback\", e);"));
    }

    @Test
    void memoryReinforcementFailureLogsDoNotUseRawThrowableMessagesOrSidValues() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/MemoryReinforcementService.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = code.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        assertFalse(code.contains("sid={} : {}\", stage, session,"));
        assertFalse(code.contains("(sid={})\", sid, e"));
        assertFalse(code.contains("stage={} sid={} reason={}"));
        assertFalse(code.contains("session {}\", sessionId"));
        assertFalse(code.contains("SID → skip (sid={})\", sessionId"));
        assertFalse(code.contains("[Feedback] applied (sid={},"));
        assertFalse(code.contains("PENDING sid={} score={}"));
        assertFalse(code.contains("for session={}, source={}"));
        assertFalse(code.contains("for session={}\""));
        assertFalse(code.contains("sid={})\", score, sid"));
        assertTrue(code.contains("sidHash={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(session))"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(sessionId))"));
        assertFalse(code.contains("return s.substring(0, max) + \"...\";"));
        assertTrue(code.contains("return com.example.lms.trace.SafeRedactor.safeMessage(s, max);"));
        assertFalse(code.contains("Blocking simple negative/uncertain answer from reinforcement: '{}'"));
        assertFalse(code.contains("hasEvidence={}, snippet='{}'"));
        assertFalse(code.contains("STRICT mode). snippet='{}'"));
        assertTrue(code.contains("answerHash={} answerLength={}"));
        assertTrue(code.contains("snippetHash={} snippetLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(trimmedSnippet)"));
        assertTrue(code.contains("SafeRedactor.hashValue(snippet)"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf("));
        assertTrue(code.contains("errorSummary(Throwable error)"));
        assertTrue(code.contains("\"errorHash=\" + errorHash(error) + \" errorLength=\" + errorLength(error)"));
        assertTrue(code.contains("errorHash(Throwable error)"));
        assertTrue(code.contains("errorLength(Throwable error)"));
        assertTrue(code.contains("errorSummary(e)"));
        assertTrue(code.contains("errorSummary(ex)"));
        assertTrue(code.contains("errorSummary(t)"));
        assertTrue(code.contains("errorSummary(tuneEx)"));
    }

    @Test
    void highRiskContentCheckDoesNotThrowOnOrdinaryChatText() {
        MemoryReinforcementService service = new MemoryReinforcementService(
                org.mockito.Mockito.mock(com.example.lms.repository.TranslationMemoryRepository.class),
                org.mockito.Mockito.mock(VectorStoreService.class),
                org.mockito.Mockito.mock(com.example.lms.service.reinforcement.SnippetPruner.class),
                org.mockito.Mockito.mock(com.example.lms.strategy.StrategyPerformanceRepository.class),
                org.mockito.Mockito.mock(com.example.lms.strategy.StrategyDecisionTracker.class),
                org.mockito.Mockito.mock(com.example.lms.service.config.HyperparameterService.class));

        Boolean highRisk = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(
                        service,
                        "isHighRiskContent",
                        "ordinary harmony smoke",
                        "normal answer"));

        assertEquals(Boolean.FALSE, highRisk);
    }
}
