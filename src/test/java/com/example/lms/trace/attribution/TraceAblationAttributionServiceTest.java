package com.example.lms.trace.attribution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceAblationAttributionServiceTest {

    @Test
    void analyzeReturnsErrorResultWhenFailureMessageIsMissing() {
        Map<String, Object> trace = new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                throw new NullPointerException();
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of(Map.entry("trigger", "failure"));
            }
        };

        TraceAblationAttributionResult result = assertDoesNotThrow(
                () -> new TraceAblationAttributionService().analyze(trace, null, null));

        assertEquals("ERROR", result.outcome());
        assertNotNull(result.debug().get("error"));
    }

    @Test
    void analyzeFailureLogAndDebugDoNotRenderRawThrowableMessage() {
        Map<String, Object> trace = new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                throw new RuntimeException("private taa raw query ownerToken=secret");
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of(Map.entry("trigger", "failure"));
            }
        };

        Logger logger = (Logger) LoggerFactory.getLogger(TraceAblationAttributionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            TraceAblationAttributionResult result = assertDoesNotThrow(
                    () -> new TraceAblationAttributionService().analyze(trace, null, null));

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertEquals("ERROR", result.outcome());
            assertTrue(rendered.contains("[taa] analysis failed"));
            assertTrue(rendered.contains("failureClass="));
            assertFalse(rendered.contains("private taa raw query"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(String.valueOf(result.debug()).contains("private taa raw query"));
            assertFalse(String.valueOf(result.debug()).contains("ownerToken"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void analyzeAttributesTavilyAwaitSkipAsWebProviderFinding() {
        TraceAblationAttributionResult result = new TraceAblationAttributionService().analyze(
                Map.of("web.await.skipped.Tavily.count", 2),
                null,
                null);

        assertTrue(result.contributors().stream().anyMatch(c ->
                "web.await_skipped_tavily".equals(c.id())
                        && "WEB".equals(c.group())
                        && c.evidence().contains("web.await.skipped.Tavily.count=2")));
    }

    @Test
    void analyzeUsesLastStageCountsFallbackForStarvationEvidence() {
        Map<String, Object> stageCounts = new java.util.LinkedHashMap<>();
        stageCounts.put("NOFILTER_SAFE", 2);
        stageCounts.put("OFFICIAL", 0);
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("web.failsoft.starvationFallback.used", true);
        trace.put("web.failsoft.stageCountsSelectedFromOut.last", stageCounts);

        TraceAblationAttributionResult result = new TraceAblationAttributionService().analyze(trace, null, null);

        assertTrue(result.contributors().stream().anyMatch(c ->
                "web.starvation_failsoft".equals(c.id())
                        && c.evidence().contains("web.failsoft.stageCountsSelectedFromOut={NOFILTER_SAFE=2, OFFICIAL=0}")),
                String.valueOf(result.contributors()));
    }

    @Test
    void analyzeUsesCanonicalStarvationFallbackKeysForAttribution() {
        Map<String, Object> stageCounts = new java.util.LinkedHashMap<>();
        stageCounts.put("NOFILTER_SAFE", 1);
        stageCounts.put("OFFICIAL", 0);
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("starvationFallback.used", true);
        trace.put("starvationFallback.trigger", "officialOnly->NOFILTER_SAFE");
        trace.put("stageCountsSelectedFromOut", stageCounts);

        TraceAblationAttributionResult result = new TraceAblationAttributionService().analyze(trace, null, null);

        assertEquals("WEB_FAILSOFT", result.outcome());
        assertTrue(result.contributors().stream().anyMatch(c ->
                "web.starvation_failsoft".equals(c.id())
                        && c.evidence().contains("web.failsoft.starvationFallback.used=true")
                        && c.evidence().contains("web.failsoft.starvationFallback=officialOnly->NOFILTER_SAFE")
                        && c.evidence().contains("web.failsoft.stageCountsSelectedFromOut={NOFILTER_SAFE=1, OFFICIAL=0}")),
                String.valueOf(result.contributors()));
    }

    @Test
    void traceAblationAttributionServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "TAA advisory trace hooks need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Exception ignore) {\n            return def;\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignore) {"));
        assertTrue(source.contains("TraceStore.put(\"trace.attribution.suppressed.intParse\", true)"));
        assertTrue(source.contains("TraceStore.put(\"trace.attribution.suppressed.doubleParse\", true)"));
        assertTrue(source.contains("return def;"));
    }

    @Test
    void numericFallbacksUseStableInvalidNumberLabels() throws Exception {
        TraceStore.clear();
        Method getInt = TraceAblationAttributionService.class.getDeclaredMethod(
                "getInt", Map.class, String.class, int.class);
        Method getDouble = TraceAblationAttributionService.class.getDeclaredMethod(
                "getDouble", Map.class, String.class, double.class);
        getInt.setAccessible(true);
        getDouble.setAccessible(true);

        assertEquals(7, getInt.invoke(null, Map.of("value", "bad-int"), "value", 7));
        assertEquals(0.5d, (Double) getDouble.invoke(null, Map.of("value", "bad-double"), "value", 0.5d), 1.0e-9d);
        assertEquals("invalid_number", TraceStore.get("trace.attribution.suppressed.intParse.errorType"));
        assertEquals("invalid_number", TraceStore.get("trace.attribution.suppressed.doubleParse.errorType"));

        TraceStore.clear();

        assertEquals(7, getInt.invoke(null, Map.of("value", Double.POSITIVE_INFINITY), "value", 7));
        assertEquals(0.5d, (Double) getDouble.invoke(null, Map.of("value", Double.NaN), "value", 0.5d), 1.0e-9d);
        assertEquals("invalid_number", TraceStore.get("trace.attribution.suppressed.intParse.errorType"));
        assertEquals("invalid_number", TraceStore.get("trace.attribution.suppressed.doubleParse.errorType"));
    }
}
