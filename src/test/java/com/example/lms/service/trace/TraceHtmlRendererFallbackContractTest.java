package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceHtmlRendererFallbackContractTest {

    @Test
    void traceHtmlRendererParsersExposeBreadcrumbs() throws Exception {
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlContentListRenderer.java",
                "traceSuppressed(\"host\", e);");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlOrchestrationModeCalloutRenderer.java",
                "TraceStore.put(\"traceHtml.orchestrationMode.suppressed.toInt\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlRelationThumbnailCallouts.java",
                "TraceStore.put(\"traceHtml.relationThumbnail.suppressed.toInt\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlSoakWebKpiCopyCalloutRenderer.java",
                "TraceStore.put(\"traceHtml.soakWebKpi.suppressed.parseRunId\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlWebAwaitEventsRenderer.java",
                "TraceStore.put(\"traceHtml.webAwait.suppressed.toLong\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlWebFailSoftRiskCalloutRenderer.java",
                "TraceStore.put(\"traceHtml.webFailSoftRisk.suppressed.render\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlWebFailSoftRiskCalloutRenderer.java",
                "TraceStore.put(\"traceHtml.webFailSoftRisk.suppressed.toLong\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlWebFailSoftRunsRenderer.java",
                "TraceStore.put(\"traceHtml.webFailSoftRuns.suppressed.nofilterSafeCount\", true)");
        assertSourceContains("main/java/com/example/lms/service/trace/TraceHtmlWebFailSoftRunsRenderer.java",
                "TraceStore.put(\"traceHtml.webFailSoftRuns.suppressed.toLong\", true)");
    }

    @Test
    void generativeUiSanitizerFallbacksExposeBreadcrumbs() throws Exception {
        assertSourceContains("main/java/com/example/lms/service/ui/DefaultGenerativeUiService.java",
                "TraceStore.put(\"generativeUi.suppressed.jsonParse\", true)");
        assertSourceContains("main/java/com/example/lms/service/ui/DefaultGenerativeUiService.java",
                "TraceStore.put(\"generativeUi.suppressed.rawSanitize\", true)");
    }

    @Test
    void traceHtmlNumericParserBreadcrumbsUseStableInvalidNumberLabel() throws Exception {
        assertNumericParserBreadcrumb(TraceHtmlOrchestrationModeCalloutRenderer.class, "toInt",
                "traceHtml.orchestrationMode.suppressed.toInt.errorType");
        assertNumericParserBreadcrumb(TraceHtmlRelationThumbnailCallouts.class, "toInt",
                "traceHtml.relationThumbnail.suppressed.toInt.errorType");
        assertNumericParserBreadcrumb(TraceHtmlSoakWebKpiCopyCalloutRenderer.class, "parseRunId",
                "traceHtml.soakWebKpi.suppressed.parseRunId.errorType");
        assertNumericParserBreadcrumb(TraceHtmlWebAwaitEventsRenderer.class, "toLong",
                "traceHtml.webAwait.suppressed.toLong.errorType");
        assertNumericParserBreadcrumb(TraceHtmlWebFailSoftRiskCalloutRenderer.class, "toLong",
                "traceHtml.webFailSoftRisk.suppressed.toLong.errorType");
        assertNumericParserBreadcrumb(TraceHtmlWebFailSoftRunsRenderer.class, "toLong",
                "traceHtml.webFailSoftRuns.suppressed.toLong.errorType");
    }

    @Test
    void traceHtmlLongParsersRejectNonFiniteNumbers() throws Exception {
        assertNonFiniteParserFallback(TraceHtmlOrchestrationModeCalloutRenderer.class, "toInt", null,
                "traceHtml.orchestrationMode.suppressed.toInt.errorType");
        assertNonFiniteParserFallback(TraceHtmlRelationThumbnailCallouts.class, "toInt", null,
                "traceHtml.relationThumbnail.suppressed.toInt.errorType");
        assertNonFiniteLongParserFallback(TraceHtmlWebAwaitEventsRenderer.class, null,
                "traceHtml.webAwait.suppressed.toLong.errorType");
        assertNonFiniteLongParserFallback(TraceHtmlWebFailSoftRunsRenderer.class, null,
                "traceHtml.webFailSoftRuns.suppressed.toLong.errorType");
        assertNonFiniteLongParserFallback(TraceHtmlWebFailSoftRiskCalloutRenderer.class, 0L,
                "traceHtml.webFailSoftRisk.suppressed.toLong.errorType");
    }

    private static void assertSourceContains(String file, String needle) throws Exception {
        assertTrue(Files.readString(Path.of(file)).contains(needle), file + " missing " + needle);
    }

    private static void assertNumericParserBreadcrumb(Class<?> type, String methodName, String errorTypeKey)
            throws Exception {
        TraceStore.clear();
        Method method;
        Object value = "not-a-number";
        try {
            method = type.getDeclaredMethod(methodName, Object.class);
        } catch (NoSuchMethodException ex) {
            method = type.getDeclaredMethod(methodName, String.class);
            value = "web.failsoft.soakKpiJson.runId.not-a-number";
        }
        method.setAccessible(true);
        method.invoke(null, value);
        assertEquals("invalid_number", TraceStore.get(errorTypeKey), errorTypeKey);
    }

    private static void assertNonFiniteLongParserFallback(Class<?> type, Object expected, String errorTypeKey)
            throws Exception {
        assertNonFiniteParserFallback(type, "toLong", expected, errorTypeKey);
    }

    private static void assertNonFiniteParserFallback(Class<?> type, String methodName, Object expected,
                                                      String errorTypeKey)
            throws Exception {
        TraceStore.clear();
        Method method = type.getDeclaredMethod(methodName, Object.class);
        method.setAccessible(true);
        Object result = method.invoke(null, Double.POSITIVE_INFINITY);
        assertEquals(expected, result, type.getSimpleName());
        assertEquals("invalid_number", TraceStore.get(errorTypeKey), errorTypeKey);
    }
}
