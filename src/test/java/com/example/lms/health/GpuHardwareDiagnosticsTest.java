package com.example.lms.health;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GpuHardwareDiagnosticsTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void admissionReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/health/GpuHardwareDiagnostics.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains(
                "TraceStore.put(\"uaw.gpu-hardware.admission.reason\", admission.getOrDefault(\"reason\", \"\"));"));
        assertFalse(source.contains(
                "TraceStore.put(\"uaw.gpu-hardware.admission.reason\", SafeRedactor.safeMessage(String.valueOf(admission.getOrDefault(\"reason\", \"\")), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"uaw.gpu-hardware.admission.reason\", SafeRedactor.traceLabelOrFallback(String.valueOf(admission.getOrDefault(\"reason\", \"\")), \"unknown\"));"));
    }

    @Test
    void telemetryIsDisabledByDefaultWithoutExecutingNvidiaSmi() {
        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(new MockEnvironment(), timeoutMs -> {
            fail("nvidia-smi runner should not execute when telemetry is disabled");
            return new GpuHardwareDiagnostics.CommandResult(0, "", "", false);
        });

        assertEquals("disabled_by_config", snapshot.get("status"));
        assertEquals(Boolean.FALSE, snapshot.get("available"));
        assertEquals(0, snapshot.get("detectedCount"));
        assertEquals("disabled_by_config", TraceStore.get("uaw.gpu-hardware.status"));
    }

    @Test
    void parsesRtx3060AndRtx3090SummaryWithoutRawSecretSurface() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.telemetry.enabled", "true");

        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env, timeoutMs ->
                new GpuHardwareDiagnostics.CommandResult(0, """
                        0, NVIDIA GeForce RTX 3060, 12288, 207, 0, 34, 8.13
                        1, NVIDIA GeForce RTX 3090, 24576, 3474, 25, 44, 43.39
                        """, "", false));

        assertEquals("ok", snapshot.get("status"));
        assertEquals(Boolean.TRUE, snapshot.get("available"));
        assertEquals(2, snapshot.get("detectedCount"));
        assertEquals(Boolean.TRUE, snapshot.get("hasRtx3060"));
        assertEquals(Boolean.TRUE, snapshot.get("hasRtx3090"));
        assertEquals(Boolean.TRUE, snapshot.get("heavyLaneReady"));
        assertEquals(0.1414d, (Double) snapshot.get("maxMemoryUsedRatio"), 0.00001d);
        assertEquals(25, snapshot.get("maxUtilizationGpuPct"));
        assertEquals(44, snapshot.get("maxTemperatureC"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> devices = (List<Map<String, Object>>) snapshot.get("devices");
        assertEquals("fast-helper-embedding", devices.get(0).get("role"));
        assertEquals("primary-chat-rerank-heavy", devices.get(1).get("role"));
        assertFalse(snapshot.toString().contains("owner"));
        assertEquals("ok", TraceStore.get("uaw.gpu-hardware.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.gpu-hardware.heavyLaneReady"));
        assertEquals("ok", TraceStore.get("uaw.gpu-hardware.admission.status"));
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.gpu-hardware.admission.rerankAllowed"));
    }

    @Test
    void classifiesMissingNvidiaSmiAsFailSoftCommandNotFound() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.telemetry.enabled", "true");

        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env, timeoutMs -> {
            throw new IOException("Cannot run program \"nvidia-smi\": CreateProcess error=2");
        });

        assertEquals("command_not_found", snapshot.get("status"));
        assertEquals(Boolean.FALSE, snapshot.get("available"));
        assertEquals(0, snapshot.get("detectedCount"));
        assertEquals("command_not_found", TraceStore.get("uaw.gpu-hardware.status"));
        assertEquals("blocked", TraceStore.get("uaw.gpu-hardware.admission.status"));
        assertEquals(Boolean.FALSE, TraceStore.get("uaw.gpu-hardware.admission.retrainAllowed"));
    }

    @Test
    void uuidRoleMappingSurvivesReorderedIndicesWithoutPublishingRawUuid() {
        String mainUuid = "GPU-11111111-1111-1111-1111-111111111111";
        String fastUuid = "GPU-22222222-2222-2222-2222-222222222222";
        String rows = "0, NVIDIA GeForce RTX 3090, 24576, 100, 0, 30, 8, " + mainUuid + ", 00000000:0A:00.0, 616.64, 24476\n"
                + "1, NVIDIA GeForce RTX 3060, 12288, 200, 0, 40, 9, " + fastUuid + ", 00000000:05:00.0, 616.64, 12088";
        List<Map<String, Object>> first = GpuHardwareDiagnostics.parseNvidiaSmiCsv(rows);
        List<Map<String, Object>> swapped = GpuHardwareDiagnostics.parseNvidiaSmiCsv(
                rows.replace("0, NVIDIA GeForce RTX 3090", "1, NVIDIA GeForce RTX 3090")
                        .replace("1, NVIDIA GeForce RTX 3060", "0, NVIDIA GeForce RTX 3060"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(mainUuid), first.get(0).get("uuidHash"));
        assertEquals(first.get(0).get("uuidHash"), swapped.get(0).get("uuidHash"));
        assertEquals(first.get(0).get("role"), swapped.get(0).get("role"));
        assertEquals("00000000:0A:00.0", first.get(0).get("pciBusId"));
        assertEquals("616.64", first.get(0).get("driverVersion"));
        assertEquals(24476, first.get(0).get("memoryFreeMiB"));
        assertFalse(first.toString().contains(mainUuid));
    }

    @Test
    void repeatedAdmissionReadsReuseBoundedHardwareObservation() {
        MockEnvironment env = new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled", "true");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GpuHardwareDiagnostics.CommandRunner runner = timeout -> {
            calls.incrementAndGet();
            return new GpuHardwareDiagnostics.CommandResult(0,
                    "0, NVIDIA GeForce RTX 3060, 12288, 100, 0, 30, 8", "", false);
        };
        Map<String, Object> first = GpuHardwareDiagnostics.snapshot(env, runner);
        for (int i = 0; i < 20; i++) {
            Map<String, Object> next = GpuHardwareDiagnostics.snapshot(env, runner);
            assertEquals(first.get("observedAtEpochMs"), next.get("observedAtEpochMs"));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentExpiredCacheReadersShareOneHardwareProbe() throws Exception {
        var env = new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled", "true");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        GpuHardwareDiagnostics.CommandRunner runner = timeout -> {
            calls.incrementAndGet();
            Thread.sleep(100);
            return new GpuHardwareDiagnostics.CommandResult(0, "0, NVIDIA GeForce RTX 3060, 12288, 100, 0, 30, 8", "", false);
        };
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> { start.await(); return GpuHardwareDiagnostics.snapshot(env, runner); }));
            start.countDown();
            Object first = results.get(0).get(3, java.util.concurrent.TimeUnit.SECONDS).get("observedAtEpochMs");
            for (var result : results) assertEquals(first, result.get(3, java.util.concurrent.TimeUnit.SECONDS).get("observedAtEpochMs"));
            assertEquals(1, calls.get());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void failedHardwareQueryIsAlsoCachedToAvoidPerRequestRetryStorm() {
        MockEnvironment env = new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled", "true");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GpuHardwareDiagnostics.CommandRunner runner = timeout -> {
            calls.incrementAndGet(); throw new IOException("driver_unavailable");
        };
        for (int i = 0; i < 20; i++) {
            assertEquals("error", GpuHardwareDiagnostics.snapshot(env, runner).get("status"));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void unavailableSensorsNeverBecomeZeroOrNominalMemoryAdmission() {
        MockEnvironment env = new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled", "true");
        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env, timeout ->
                new GpuHardwareDiagnostics.CommandResult(0,
                        "0, NVIDIA GeForce RTX 3090, N/A, N/A, N/A, N/A, N/A\n"
                                + "1, NVIDIA GeForce RTX 3060, 12288, 100, N/A, N/A, N/A", "", false));
        assertEquals(null, snapshot.get("maxTemperatureC"));
        assertEquals(null, snapshot.get("maxUtilizationGpuPct"));
        assertEquals(false, snapshot.get("memoryEvidenceComplete"));
        assertEquals("gpu_memory_evidence_needed", GpuHardwareDiagnostics.admissionFromSnapshot(snapshot).get("reason"));
        assertEquals(false, GpuHardwareDiagnostics.admissionFromSnapshot(snapshot).get("heavyWorkloadsAllowed"));
        assertEquals("not_observed", snapshot.get("inferenceStatus"));
    }

    @Test
    void partialDriverFailureRetainsHealthyDeviceEvidenceWithoutClaimingBothReady() {
        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(
                new MockEnvironment().withProperty("awx.gpu-hardware.telemetry.enabled", "true"), timeout ->
                        new GpuHardwareDiagnostics.CommandResult(15,
                                "0, NVIDIA GeForce RTX 3060, 12288, 100, 2, 40, 9\n"
                                        + "Unable to determine the device handle for GPU-11111111-1111-1111-1111-111111111111: GPU is lost",
                                "driver query failed", false));
        assertEquals(1, snapshot.get("detectedCount"));
        assertEquals(true, snapshot.get("hasRtx3060"));
        assertEquals(false, snapshot.get("hasRtx3090"));
        assertEquals(false, snapshot.get("heavyLaneReady"));
        assertEquals("partial", snapshot.get("status"));
        assertEquals(15, snapshot.get("queryExitCode"));
        assertEquals(false, snapshot.get("driverQuerySucceeded"));
        assertTrue(snapshot.containsKey("observedAtEpochMs"));
    }

    @Test
    void invalidNumericConfigLeavesStableReasonCodeWithoutRawValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.telemetry.enabled", "false")
                .withProperty("awx.gpu-hardware.telemetry.timeout-ms", "bad-timeout-owner-token")
                .withProperty("awx.gpu-hardware.admission.memory-warn-threshold", "bad-warn-owner-token")
                .withProperty("awx.gpu-hardware.admission.memory-block-threshold", "bad-block-owner-token");

        GpuHardwareDiagnostics.snapshot(env, timeoutMs -> {
            fail("nvidia-smi runner should not execute when telemetry is disabled");
            return new GpuHardwareDiagnostics.CommandResult(0, "", "", false);
        });

        assertEquals("invalid_number", TraceStore.get("uaw.gpu-hardware.suppressed.gpuHardware.intProp.errorType"));
        assertEquals("invalid_number", TraceStore.get("uaw.gpu-hardware.suppressed.gpuHardware.doubleProp.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("owner-token"));
    }

    @Test
    void nonFiniteNumericConfigAndAdmissionValuesFallBack() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.admission.memory-warn-threshold", "Infinity");
        Method doubleProp = GpuHardwareDiagnostics.class.getDeclaredMethod(
                "doubleProp", org.springframework.core.env.Environment.class, String.class, double.class);
        Method doubleValue = GpuHardwareDiagnostics.class.getDeclaredMethod(
                "doubleValue", Object.class, double.class);
        doubleProp.setAccessible(true);
        doubleValue.setAccessible(true);

        assertEquals(0.55d, (Double) doubleProp.invoke(null,
                env, "awx.gpu-hardware.admission.memory-warn-threshold", 0.55d), 0.0d);
        assertEquals(0.42d, (Double) doubleValue.invoke(null, Double.NEGATIVE_INFINITY, 0.42d), 0.0d);
        assertEquals("invalid_number", TraceStore.get("uaw.gpu-hardware.suppressed.gpuHardware.doubleProp.errorType"));
        assertEquals("invalid_number", TraceStore.get("uaw.gpu-hardware.suppressed.gpuHardware.doubleValue.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("Infinity"));
    }

    @Test
    void gpuHardwareSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method method = GpuHardwareDiagnostics.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, "gpuHardware.parseInt " + secret, new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("uaw.gpu-hardware.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.gpu-hardware.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("uaw.gpu-hardware.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.gpu-hardware.suppressed." + safeStage + ".errorType"));
        assertFalse(TraceStore.getAll().toString().contains(secret));
    }

    @Test
    void gpuHardwareFailSoftPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/health/GpuHardwareDiagnostics.java"));

        assertTrue(source.contains("traceSuppressed(\"gpuHardware.interrupted\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.io\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.snapshot\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.parseInt\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.parseDouble\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.intProp\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.doubleProp\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"gpuHardware.doubleValue\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"uaw.gpu-hardware.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("return \"invalid_number\";"));
    }

    @Test
    void admissionDemotesRetrainBeforeBlockingInteractiveHeavyWorkAtWarnPressure() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.telemetry.enabled", "true")
                .withProperty("awx.gpu-hardware.admission.memory-warn-threshold", "0.50")
                .withProperty("awx.gpu-hardware.admission.memory-block-threshold", "0.95");

        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env, timeoutMs ->
                new GpuHardwareDiagnostics.CommandResult(0, """
                        0, NVIDIA GeForce RTX 3060, 12288, 207, 0, 34, 8.13
                        1, NVIDIA GeForce RTX 3090, 24576, 14746, 25, 44, 43.39
                        """, "", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> admission = (Map<String, Object>) snapshot.get("admission");
        assertEquals("degraded", admission.get("status"));
        assertEquals("warn", admission.get("pressureLevel"));
        assertEquals(Boolean.FALSE, admission.get("retrainAllowed"));
        assertEquals(Boolean.TRUE, admission.get("rerankAllowed"));
        assertEquals(Boolean.FALSE, admission.get("embeddingFallbackAllowed"));
    }

    @Test
    void admissionBlocksHeavyWorkAtBlockPressure() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.gpu-hardware.telemetry.enabled", "true")
                .withProperty("awx.gpu-hardware.admission.memory-block-threshold", "0.90");

        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env, timeoutMs ->
                new GpuHardwareDiagnostics.CommandResult(0, """
                        0, NVIDIA GeForce RTX 3060, 12288, 207, 0, 34, 8.13
                        1, NVIDIA GeForce RTX 3090, 24576, 23500, 92, 77, 320.50
                        """, "", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> admission = (Map<String, Object>) snapshot.get("admission");
        assertEquals("blocked", admission.get("status"));
        assertEquals("block", admission.get("pressureLevel"));
        assertEquals("gpu_memory_pressure", admission.get("reason"));
        assertEquals(Boolean.FALSE, admission.get("heavyWorkloadsAllowed"));
        assertEquals(Boolean.FALSE, admission.get("retrainAllowed"));
        assertEquals(Boolean.FALSE, admission.get("rerankAllowed"));
    }
}
