package com.example.lms.uaw.autolearn;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawAutolearnRedactionContractTest {

    @Test
    void uawFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/uaw/selfclean/UawSelfCleanOrchestrator.java"),
                Path.of("main/java/com/example/lms/uaw/autolearn/UawSeedSampler.java"),
                Path.of("main/java/com/example/lms/uaw/autolearn/AutoLearnRunStateStore.java"),
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnQualityTracker.java"),
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"),
                Path.of("main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"),
                Path.of("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertEquals(List.of(), rawThrowableLogLines, source + " logs raw throwable messages");
        }
    }

    @Test
    void uawStateAndTraceDiagnosticsAvoidRawPathsAndThrowableStrings() throws Exception {
        String stateStore = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/AutoLearnRunStateStore.java"),
                StandardCharsets.UTF_8);
        String traceAttribution = Files.readString(
                Path.of("main/java/com/example/lms/trace/attribution/TraceAblationAttributionService.java"),
                StandardCharsets.UTF_8);

        assertFalse(stateStore.contains("budget state {}"));
        assertTrue(stateStore.contains("pathHash={} pathLength={}"));
        assertFalse(traceAttribution.contains("Map.of(\"error\", e.toString())"));
        assertFalse(traceAttribution.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(traceAttribution.contains("analysis failed failureClass={} errorType={}"));

        String trainIngest = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"),
                StandardCharsets.UTF_8);
        assertFalse(trainIngest.contains("train jsonl not found: {}"));
        assertTrue(trainIngest.contains("train jsonl not found pathHash={} pathLength={}"));
    }

    @Test
    void uawStateAndIngestFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String stateStore = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/AutoLearnRunStateStore.java"),
                StandardCharsets.UTF_8);
        String trainIngest = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"),
                StandardCharsets.UTF_8);

        assertFalse(stateStore.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(stateStore.contains("failed to load budget state pathHash={} pathLength={} errorHash={} errorLength={}"));
        assertTrue(stateStore.contains("failed to save budget state pathHash={} pathLength={} errorHash={} errorLength={}"));
        assertTrue(stateStore.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        assertFalse(trainIngest.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(trainIngest.contains("ingest error. errorHash={} errorLength={}"));
        assertTrue(trainIngest.contains("vector upsert failed. errorHash={} errorLength={}"));
        assertTrue(trainIngest.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(trainIngest.contains("train json parse skipped lineLength={}"));
        assertTrue(trainIngest.contains("train numeric parse skipped valueLength={}"));
        assertTrue(trainIngest.contains("ingest state load skipped pathHash={} pathLength={} errorHash={} errorLength={}"));
        assertTrue(trainIngest.contains("utf8 line read skipped errorHash={} errorLength={}"));
        assertTrue(trainIngest.contains("train sample hash fallback errorHash={} errorLength={}"));
    }

    @Test
    void uawSelfCleanAndSeedFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String selfClean = Files.readString(
                Path.of("main/java/com/example/lms/uaw/selfclean/UawSelfCleanOrchestrator.java"),
                StandardCharsets.UTF_8);
        String seedSampler = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawSeedSampler.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(selfClean, seedSampler)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
            assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        }
        assertTrue(selfClean.contains("self-clean tick failed. errorHash={} errorLength={}"));
        assertTrue(selfClean.contains("self-clean rebuild failed. errorHash={} errorLength={}"));
        assertTrue(seedSampler.contains("seed sampling failed; fallback. errorHash={} errorLength={}"));
    }

    @Test
    void uawQualityTrackerOpsLedgerLogsUseHashAndLengthOnly() throws Exception {
        String tracker = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnQualityTracker.java"),
                StandardCharsets.UTF_8);

        assertFalse(tracker.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(tracker.contains("AutoLearn capture hook skipped. errorHash={} errorLength={}"));
        assertTrue(tracker.contains("AutoLearn diagnostic hook skipped. errorHash={} errorLength={}"));
        assertTrue(tracker.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(tracker.contains("[AWX][uaw][quality] blackbox refresh skipped where={}"));
        assertTrue(tracker.contains("TraceStore.put(\"uaw.autolearn.quality.suppressed.traceDouble\","));
    }

    @Test
    void uawNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String tracker = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnQualityTracker.java"),
                StandardCharsets.UTF_8);
        String validation = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/LearningSampleValidationMetadataBuilder.java"),
                StandardCharsets.UTF_8);
        String trainIngest = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(tracker, "private static double traceDouble(String key)");
        assertParserCatchNarrowed(validation, "private static double readDouble(String key, double def)");
        assertParserCatchNarrowed(trainIngest, "private static double readDouble(Object value, double fallback)");
        assertTrue(validation.contains("[AWX][uaw][learning] validation trace emission skipped"));
        assertTrue(validation.contains("TraceStore.put(\"learning.validation.suppressed.readDouble\", true)"));
    }

    @Test
    void uawOrchestratorFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String orchestrator = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(orchestrator.contains("SafeRedactor.safeMessage(String.valueOf(sc), 180)"));
        assertFalse(orchestrator.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(orchestrator.contains("self-clean invocation failed (fail-soft). errorHash={} errorLength={}"));
        assertTrue(orchestrator.contains("tick error. errorHash={} errorLength={}"));
        assertTrue(orchestrator.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void uawOrchestratorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW autolearn orchestrator needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void trainRagIngestDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "train RAG ingest fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void uawHandoffAndDatasetErrorsDoNotPersistThrowableToString() throws Exception {
        String handoffWriter = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java"),
                StandardCharsets.UTF_8);
        String autolearnService = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnService.java"),
                StandardCharsets.UTF_8);
        String datasetWriter = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawDatasetWriter.java"),
                StandardCharsets.UTF_8);

        assertFalse(handoffWriter.contains("SafeRedactor.safeMessage(e.toString(),"));
        assertFalse(autolearnService.contains("SafeRedactor.safeMessage(e.toString(),"));
        assertFalse(datasetWriter.contains("SafeRedactor.safeMessage(e.toString(),"));
        assertFalse(handoffWriter.contains(
                "TraceStore.put(\"uaw.agent.handoff.error\", SafeRedactor.safeMessage(e.getMessage(), 240))"));
        assertFalse(autolearnService.contains(
                "TraceStore.put(\"uaw.agent.handoff.error\", com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 240))"));
        assertTrue(handoffWriter.contains(
                "TraceStore.put(\"uaw.agent.handoff.error\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
        assertTrue(autolearnService.contains(
                "TraceStore.put(\"uaw.agent.handoff.error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"))"));
        assertTrue(autolearnService.contains("traceSuppressed(\"seed.sampler\")"));
        assertTrue(autolearnService.contains("traceSuppressed(\"externalQuotaGuard.resolve\")"));
        assertTrue(autolearnService.contains("traceSuppressed(\"cycleSummary.trace\")"));
        assertTrue(autolearnService.contains("traceSuppressed(\"sampleHash.digest\")"));
        assertFalse(handoffWriter.contains("out.put(\"error\", SafeRedactor.safeMessage(e.getMessage(), 160));"));
        assertTrue(handoffWriter.contains("out.put(\"error\", SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"));"));
        assertTrue(handoffWriter.contains("[AWX][uaw][handoff] dataset summary skipped path redacted"));
        assertTrue(datasetWriter.contains("SafeRedactor.safeMessage(e.getMessage(), 240)"));
        assertTrue(datasetWriter.contains("[AWX][uaw][dataset] append failed path redacted"));
        assertTrue(datasetWriter.contains("[AWX][uaw][dataset] sample id hash fallback"));
        assertTrue(datasetWriter.contains("[AWX][uaw][dataset] failure trace skipped"));
        assertTrue(datasetWriter.contains("[AWX][uaw][dataset] success trace skipped"));
    }

    @Test
    void uawHandoffCycleDiagnosticsDoNotWriteRawDatasetFileName() throws Exception {
        String handoffWriter = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java"),
                StandardCharsets.UTF_8);

        assertFalse(handoffWriter.contains("node.put(\"datasetFile\", fileName(datasetPath));"));
        assertTrue(handoffWriter.contains("node.put(\"datasetFileHash\", fileNameHash(datasetPath));"));
        assertTrue(handoffWriter.contains("node.put(\"datasetFileLength\", fileNameLength(datasetPath));"));
    }

    @Test
    void uawDatasetFilterHashFallbackDoesNotExposeSampleContent() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawDatasetTrainingDataFilter.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("[AWX][uaw][training-filter] sample hash fallback errorType={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void uawOrchestratorDiagnosticsDoNotWriteRawDatasetFileName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("datasetFile={}, datasetPathHash={}"));
        assertFalse(source.contains("datasetFileName(result.datasetPath()),"));
        assertFalse(source.contains("data.put(\"datasetFile\", datasetFileName(path));"));
        assertTrue(source.contains("datasetFileHash={}, datasetFileLength={}, datasetPathHash={}"));
        assertTrue(source.contains("datasetFileHash(result.datasetPath()), datasetFileLength(result.datasetPath()),"));
        assertTrue(source.contains("data.put(\"datasetFileHash\", datasetFileHash(path));"));
        assertTrue(source.contains("data.put(\"datasetFileLength\", datasetFileLength(path));"));
    }

    @Test
    void uawOrchestratorDiagnosticsSafeHelperUsesTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/autolearn/UawAutolearnOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("return value == null ? \"\" : value;"));
        assertTrue(source.contains("import com.example.lms.trace.SafeRedactor;"));
        assertTrue(source.contains("return value == null ? \"\" : SafeRedactor.traceLabel(value);"));
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
