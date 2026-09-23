package com.example.lms.service.trace;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalFallbackBreadcrumbContractTest {

    @Test
    void soakFallbacksExposeBreadcrumbs() throws Exception {
        String soak = Files.readString(Path.of(
                "main/java/com/example/lms/service/soak/DefaultSoakTestService.java"));
        String summary = Files.readString(Path.of(
                "main/java/com/example/lms/service/soak/metrics/SoakWebKpiMinuteSummaryLogger.java"));
        String runner = Files.readString(Path.of(
                "main/java/com/example/lms/service/soak/runner/SoakQuickRunner.java"));

        assertTrue(soak.contains("traceSuppressed(\"runSearch\", \"run_search\", error);"));
        assertTrue(soak.contains("traceSuppressed(\"quickSearch\", \"quick_search\", e);"));
        assertTrue(soak.contains("traceSuppressed(\"datasetIngest\", \"dataset_ingest\", error);"));
        assertTrue(soak.contains("traceSuppressed(\"jsonlExport\", \"jsonl_export\", error);"));
        assertTrue(soak.contains("String type = suppressedErrorType(error);"));
        assertTrue(soak.contains("TraceStore.put(\"soak.default.suppressed.\" + traceStage + \".errorType\", type);"));
        assertTrue(summary.contains("TraceStore.put(\"soak.webKpi.suppressed.record\", true)"));
        assertTrue(summary.contains("TraceStore.put(\"soak.webKpi.suppressed.record.errorType\", errorType(ignore));"));
        assertTrue(summary.contains("TraceStore.put(\"soak.webKpi.suppressed.emitSummary\", true)"));
        assertTrue(summary.contains("TraceStore.put(\"soak.webKpi.suppressed.emitSummary.errorType\", errorType(ignore));"));
        assertTrue(runner.contains("TraceStore.put(\"soak.quickRunner.suppressed.providerRun\", true)"));
    }

    @Test
    void subjectAndDebugFallbacksExposeBreadcrumbs() throws Exception {
        String subject = Files.readString(Path.of(
                "main/java/com/example/lms/service/subject/SubjectResolver.java"));
        String debug = Files.readString(Path.of(
                "main/java/com/example/lms/service/trace/DebugCopilotService.java"));
        String canonicalizer = Files.readString(Path.of(
                "main/java/com/example/lms/service/service/rag/fusion/Canonicalizer.java"));

        assertTrue(subject.contains("TraceStore.put(\"subject.resolver.suppressed.entityScan\", true)"));
        assertTrue(debug.contains("traceSuppressed(\"shouldRun\", ignore);"));
        assertTrue(debug.contains("traceSuppressed(\"blackboxCause\", ignore);"));
        assertTrue(debug.contains("traceSuppressed(\"providerFailureCause\", ignore);"));
        assertTrue(debug.contains("traceSuppressed(\"ablationCause\", ignore);"));
        assertTrue(debug.contains("traceSuppressed(\"asInt\", \"invalid_number\");"));
        assertTrue(debug.contains("traceSuppressed(\"asDouble\", \"invalid_number\");"));
        assertTrue(debug.contains("TraceStore.put(\"debug.copilot.suppressed.\" + safeStage + \".errorType\", errorType);"));
        assertTrue(canonicalizer.contains("TraceStore.put(\"rag.fusion.canonicalizer.suppressed.url\", true)"));
    }
}
