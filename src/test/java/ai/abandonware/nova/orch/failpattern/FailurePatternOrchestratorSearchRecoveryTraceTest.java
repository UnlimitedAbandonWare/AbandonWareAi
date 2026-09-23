package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePatternOrchestratorSearchRecoveryTraceTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void searchRecoveryMatchesUpdateLowCardinalityTraceCountersWithoutRawQuery() {
        FailurePatternOrchestrator orchestrator = newOrchestrator();
        String rawQuery = "raw private query should not be copied to trace";

        orchestrator.onLogEvent(
                1_000L,
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "WARN",
                "[AWX2AF2][rag][starvation] zero_result_reason=zero_result query=" + rawQuery);
        orchestrator.onLogEvent(
                1_100L,
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "WARN",
                "[AWX2AF2][rag][starvation] zero_result_reason=after_filter_starvation query=" + rawQuery);
        orchestrator.onLogEvent(
                1_200L,
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "WARN",
                "[AWX2AF2][rag][starvation] zero_result_reason=zero_result query=" + rawQuery);

        assertEquals("SEARCH_ZERO_RESULT", TraceStore.get("failpattern.searchRecovery.kind"));
        assertEquals("zero_result", TraceStore.get("failpattern.searchRecovery.reason"));
        assertEquals("rag", TraceStore.get("failpattern.searchRecovery.source"));
        assertEquals(1_200L, TraceStore.get("failpattern.searchRecovery.lastAtMs"));

        Map<?, ?> counts = assertInstanceOf(Map.class, TraceStore.get("failpattern.searchRecovery.count"));
        assertEquals(2L, ((Number) counts.get("SEARCH_ZERO_RESULT")).longValue());
        assertEquals(1L, ((Number) counts.get("SEARCH_AFTER_FILTER_STARVATION")).longValue());

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery));
        assertFalse(trace.contains("raw private query"));
    }

    @Test
    void insufficientEvidenceUsesCanonicalReasonAndSeparateCounter() {
        FailurePatternOrchestrator orchestrator = newOrchestrator();

        orchestrator.onLogEvent(
                2_000L,
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "WARN",
                "[AWX2AF2][rag][starvation] zero_result_reason=insufficient_citations recoveredCount=0");

        assertEquals("EVIDENCE_INSUFFICIENT", TraceStore.get("failpattern.searchRecovery.kind"));
        assertEquals("evidence_insufficient", TraceStore.get("failpattern.searchRecovery.reason"));
        assertEquals("rag", TraceStore.get("failpattern.searchRecovery.source"));

        Map<?, ?> counts = assertInstanceOf(Map.class, TraceStore.get("failpattern.searchRecovery.count"));
        assertEquals(1L, ((Number) counts.get("EVIDENCE_INSUFFICIENT")).longValue());
    }

    @Test
    void providerSpecificSearchRecoveryKeepsTavilySourceWithoutRawQuery() {
        FailurePatternOrchestrator orchestrator = newOrchestrator();
        String rawQuery = "raw private tavily query should not be copied";

        orchestrator.onLogEvent(
                2_500L,
                "com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain",
                "WARN",
                "[AWX2AF2][rag][starvation] zero_result_reason=after_filter_starvation provider=tavily query="
                        + rawQuery);

        assertEquals("SEARCH_AFTER_FILTER_STARVATION", TraceStore.get("failpattern.searchRecovery.kind"));
        assertEquals("after_filter_starvation", TraceStore.get("failpattern.searchRecovery.reason"));
        assertEquals("tavily", TraceStore.get("failpattern.searchRecovery.source"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery));
        assertFalse(trace.contains("raw private tavily query"));
    }

    @Test
    void searchRecoveryReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java"));

        assertFalse(source.contains("TraceStore.put(\"failpattern.searchRecovery.reason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"failpattern.searchRecovery.reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"failpattern.searchRecovery.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void failurePatternOrchestratorFallbackCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java"));

        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.registryTransition\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.mdcSid\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.rememberRecent\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.positiveLong\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.reloadFromJsonl\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.parseJsonl\", ignored);"));
        assertTrue(source.contains("FailurePatternTrace.traceSkipped(\"failurePattern.parseKind\", ignored);"));
    }

    @Test
    void failurePatternKindParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java"));
        String parserCall = "return FailurePatternKind.valueOf(s.trim().toUpperCase(Locale.ROOT));";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "failure pattern kind parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 220));
        assertFalse(window.contains("catch (Exception"),
                "failure pattern kind parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "failure pattern kind parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "failure pattern kind parser should only catch IllegalArgumentException");
    }

    @Test
    void failurePatternDocsDoNotClaimBoltzmannOrRawMatrixWiring() throws Exception {
        String orchestrator = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternOrchestrator.java"));
        String rawMatrixBuffer = Files.readString(Path.of(
                "main/java/com/example/lms/cfvm/RawMatrixBuffer.java"));

        assertTrue(orchestrator.contains("NOT Boltzmann softmax temperature"));
        assertTrue(orchestrator.contains("RawMatrixBuffer"));
        assertTrue(orchestrator.contains("not currently wired to this orchestrator"));
        assertTrue(rawMatrixBuffer.contains("not currently wired directly to FailurePatternOrchestrator"));
    }

    private static FailurePatternOrchestrator newOrchestrator() {
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        props.getJsonl().setReadEnabled(false);
        props.getJsonl().setWriteEnabled(false);

        return new FailurePatternOrchestrator(
                new FailurePatternDetector(),
                new FailurePatternMetrics(null, props),
                new FailurePatternJsonlWriter(new ObjectMapper(), props),
                new FailurePatternCooldownRegistry(),
                new ObjectMapper(),
                props);
    }
}
