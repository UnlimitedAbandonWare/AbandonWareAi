package com.example.lms.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.aicore.domain.model.GenerationParams;
import com.acme.aicore.domain.model.Prompt;
import com.acme.aicore.domain.model.TokenChunk;
import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceAspectRedactionContractTest {

    @Test
    void promptTraceUsesHashAndLengthInsteadOfPromptPreviews() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/PromptTraceAspect.java"));

        assertFalse(source.contains("\"user_preview\""));
        assertFalse(source.contains("\"ctx_preview\""));
        assertTrue(source.contains("\"user_hash\""));
        assertTrue(source.contains("\"user_len\""));
        assertTrue(source.contains("\"ctx_hash\""));
        assertTrue(source.contains("\"ctx_len\""));
    }

    @Test
    void promptTraceToleratesMissingJoinPointMetadata() {
        PromptTraceAspect aspect = new PromptTraceAspect();
        Prompt prompt = new Prompt("system", "private user prompt", "private context");

        assertDoesNotThrow(() -> aspect.afterBuild(null, prompt));
    }

    @Test
    void llmTraceUsesHashAndLengthInsteadOfResponsePreviews() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/LlmTraceAspect.java"));

        assertFalse(source.contains("\"resp_preview\""));
        assertFalse(source.contains("TraceLogger.preview(resp)"));
        assertTrue(source.contains("\"resp_hash\""));
        assertTrue(source.contains("\"resp_len\""));
        assertTrue(source.contains("hashOrEmpty(preview.toString())"));
    }

    @Test
    void llmStreamTraceToleratesNullTokenText() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(Flux.just(TokenChunk.of(null)));
        LlmTraceAspect aspect = new LlmTraceAspect();

        Object wrapped = aspect.aroundStream(
                pjp,
                new Prompt(null, null, null),
                GenerationParams.streaming());

        assertTrue(wrapped instanceof Flux<?>);
        assertDoesNotThrow(() -> ((Flux<?>) wrapped).collectList().block());
    }

    @Test
    void llmStreamTraceToleratesNegativePreviewLimit() throws Throwable {
        int previousPreview = TraceLogger.PREVIEW;
        try {
            TraceLogger.PREVIEW = -1;
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            when(pjp.proceed()).thenReturn(Flux.just(TokenChunk.of("private stream text")));
            LlmTraceAspect aspect = new LlmTraceAspect();

            Object wrapped = aspect.aroundStream(
                    pjp,
                    new Prompt(null, null, null),
                    GenerationParams.streaming());

            assertTrue(wrapped instanceof Flux<?>);
            assertDoesNotThrow(() -> ((Flux<?>) wrapped).collectList().block());
        } finally {
            TraceLogger.PREVIEW = previousPreview;
        }
    }

    @Test
    void searchTraceHashesStableDocumentIdentifiers() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/SearchTraceAspect.java"));

        assertFalse(source.contains("return String.valueOf(v);"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(v))"));
    }

    @Test
    void searchTraceHashesLangChainEmbeddingIdMetadata() throws Exception {
        Content content = mock(Content.class);
        String rawEmbeddingId = "vector-doc-C:\\Users\\nninn\\Desktop\\secret\\doc-123";
        when(content.metadata()).thenReturn(Map.of(ContentMetadata.EMBEDDING_ID, rawEmbeddingId));
        Method method = SearchTraceAspect.class.getDeclaredMethod("extractStableId", Content.class);
        method.setAccessible(true);

        String stableId = (String) method.invoke(null, content);

        assertNotNull(stableId);
        assertFalse(stableId.isBlank());
        assertFalse(stableId.contains(rawEmbeddingId));
        assertEquals(SafeRedactor.hashValue(rawEmbeddingId), stableId);
    }

    @Test
    void searchTraceSkipsNullRetrievedContentItems() throws Throwable {
        Content content = mock(Content.class);
        when(content.metadata()).thenReturn(Map.of(ContentMetadata.EMBEDDING_ID, "doc-1"));
        List<Content> ret = new ArrayList<>();
        ret.add(content);
        ret.add(null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(ret);
        SearchTraceAspect aspect = new SearchTraceAspect();

        Object out = assertDoesNotThrow(() -> aspect.aroundRetrieve(pjp, null));

        assertTrue(out == ret);
    }

    @Test
    void searchDecisionTraceSkipsNullProviders() throws Throwable {
        List<ProviderId> providers = new ArrayList<>();
        providers.add(ProviderId.NAVER);
        providers.add(null);
        SearchDecision decision = new SearchDecision(true, SearchDecision.Depth.DEEP, providers, 3, "test");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(decision);
        SearchTraceAspect aspect = new SearchTraceAspect();

        Object out = assertDoesNotThrow(() -> aspect.aroundDecision(pjp));

        assertTrue(out == decision);
    }

    @Test
    void traceLoggerEmitUsesStableEmptyCorrelationHashesWhenMdcMissing() throws Exception {
        MDC.clear();
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;

            TraceLogger.emit("unit_trace", "trace", Map.of("requestId", "raw-payload-request-id"));

            assertFalse(appender.list.isEmpty());
            String logged = appender.list.get(0).getFormattedMessage();
            Map<?, ?> event = new ObjectMapper().readValue(logged, Map.class);
            assertEquals("", event.get("sid"));
            assertEquals("", event.get("trace"));
            assertEquals("", event.get("requestId"));
            assertFalse(logged.contains("\"sid\":null"));
            assertFalse(logged.contains("\"trace\":null"));
            assertFalse(logged.contains("\"requestId\":null"));
            assertFalse(logged.contains("raw-payload-request-id"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
            MDC.clear();
        }
    }

    @Test
    void traceLoggerEmitUsesStableLabelsWhenTypeOrStageMissing() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;

            TraceLogger.emit(null, "   ", Map.of());

            assertFalse(appender.list.isEmpty());
            String logged = appender.list.get(0).getFormattedMessage();
            Map<?, ?> event = new ObjectMapper().readValue(logged, Map.class);
            assertEquals("unknown", event.get("type"));
            assertEquals("unknown", event.get("stage"));
            assertFalse(logged.contains("\"type\":null"));
            assertFalse(logged.contains("\"stage\":null"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
        }
    }

    @Test
    void traceLoggerTypeAndStageAreLabelsNotRawText() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;
            String rawType = "private trace type " + com.example.lms.test.SecretFixtures.openAiKey() + "";
            String rawStage = "private trace stage ownerToken=secret";

            TraceLogger.emit(rawType, rawStage, Map.of("count", 1));

            assertFalse(appender.list.isEmpty());
            String logged = appender.list.get(0).getFormattedMessage();
            Map<?, ?> event = new ObjectMapper().readValue(logged, Map.class);
            assertTrue(String.valueOf(event.get("type")).startsWith("hash:"), logged);
            assertTrue(String.valueOf(event.get("stage")).startsWith("hash:"), logged);
            assertFalse(logged.contains(rawType));
            assertFalse(logged.contains(rawStage));
            assertFalse(logged.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
            assertFalse(logged.contains("ownerToken"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
        }
    }

    @Test
    void traceLoggerPayloadKeysAreLabelsNotRawText() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("TRACE_JSON");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        boolean previousEnabled = TraceLogger.enabled;
        double previousSample = TraceLogger.sample;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            TraceLogger.enabled = true;
            TraceLogger.sample = 1.0d;
            String rawKey = "private payload key " + com.example.lms.test.SecretFixtures.openAiKey() + "";

            TraceLogger.emit("unit_trace", "trace", Map.of(rawKey, "provider-disabled"));

            assertFalse(appender.list.isEmpty());
            String logged = appender.list.get(0).getFormattedMessage();
            Map<?, ?> event = new ObjectMapper().readValue(logged, Map.class);
            Map<?, ?> kv = (Map<?, ?>) event.get("kv");
            assertTrue(kv.keySet().stream().anyMatch(k -> String.valueOf(k).startsWith("hash:")), logged);
            assertFalse(logged.contains(rawKey));
            assertFalse(logged.contains("private payload key"));
            assertFalse(logged.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            TraceLogger.enabled = previousEnabled;
            TraceLogger.sample = previousSample;
        }
    }

    @Test
    void traceLoggerPreviewTreatsNegativeLimitAsEmptyClip() {
        int previousPreview = TraceLogger.PREVIEW;
        try {
            TraceLogger.PREVIEW = -1;

            String preview = assertDoesNotThrow(() -> TraceLogger.preview("private preview text"));

            assertEquals("", preview);
        } finally {
            TraceLogger.PREVIEW = previousPreview;
        }
    }

    @Test
    void traceLoggerSystemPropertyParsersFailSoft() {
        TraceStore.clear();
        assertEquals(240, TraceLogger.parseIntProperty("not-a-number", 240));
        assertEquals(64, TraceLogger.parseIntProperty("64", 240));
        assertEquals(1.0d, TraceLogger.parseDoubleProperty("bad-sample", 1.0d), 1.0e-9d);
        assertEquals(0.25d, TraceLogger.parseDoubleProperty("0.25", 1.0d), 1.0e-9d);
        assertEquals("invalid_number", TraceStore.get("trace.logger.suppressed.parseIntProperty.errorType"));
        assertEquals("invalid_number", TraceStore.get("trace.logger.suppressed.parseDoubleProperty.errorType"));
    }

    @Test
    void traceLoggerHashesCorrelationIdsAndSanitizesEventPayload() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/TraceLogger.java"));

        assertTrue(source.contains("hashOrEmpty(sid)"));
        assertTrue(source.contains("hashOrEmpty(trace)"));
        assertTrue(source.contains("hashOrEmpty(requestId)"));
        assertTrue(source.contains("SafeRedactor.hashValue(value)"));
        assertTrue(source.contains("sanitizeKv(kv)"));
        assertTrue(source.contains("SafeRedactor.diagnosticValue(\"trace.\" + key, e.getValue(), 2048)"));
        assertFalse(source.contains("Double.parseDouble(System.getProperty(\"lms.trace.sample\""));
        assertFalse(source.contains("Integer.parseInt(System.getProperty(\"lms.trace.preview\""));
    }

    @Test
    void orchestrationHotspotFailuresUseStableOperationalLabels() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/trace/OrchestrationHotspotAspect.java"));

        assertFalse(source.contains("err.getClass().getSimpleName()"),
                "hotspot telemetry must not place Java exception class names into event payloads");
        assertTrue(source.contains("kv.put(\"error\", \"model_route_failed\")"));
        assertTrue(source.contains("kv.put(\"error\", \"router_policy_failed\")"));
        assertTrue(source.contains("kv.put(\"error\", \"moe_select_failed\")"));
        assertTrue(source.contains("kv.put(\"error\", \"evidence_gate_failed\")"));
    }

}
