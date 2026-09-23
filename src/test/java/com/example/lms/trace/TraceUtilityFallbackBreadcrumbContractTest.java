package com.example.lms.trace;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceUtilityFallbackBreadcrumbContractTest {

    @Test
    void traceUtilityFallbacksExposeBreadcrumbs() throws Exception {
        assertSourceContains("main/java/com/example/lms/trace/CloudPointerClient.java",
                "TraceStore.put(\"trace.cloudPointer.suppressed.parseInt\", true)");
        assertSourceContains("main/java/com/example/lms/trace/FailureTagNormalizer.java",
                "TraceStore.put(\"trace.failureTag.suppressed.arrayLength\", true)");
        assertSourceContains("main/java/com/example/lms/trace/OrchestrationHotspotAspect.java",
                "TraceStore.put(\"trace.orchestrationHotspot.suppressed.modelName\", true)");
        assertSourceContains("main/java/com/example/lms/trace/OrchestrationHotspotAspect.java",
                "TraceStore.put(\"trace.orchestrationHotspot.suppressed.modelName.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/PromptTraceAspect.java",
                "TraceStore.put(\"trace.promptTrace.suppressed.sha256\", true)");
        assertSourceContains("main/java/com/example/lms/trace/PromptTraceAspect.java",
                "TraceStore.put(\"trace.promptTrace.suppressed.sha256.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/SearchDebugBoost.java",
                "TraceStore.put(\"trace.searchDebugBoost.suppressed.mdc\", true)");
        assertSourceContains("main/java/com/example/lms/trace/SearchDebugBoost.java",
                "TraceStore.put(\"trace.searchDebugBoost.suppressed.mdc.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/SearchDebugBoost.java",
                "TraceStore.put(\"trace.searchDebugBoost.suppressed.log\", true)");
        assertSourceContains("main/java/com/example/lms/trace/SearchDebugBoost.java",
                "TraceStore.put(\"trace.searchDebugBoost.suppressed.log.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.boostActive\", true)");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.boostActive.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.awaitCandidateCast\", true)");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.awaitCandidateCast.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.debugEventSummary\", true)");
        assertSourceContains("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java",
                "TraceStore.put(\"trace.console.suppressed.debugEventSummary.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceLogger.java",
                "TraceStore.put(\"trace.logger.suppressed.emit\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceLogger.java",
                "TraceStore.put(\"trace.logger.suppressed.emit.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceLogger.java",
                "TraceStore.put(\"trace.logger.suppressed.parseIntProperty\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceLogger.java",
                "TraceStore.put(\"trace.logger.suppressed.parseDoubleProperty\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.htmlBuild\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.htmlBuild.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.context\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.context.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.contentText\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.contentText.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.contentHost\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.contentHost.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.scoreParse\", true)");
        assertSourceContains("main/java/com/example/lms/trace/TraceSnapshotStore.java",
                "TraceStore.put(\"trace.snapshot.suppressed.intValue\", true)");
        assertSourceContains("main/java/com/example/lms/trace/WebClientDiagnostics.java",
                "TraceStore.put(\"trace.webclient.suppressed.request\", true)");
        assertSourceContains("main/java/com/example/lms/trace/WebClientDiagnostics.java",
                "TraceStore.put(\"trace.webclient.suppressed.request.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/WebClientDiagnostics.java",
                "TraceStore.put(\"trace.webclient.suppressed.response\", true)");
        assertSourceContains("main/java/com/example/lms/trace/WebClientDiagnostics.java",
                "TraceStore.put(\"trace.webclient.suppressed.response.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.emit\", true)");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.emit.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.hostNormalize\", true)");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.hostNormalize.errorType\"");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.intParse\", true)");
        assertSourceContains("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java",
                "TraceStore.put(\"trace.attribution.suppressed.doubleParse\", true)");
    }

    private static void assertSourceContains(String file, String needle) throws Exception {
        assertTrue(Files.readString(Path.of(file)).contains(needle), file + " missing " + needle);
    }
}
