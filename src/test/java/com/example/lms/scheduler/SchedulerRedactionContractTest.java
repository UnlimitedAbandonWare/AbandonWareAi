package com.example.lms.scheduler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerRedactionContractTest {

    @Test
    void schedulerFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/scheduler/TrainingJobRunner.java"),
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                Path.of("main/java/com/example/lms/scheduler/UserIdleDetector.java"),
                Path.of("main/java/com/example/lms/scheduler/AutoEvolveScheduler.java"),
                Path.of("main/java/com/example/lms/scheduler/IndexingScheduler.java"),
                Path.of("main/java/com/example/lms/scheduler/QuarantineReprocessScheduler.java"),
                Path.of("main/java/com/example/lms/scheduler/VectorStoreBufferScheduler.java"),
                Path.of("main/java/com/example/lms/scheduler/WhiteningRefitScheduler.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()")
                            || line.contains(".toString()")
                            || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertEquals(List.of(), rawThrowableLogLines, source + " logs raw throwable messages");
        }
    }

    @Test
    void schedulerFailureLogsUseHashOnlySessionIdentifiers() throws Exception {
        String training = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/TrainingJobRunner.java"),
                StandardCharsets.UTF_8);
        String pending = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                StandardCharsets.UTF_8);

        assertFalse(training.contains("start trigger={} sessionId={}"));
        assertFalse(training.contains("end sessionId={} outcome={}"));
        assertFalse(training.contains("failed sessionId={} err={}"));
        assertFalse(training.contains("\"sessionId\", sessionId"));
        assertTrue(training.contains("start trigger={} sessionHash={}"));
        assertTrue(training.contains("end sessionHash={} outcome={}"));
        assertTrue(training.contains("failed sessionHash={} err={}"));
        assertTrue(training.contains("\"sessionHash\", SafeRedactor.hashValue(sessionId)"));
        assertFalse(pending.contains("id={} session={}"));
        assertTrue(pending.contains("idHash={} sessionHash={}"));
    }

    @Test
    void smallSchedulerFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String vector = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/VectorStoreBufferScheduler.java"),
                StandardCharsets.UTF_8);
        String whitening = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/WhiteningRefitScheduler.java"),
                StandardCharsets.UTF_8);
        String idle = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/UserIdleDetector.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(vector, whitening, idle)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
            assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        }
        assertTrue(vector.contains("[VectorStore] flush failed. errorHash={} errorLength={}"));
        assertTrue(whitening.contains("[MP] whitening refit failed. errorHash={} errorLength={}"));
        assertTrue(idle.contains("[AutoEvolve] idle detection failed. errorHash={} errorLength={}"));
    }

    @Test
    void memorySchedulerFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String pending = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                StandardCharsets.UTF_8);
        String quarantine = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/QuarantineReprocessScheduler.java"),
                StandardCharsets.UTF_8);
        String autoEvolve = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/AutoEvolveScheduler.java"),
                StandardCharsets.UTF_8);
        String indexing = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/IndexingScheduler.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(pending, quarantine, autoEvolve, indexing)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf("));
            assertTrue(source.contains("errorHash={} errorLength={}"));
            assertTrue(source.contains("messageLength("));
        }
        assertTrue(pending.contains("[PENDING_SOAK] guard error; skip promote idHash={} sessionHash={} errorHash={} errorLength={}"));
        assertTrue(pending.contains("[PENDING_SOAK] enqueue failed idHash={} errorHash={} errorLength={}"));
        assertTrue(pending.contains("[PENDING_SOAK] failed errorHash={} errorLength={}"));
        assertTrue(quarantine.contains("[QUARANTINE_REPROCESS] failed errorHash={} errorLength={}"));
        assertTrue(autoEvolve.contains("[AutoEvolve] scheduler errorHash={} errorLength={}"));
        assertTrue(indexing.contains("[Indexing] vector store load failed errorHash={} errorLength={}"));
    }

    @Test
    void quarantineReprocessLogsUseHashOnlyMemoryIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/QuarantineReprocessScheduler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("-> PENDING id={} sid={}"));
        assertTrue(source.contains("-> PENDING idHash={} sidHash={}"));
    }

    @Test
    void autoEvolveStatusUsesHashOnlySessionAndPathMetadata() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/internal/AutoEvolveApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"currentSessionId\", runner.currentSessionId());"));
        assertFalse(source.contains("cfg.put(\"logPath\", moeProps.getLogPath());"));
        assertFalse(source.contains("cfg.put(\"soakReportDir\", moeProps.getSoakReportDir());"));
        assertFalse(source.contains("cfg.put(\"debugPersistDir\", moeProps.getDebug() == null ? null : moeProps.getDebug().getPersistDir());"));
        assertFalse(source.contains("persist.put(\"dir\", debugStore.persistDirectory());"));
        assertFalse(source.contains("persist.put(\"ndjsonPath\", debugStore.ndjsonPath());"));
        assertFalse(source.contains("persist.put(\"indexPath\", debugStore.indexPath());"));
        assertTrue(source.contains("out.put(\"currentSessionHash\", hashOrEmpty(currentSessionId));"));
        assertTrue(source.contains("out.put(\"currentSessionPresent\", currentSessionId != null && !currentSessionId.isBlank());"));
        assertTrue(source.contains("cfg.put(\"logPath\", pathSummary(moeProps.getLogPath()));"));
        assertTrue(source.contains("cfg.put(\"soakReportDir\", pathSummary(moeProps.getSoakReportDir()));"));
        assertTrue(source.contains("cfg.put(\"debugPersistDir\", pathSummary(moeProps.getDebug() == null ? null : moeProps.getDebug().getPersistDir()));"));
        assertTrue(source.contains("persist.put(\"dir\", pathSummary(debugStore.persistDirectory()));"));
        assertTrue(source.contains("persist.put(\"ndjsonPath\", pathSummary(debugStore.ndjsonPath()));"));
        assertTrue(source.contains("persist.put(\"indexPath\", pathSummary(debugStore.indexPath()));"));
        assertTrue(source.contains("out.put(\"hash\", hashOrEmpty(value));"));
        assertTrue(source.contains("SafeRedactor.hashValue(value)"));
    }

    @Test
    void autoEvolveStatusUsesStableEmptyHashesForMissingOptionalMetadata() {
        TrainingJobRunner runner = org.mockito.Mockito.mock(TrainingJobRunner.class);
        org.mockito.Mockito.when(runner.currentSessionId()).thenReturn(null);
        org.mockito.Mockito.when(runner.isRunning()).thenReturn(false);
        com.example.lms.moe.RgbMoeProperties props = new com.example.lms.moe.RgbMoeProperties();
        props.setLogPath(null);
        props.setSoakReportDir(null);
        props.getDebug().setPersistDir(null);

        AutoEvolveDebugStore debugStore = new AutoEvolveDebugStore(
                props,
                new com.fasterxml.jackson.databind.ObjectMapper());
        com.example.lms.api.internal.AutoEvolveApiController controller =
                new com.example.lms.api.internal.AutoEvolveApiController(runner, debugStore, props);

        org.springframework.http.ResponseEntity<Map<String, Object>> response = controller.status(10);
        Map<String, Object> body = response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals(false, body.get("currentSessionPresent"));
        assertEquals("", body.get("currentSessionHash"));
        Map<?, ?> config = (Map<?, ?>) body.get("config");
        Map<?, ?> logPath = (Map<?, ?>) config.get("logPath");
        Map<?, ?> soakReportDir = (Map<?, ?>) config.get("soakReportDir");
        Map<?, ?> debugPersistDir = (Map<?, ?>) config.get("debugPersistDir");
        assertEquals(false, logPath.get("present"));
        assertEquals("", logPath.get("hash"));
        assertEquals(0, logPath.get("length"));
        assertEquals(false, soakReportDir.get("present"));
        assertEquals("", soakReportDir.get("hash"));
        assertEquals(0, soakReportDir.get("length"));
        assertEquals(false, debugPersistDir.get("present"));
        assertEquals("", debugPersistDir.get("hash"));
        assertEquals(0, debugPersistDir.get("length"));
    }

    @Test
    void autoEvolveIndexEntriesUseHashOnlySessionIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/AutoEvolveRunIndexEntry.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String sessionId,"));
        assertFalse(source.contains("d.sessionId(),"));
        assertTrue(source.contains("String sessionHash,"));
        assertTrue(source.contains("SafeRedactor.hashValue(d.sessionId()),"));
    }

    @Test
    void schedulerFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String index = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/AutoEvolveRunIndexEntry.java"),
                StandardCharsets.UTF_8);
        String pending = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                StandardCharsets.UTF_8);
        String quarantine = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/QuarantineReprocessScheduler.java"),
                StandardCharsets.UTF_8);
        String idle = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/UserIdleDetector.java"),
                StandardCharsets.UTF_8);
        String vectorDlq = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/VectorDlqRedriveScheduler.java"),
                StandardCharsets.UTF_8);
        String training = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/TrainingJobRunner.java"),
                StandardCharsets.UTF_8);

        assertTrue(index.contains("TraceStore.put(\"autoevolve.index.suppressed.primaryStrategy\", true);"));
        assertTrue(pending.contains("traceSuppressed(\"sidRotation.maxAge\", ignore);"));
        assertTrue(pending.contains("traceSuppressed(\"sidRotation.poison\", ignore);"));
        assertTrue(pending.contains("traceSuppressed(\"sidRotation.weakEvidence\", ignore);"));
        assertTrue(quarantine.contains("traceSuppressed(\"sidRotation.poison\", ignore);"));
        assertTrue(quarantine.contains("traceSuppressed(\"poisonGuard\", guardErr);"));
        assertTrue(quarantine.contains("traceSuppressed(\"releaseLease\", ignore);"));
        assertTrue(idle.contains("traceSuppressed(\"idleWindow.parse\", e);"));
        assertTrue(vectorDlq.contains("traceSuppressed(\"clearIngestProtection\", ignore);"));
        assertTrue(training.contains("traceSuppressed(\"greenExpansion\", e);"));
        assertTrue(training.contains("traceSuppressed(\"blue.retryAfter\", ignore);"));
        assertTrue(training.contains("traceSuppressed(\"blue.bodyPreview\", ignore);"));
        assertTrue(training.contains("traceSuppressed(\"blue.failure\", e);"));
    }

    @Test
    void autoEvolveHistoryEndpointsReturnSafeRunSummaries() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/internal/AutoEvolveApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"last\", debugStore.last());"));
        assertFalse(source.contains("out.put(\"recent\", debugStore.recent(historyLimit));"));
        assertFalse(source.contains("return ResponseEntity.ok(debugStore.recent(limit));"));
        assertTrue(source.contains("out.put(\"last\", runSummary(debugStore.last()));"));
        assertTrue(source.contains("debugStore.recent(historyLimit).stream().map(AutoEvolveApiController::runSummary).toList()"));
        assertTrue(source.contains("return ResponseEntity.ok(debugStore.recent(limit).stream().map(AutoEvolveApiController::runSummary).toList());"));
        assertTrue(source.contains("out.put(\"baseQueryHashes\", hashList(d.baseQueries()));"));
        assertTrue(source.contains("out.put(\"finalQueryHashes\", hashList(d.finalQueries()));"));
        assertTrue(source.contains("out.put(\"reportFile\", pathSummary(d.reportFile()));"));
        assertTrue(source.contains("out.put(\"responseBodyPreviewHash\", SafeRedactor.hashValue(blue.responseBodyPreview()));"));
    }

    @Test
    void autoEvolveRunSummariesDoNotExposeRawErrorMessagePreviews() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/internal/AutoEvolveApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"errorMessage\", SafeRedactor.safeMessage(d.errorMessage(), 180));"));
        assertFalse(source.contains("out.put(\"errorMessage\", SafeRedactor.safeMessage(blue.errorMessage(), 180));"));
        assertTrue(source.contains("out.put(\"errorMessage\", SafeRedactor.diagnosticValue(\"autoevolve.run.errorMessage\", d.errorMessage(), 180));"));
        assertTrue(source.contains("out.put(\"errorMessage\", SafeRedactor.diagnosticValue(\"autoevolve.blue.errorMessage\", blue.errorMessage(), 180));"));
    }

    @Test
    void autoEvolvePreviewEndpointReturnsSafeQuerySummary() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/internal/AutoEvolveApiController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("public ResponseEntity<AutoEvolvePreview> preview("));
        assertFalse(source.contains("return ResponseEntity.ok(runner.preview(requireIdle));"));
        assertTrue(source.contains("public ResponseEntity<Map<String, Object>> preview("));
        assertTrue(source.contains("return ResponseEntity.ok(previewSummary(runner.preview(requireIdle)));"));
        assertTrue(source.contains("private static Map<String, Object> previewSummary(AutoEvolvePreview p)"));
        assertTrue(source.contains("out.put(\"baseQueryCount\", p.baseQueries() == null ? 0 : p.baseQueries().size());"));
        assertTrue(source.contains("out.put(\"baseQueryHashes\", hashList(p.baseQueries()));"));
        assertFalse(source.contains("out.put(\"blueBlockedReason\", SafeRedactor.safeMessage(p.blueBlockedReason(), 160));"));
        assertTrue(source.contains("out.put(\"blueBlockedReason\", SafeRedactor.traceLabelOrFallback(p.blueBlockedReason(), \"unknown\"));"));
    }
}
