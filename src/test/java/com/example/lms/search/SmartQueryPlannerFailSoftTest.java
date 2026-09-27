package com.example.lms.search;

import com.example.lms.search.extract.HybridKeywordExtractor;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.transform.QueryTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmartQueryPlannerFailSoftTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void planAnchoredFallsBackToRawQueryWhenTransformerTimesOut() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transformEnhanced(anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout from test"));

        SmartQueryPlanner planner = planner(transformer, "GENERAL", Optional.empty());

        List<String> planned = planner.planAnchored(
                "RAG evidence starvation debug",
                "RAG",
                null,
                "assistant draft",
                3);

        assertTrue(planned.contains("RAG evidence starvation debug"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("timeout", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
        assertEquals(SafeRedactor.hash12("RAG evidence starvation debug"),
                TraceStore.get("queryTransformer.bypassed.queryHash12"));
        assertEquals("RAG evidence starvation debug".length(),
                TraceStore.get("queryTransformer.bypassed.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("RAG evidence starvation debug"));
    }

    @Test
    void planAnchoredResolvedSubjectClassifiesBreakerOpenWithoutRawExceptionLeak() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transformEnhanced(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("breaker_open ownerToken=secret"));

        SmartQueryPlanner planner = planner(transformer, "GENERAL", Optional.of("GraphRAG"));

        List<String> planned = planner.planAnchored(
                "GraphRAG relation thumbnail",
                "assistant draft",
                4);

        assertTrue(planned.contains("GraphRAG relation thumbnail"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("breaker_open", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret"));
    }

    @Test
    void selectedTermsDoNotDuplicateAnExistingSubjectUnderTurkishLocale() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        KeywordSelectionService selector = mock(KeywordSelectionService.class);
        when(selector.select(anyString(), anyString(), anyInt())).thenReturn(Optional.of(
                SelectedTerms.builder()
                        .must(List.of("IBM", "earnings"))
                        .build()));
        SmartQueryPlanner planner = planner(transformer, "FINANCE", Optional.of("ibm"));
        ReflectionTestUtils.setField(planner, "selector", selector);

        Locale previous = Locale.getDefault();
        List<String> planned;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            planned = planner.plan("IBM earnings", null, 4);
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals(List.of("IBM earnings"), planned);
    }

    @Test
    void queryTransformerBypassReasonUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String safeReasonCode = SafeRedactor.safeMessage(Objects.toString(reasonCode, \"unknown\"), 80);"));
        assertTrue(source.contains("String safeReasonCode = SafeRedactor.traceLabelOrFallback(reasonCode, \"unknown\");"));
    }

    @Test
    void keywordSelectionNoiseGatePropertiesUseBoundedFailSoftParser() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("Double.parseDouble(System.getProperty(\"orch.noiseGate.keywordSelection"));
        assertTrue(source.contains("boundedDoubleProperty("));
        assertTrue(source.contains("\"orch.noiseGate.keywordSelection.compression.escapeP.max\", 0.16d"));
        assertTrue(source.contains("\"orch.noiseGate.keywordSelection.compression.escapeP.min\", 0.03d"));
        assertTrue(source.contains("TraceStore.put(\"smartQueryPlanner.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("log.debug(\"[SmartQueryPlanner] trace suppression failed stage={} errorHash={} errorLength={}\""));
    }

    @Test
    void invalidDoublePropertyFallbackUsesStableInvalidNumberLabel() throws Exception {
        Method method = SmartQueryPlanner.class.getDeclaredMethod(
                "boundedDoubleProperty", String.class, double.class, String.class);
        method.setAccessible(true);
        String key = "test.smartQueryPlanner.invalidDouble";
        System.setProperty(key, "private-number");

        try {
            double parsed = ((Number) method.invoke(null, key, 0.42d, "config.double.parse")).doubleValue();

            assertEquals(0.42d, parsed);
            assertEquals(Boolean.TRUE, TraceStore.get("smartQueryPlanner.suppressed.config.double.parse"));
            assertEquals("invalid_number",
                    TraceStore.get("smartQueryPlanner.suppressed.config.double.parse.errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("private-number"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void smartQueryPlannerFallbackCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(".warn(\"[SmartQueryPlanner] LLM keyword selection failed; falling back\", e);"));
        assertTrue(source.contains("traceSuppressed(\"needleProbeSynthesizer\", e);"));
        assertTrue(source.contains("traceSuppressed(\"keywordSelection.bypassTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"keywordSelection.select\", e);"));
        assertTrue(source.contains("traceSuppressed(\"selectedTerms.fallbackTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"queryTransformer.bypass\", e);"));
    }

    private static SmartQueryPlanner planner(
            QueryTransformer transformer,
            String domain,
            Optional<String> subject) {
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        when(knowledgeBase.inferDomain(anyString())).thenReturn(domain);

        SubjectResolver subjectResolver = mock(SubjectResolver.class);
        when(subjectResolver.resolve(anyString(), anyString())).thenReturn(subject);

        return new SmartQueryPlanner(
                mock(HybridKeywordExtractor.class),
                transformer,
                subjectResolver,
                knowledgeBase,
                new NoiseClipper());
    }
}
