package com.example.lms.service.routing;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyBasedModelRouterRedactionContractTest {

    @Test
    void policyBasedModelRouterDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "model router fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void intentParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"));
        String parserCall = "return RouteSignal.Intent.valueOf(t);";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "intent enum parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 260));
        assertFalse(window.contains("catch (Exception"),
                "intent parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "intent parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "intent parser should only catch IllegalArgumentException");
    }

    @Test
    void routerFallbacksExposeScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"));

        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.policyPromote\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.ragEvent\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.intentParse\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.requestedBuildFailedTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.requestedProviderNotConfiguredTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.suppressed.promoteBlockedTrace\", true)"));
        assertTrue(source.contains("ModelRouterTraceSuppressions.trace(\"policy.promote\", e);"));
        assertTrue(source.contains("ModelRouterTraceSuppressions.trace(\"rag.event\", ignore);"));
    }

    @Test
    void routerTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        ModelRouterTraceSuppressions.trace("requestedBuildFailed.trace", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("ml.router.suppressed.requestedBuildFailed.trace"));
        assertEquals("invalid_number", TraceStore.get("ml.router.suppressed.requestedBuildFailed.trace.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void invalidIntentFallbackPublishesStableErrorType() throws Exception {
        TraceStore.clear();
        Method parseIntent = PolicyBasedModelRouter.class.getDeclaredMethod("parseIntent", String.class);
        parseIntent.setAccessible(true);

        Object intent = parseIntent.invoke(null, "ownerToken-secret-not-a-real-intent");

        assertEquals(RouteSignal.Intent.GENERAL, intent);
        assertEquals(Boolean.TRUE, TraceStore.get("ml.router.suppressed.intentParse"));
        assertEquals("IllegalArgumentException", TraceStore.get("ml.router.suppressed.intentParse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-secret-not-a-real-intent"));
    }

    @Test
    void requestedBuildFailureTelemetryUsesStableErrorLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java"));

        assertFalse(source.contains(
                "TraceStore.put(\"ml.router.requestedModel.buildFailed.error\", e.getClass().getSimpleName());"));
        assertFalse(source.contains("ev.put(\"error\", e.getClass().getSimpleName());"));
        assertFalse(source.contains("dd.put(\"error\", e.getClass().getSimpleName());"));
        assertTrue(source.contains("String buildFailureLabel = \"requested_model_build_failed\";"));
        assertTrue(source.contains("TraceStore.put(\"ml.router.requestedModel.buildFailed.error\", buildFailureLabel);"));
        assertTrue(source.contains("ev.put(\"error\", buildFailureLabel);"));
        assertTrue(source.contains("dd.put(\"error\", buildFailureLabel);"));
    }
}
