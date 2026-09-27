package com.example.lms.service.rag.pre;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.location.LocationService;
import com.example.lms.config.rag.RagCognitiveProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QueryPreprocessorTraceContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void compositeDelegateFallbacksExposeStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/pre/CompositeQueryContextPreprocessor.java"));

        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.detectDomain\", true)"));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.stage\", \"detectDomain\")"));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.stage\", \"inferIntent\")"));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.stage\", \"interactionRules\")"));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.inferIntent\", true)"));
        assertTrue(source.contains("TraceStore.put(\"query.preprocessor.suppressed.interactionRules\", true)"));
        assertFalse(source.contains("log.warn(\"{}\", q)"));
    }

    @Test
    void guardrailFallbacksExposeStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/pre/GuardrailQueryPreprocessor.java"));

        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.cognitiveState\", true)"));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.stage\", \"cognitiveState\")"));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.stage\", \"typoRewrite\")"));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.stage\", \"extractCognitiveState\")"));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.typoRewrite\", true)"));
        assertTrue(source.contains("TraceStore.put(\"query.guardrail.suppressed.extractCognitiveState\", true)"));
        assertFalse(source.contains("log.warn(\"{}\", q)"));
    }

    @Test
    void guardrailCognitiveFailureLeavesStageErrorTypeWithoutRawQuery() {
        GuardrailQueryPreprocessor guardrail = new GuardrailQueryPreprocessor(null, null, null, null);
        String rawQuery = "raw sensitive guardrail query";

        String out = guardrail.enrich(rawQuery);

        assertFalse(out.isBlank());
        assertEquals(Boolean.TRUE, TraceStore.get("query.guardrail.suppressed.cognitiveState"));
        assertEquals("NullPointerException",
                TraceStore.get("query.guardrail.suppressed.cognitiveState.errorType"));
        assertEquals("cognitiveState", TraceStore.get("query.guardrail.suppressed.stage"));
        assertEquals("NullPointerException", TraceStore.get("query.guardrail.suppressed.errorType"));
        assertEquals("NullPointerException", TraceStore.get("query.guardrail.failureReason"));
        assertEquals("NullPointerException", TraceStore.get("query.guardrail.cognitive_state.failureReason"));
        assertEquals(rawQuery.length(), TraceStore.get("query.guardrail.cognitive_state.queryLength"));
        assertNotNull(TraceStore.get("query.guardrail.cognitive_state.queryHash"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(rawQuery));
    }

    @Test
    void sensitiveBoundaryMetaFallbackLeavesBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/pre/SensitiveBoundaryPreprocessor.java"));

        assertTrue(source.contains("TraceStore.put(\"privacy.boundary.suppressed.metaWrite\", true)"));
        assertTrue(source.contains("TraceStore.put(\"privacy.boundary.suppressed.stage\", \"metaWrite\")"));
        assertTrue(source.contains("TraceStore.put(\"privacy.boundary.suppressed.errorType\""));
        assertFalse(source.contains("meta={}"));
    }

    @Test
    void sensitiveBoundaryMasksWebQueryWhenGuardContextIsAbsent() {
        GuardContextHolder.clear();
        SensitiveBoundaryPreprocessor preprocessor = new SensitiveBoundaryPreprocessor();
        ReflectionTestUtils.setField(preprocessor, "enabled", true);
        ReflectionTestUtils.setField(preprocessor, "maxLen", 220);
        Map<String, Object> meta = new HashMap<>();
        meta.put("purpose", "web_search");

        String out = preprocessor.enrich("contact user@example.com or 010-1234-5678", meta);

        assertEquals("contact [EMAIL] or [PHONE]", out);
        assertEquals(Boolean.TRUE, meta.get("privacy.masked"));
        assertFalse(out.contains("user@example.com"));
        assertFalse(out.contains("010-1234-5678"));
    }

    @Test
    void sensitiveBoundaryMetaWriteFailureLeavesErrorTypeWithoutRawQuery() {
        GuardContextHolder.clear();
        SensitiveBoundaryPreprocessor preprocessor = new SensitiveBoundaryPreprocessor();
        ReflectionTestUtils.setField(preprocessor, "enabled", true);
        ReflectionTestUtils.setField(preprocessor, "maxLen", 220);
        String rawQuery = "contact user@example.com";

        String out = preprocessor.enrich(rawQuery, Map.of("purpose", "web_search"));

        assertEquals("contact [EMAIL]", out);
        assertEquals(Boolean.TRUE, TraceStore.get("privacy.boundary.suppressed.metaWrite"));
        assertEquals("UnsupportedOperationException",
                TraceStore.get("privacy.boundary.suppressed.metaWrite.errorType"));
        assertEquals("metaWrite", TraceStore.get("privacy.boundary.suppressed.stage"));
        assertEquals("UnsupportedOperationException", TraceStore.get("privacy.boundary.suppressed.errorType"));
        assertNotNull(TraceStore.get("privacy.boundary.suppressed.metaWrite.queryHash"));
        assertEquals(rawQuery.length(),
                TraceStore.get("privacy.boundary.suppressed.metaWrite.queryLength"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(rawQuery));
        assertFalse(publicTrace.contains("user@example.com"));
    }

    @Test
    void locationRewritePrivacySkipLeavesHashOnlyBreadcrumb() {
        LocationService locationService = mock(LocationService.class);
        LocationContextQueryPreprocessor preprocessor = new LocationContextQueryPreprocessor(locationService);
        GuardContext guardContext = new GuardContext();
        guardContext.setSensitiveTopic(true);
        GuardContextHolder.set(guardContext);
        String rawQuery = "near me coffee";

        String out = preprocessor.enrich(rawQuery, Map.of("purpose", "web_search"));

        assertEquals(rawQuery, out);
        verifyNoInteractions(locationService);
        assertEquals(Boolean.TRUE, TraceStore.get("query.location.rewriteSkipped"));
        assertEquals("privacy_boundary_web_query", TraceStore.get("query.location.rewriteSkipped.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("query.location.rewriteSkipped.webPurpose"));
        assertEquals(Boolean.TRUE, TraceStore.get("query.location.rewriteSkipped.privacyBoundary"));
        assertNotNull(TraceStore.get("query.location.rewriteSkipped.queryHash"));
        assertEquals(rawQuery.length(), TraceStore.get("query.location.rewriteSkipped.queryLength"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(rawQuery));
    }

    @Test
    void locationRewriteTraceStoreFailureLogsRedactedLastResortBreadcrumb() {
        LocationService locationService = mock(LocationService.class);
        LocationContextQueryPreprocessor preprocessor = new LocationContextQueryPreprocessor(locationService);
        GuardContext guardContext = new GuardContext();
        guardContext.setSensitiveTopic(true);
        GuardContextHolder.set(guardContext);
        String rawQuery = "near me ownerToken=private-token";
        Logger logger = (Logger) LoggerFactory.getLogger(LocationContextQueryPreprocessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TraceStore.installContext(new ThrowingTraceMap());

            String out = preprocessor.enrich(rawQuery, Map.of("purpose", "web_search"));

            assertEquals(rawQuery, out);
            verifyNoInteractions(locationService);
            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(rendered.contains("[AWX][query-location] traceRewriteSkippedSuppressed"), rendered);
            assertTrue(rendered.contains("errorType=UnsupportedOperationException"), rendered);
            assertFalse(rendered.contains("ownerToken"), rendered);
            assertFalse(rendered.contains("private-token"), rendered);
            assertFalse(rendered.contains(rawQuery), rendered);
        } finally {
            logger.detachAppender(appender);
            TraceStore.clear();
        }
    }

    @Test
    void compositeNullDelegateOutputPreservesPreviousQueryAndTracesHashOnly() {
        QueryContextPreprocessor nulling = original -> null;
        QueryContextPreprocessor suffix = original -> original + " safe";
        CompositeQueryContextPreprocessor composite = new CompositeQueryContextPreprocessor(
                List.of(nulling, suffix),
                new RagCognitiveProperties());

        String out = composite.enrich("raw sensitive query", Map.of("purpose", "web_search"));

        assertEquals("raw sensitive query safe", out);
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.nullOutput"));
        assertEquals("enrich", TraceStore.get("query.preprocessor.nullOutput.stage"));
        assertEquals("null_output", TraceStore.get("query.preprocessor.nullOutput.reason"));
        assertEquals(19, TraceStore.get("query.preprocessor.nullOutput.queryLength"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains("raw sensitive query"));
        assertFalse(publicTrace.contains("raw sensitive query safe"));
    }

    @Test
    void compositeDetectDomainFailureLeavesStageErrorTypeWithoutRawQuery() {
        QueryContextPreprocessor throwing = new QueryContextPreprocessor() {
            @Override
            public String enrich(String original) {
                return original;
            }

            @Override
            public String detectDomain(String q) {
                throw new IllegalStateException("private preprocessor ownerToken=raw-secret");
            }
        };
        CompositeQueryContextPreprocessor composite = new CompositeQueryContextPreprocessor(
                List.of(throwing),
                new RagCognitiveProperties());
        String rawQuery = "raw sensitive query";

        String domain = composite.detectDomain(rawQuery);

        assertEquals("GENERAL", domain);
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.suppressed.detectDomain"));
        assertEquals("IllegalStateException",
                TraceStore.get("query.preprocessor.suppressed.detectDomain.errorType"));
        assertEquals("detectDomain", TraceStore.get("query.preprocessor.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("query.preprocessor.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("query.preprocessor.failureReason"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(rawQuery));
        assertFalse(publicTrace.contains("ownerToken"));
        assertFalse(publicTrace.contains("raw-secret"));
    }

    @Test
    void compositeStageFailureBreadcrumbsKeepFirstFailureAndExposeStageScopedHashOnly() {
        class ThrowingPreprocessor implements QueryContextPreprocessor {
            @Override
            public String enrich(String original) {
                throw new IllegalStateException("private preprocessor ownerToken=raw-secret");
            }

            @Override
            public String inferIntent(String q) {
                throw new IllegalArgumentException("private preprocessor ownerToken=raw-secret-2");
            }
        }
        CompositeQueryContextPreprocessor composite = new CompositeQueryContextPreprocessor(
                List.of(new ThrowingPreprocessor()),
                new RagCognitiveProperties());
        String rawQuery = "raw sensitive query";

        String out = composite.enrich(rawQuery, Map.of("purpose", "web_search"));
        String intent = composite.inferIntent(rawQuery);

        assertEquals(rawQuery, out);
        assertEquals("GENERAL", intent);
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.failed"));
        assertEquals("enrich", TraceStore.get("query.preprocessor.failureStage"));
        assertEquals("IllegalStateException", TraceStore.get("query.preprocessor.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("query.preprocessor.enrich.failureReason"));
        assertEquals("ThrowingPreprocessor", TraceStore.get("query.preprocessor.enrich.name"));
        assertEquals(rawQuery.length(), TraceStore.get("query.preprocessor.enrich.queryLength"));
        assertNotNull(TraceStore.get("query.preprocessor.enrich.queryHash"));
        assertEquals("IllegalArgumentException", TraceStore.get("query.preprocessor.infer_intent.failureReason"));
        assertEquals("ThrowingPreprocessor", TraceStore.get("query.preprocessor.infer_intent.name"));
        assertEquals(rawQuery.length(), TraceStore.get("query.preprocessor.infer_intent.queryLength"));
        assertNotNull(TraceStore.get("query.preprocessor.infer_intent.queryHash"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains(rawQuery));
        assertFalse(publicTrace.contains("ownerToken"));
        assertFalse(publicTrace.contains("raw-secret"));
        assertFalse(publicTrace.contains("raw-secret-2"));
    }

    @Test
    void compositeGuardrailSkipWhenCognitiveDisabledLeavesReasonWithoutRawQuery() {
        RagCognitiveProperties props = new RagCognitiveProperties();
        props.setEnabled(false);
        GuardrailQueryPreprocessor guardrail = new GuardrailQueryPreprocessor(null, null, null, null);
        QueryContextPreprocessor suffix = original -> original + " safe";
        CompositeQueryContextPreprocessor composite = new CompositeQueryContextPreprocessor(
                List.of(guardrail, suffix),
                props);

        String out = composite.enrich("raw sensitive query", Map.of("purpose", "web_search"));

        assertEquals("raw sensitive query safe", out);
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.guardrailSkipped"));
        assertEquals("enrich", TraceStore.get("query.preprocessor.guardrailSkipped.stage"));
        assertEquals("cognitive_disabled", TraceStore.get("query.preprocessor.guardrailSkipped.reason"));
        assertEquals("GuardrailQueryPreprocessor", TraceStore.get("query.preprocessor.guardrailSkipped.name"));
        String publicTrace = TraceStore.getAll().toString();
        assertFalse(publicTrace.contains("raw sensitive query"));
        assertFalse(publicTrace.contains("raw sensitive query safe"));
    }

    private static final class ThrowingTraceMap extends HashMap<String, Object> {
        @Override
        public Object put(String key, Object value) {
            throw new UnsupportedOperationException("trace-store-readonly ownerToken=private-token");
        }
    }
}
