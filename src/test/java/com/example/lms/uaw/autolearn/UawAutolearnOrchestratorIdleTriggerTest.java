package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import com.example.lms.uaw.presence.UserAbsenceGate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UawAutolearnOrchestratorIdleTriggerTest {

    @TempDir
    Path tempDir;

    private MockEnvironment env;
    private UawAutolearnProperties props;
    private UserAbsenceGate absenceGate;
    private FakeOrchestrationGate orchestrationGate;
    private UawAutolearnService autolearnService;
    private AutolearnRagRetrainOrchestrator retrainOrchestrator;
    private AutoLearnBudgetManager budgetManager;
    private UawAutolearnQualityTracker qualityTracker;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        env = new MockEnvironment()
                .withProperty("uaw.autolearn.enabled", "true")
                .withProperty("uaw.autolearn.idle-trigger.enabled", "true");
        props = new UawAutolearnProperties();
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getBudget().setMinIntervalSeconds(0);
        props.getBudget().setMaxRunsPerDay(10);
        props.getDataset().setPath(tempDir.resolve("train_rag.jsonl").toString());
        props.getRetrain().setMinAcceptedToTrain(1);
        props.getIdleTrigger().setBreadcrumbEnabled(false);

        absenceGate = mock(UserAbsenceGate.class);
        orchestrationGate = new FakeOrchestrationGate(absenceGate);
        autolearnService = mock(UawAutolearnService.class);
        retrainOrchestrator = mock(AutolearnRagRetrainOrchestrator.class);
        budgetManager = new AutoLearnBudgetManager(props, new AutoLearnRunStateStore());
        qualityTracker = mock(UawAutolearnQualityTracker.class);
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void tickRunsAutolearnWhenIdleGateAndBudgetAllow() {
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        when(autolearnService.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(2, 1, false, props.getDataset().getPath()));
        when(retrainOrchestrator.maybeRetrain(any(Path.class), eq(1), any(PreemptionToken.class))).thenReturn(1);

        orchestrator().tick();

        verify(autolearnService).runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong());
        verify(retrainOrchestrator).maybeRetrain(any(Path.class), eq(1), any(PreemptionToken.class));
        verify(qualityTracker).mergeLastLoopDiagnostics(eq("retrain"), anyMap());
        assertEquals("uaw.idle.retrain.result", TraceStore.getString("uaw.idle.last"));
        assertNotNull(TraceStore.get("uaw.idle.start"));
        assertNotNull(TraceStore.get("uaw.idle.result"));
        assertNotNull(TraceStore.get("uaw.idle.retrain.result"));
    }

    @Test
    void tickSkipsWhenUserPresenceGateDenies() {
        orchestrationGate.decision = new UawOrchestrationGate.Decision(false, "user_present", -1.0d);

        orchestrator().tick();

        verify(autolearnService, never()).runCycle(any(), anyString(), any(), anyLong());
        assertEquals("user_present", TraceStore.getString("uaw.idle.skip.reason"));
        assertEquals("uaw.idle.skip", TraceStore.getString("uaw.idle.last"));
        @SuppressWarnings("unchecked")
        Map<String, Object> skip = (Map<String, Object>) TraceStore.get("uaw.idle.skip");
        assertEquals("user_present", skip.get("reason"));
    }

    @Test
    void tickSkipsWhenIdleTriggerDisabled() {
        env.setProperty("uaw.autolearn.idle-trigger.enabled", "false");

        orchestrator().tick();

        assertEquals(0, orchestrationGate.calls);
        verify(autolearnService, never()).runCycle(any(), anyString(), any(), anyLong());
        assertEquals("idle_trigger_disabled", TraceStore.getString("uaw.idle.skip.reason"));
    }

    @Test
    void tickDoesNotRetrainWhenCycleAbortsBecauseUserReturned() {
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        when(autolearnService.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(3, 1, true, props.getDataset().getPath()));

        orchestrator().tick();

        verify(autolearnService).runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong());
        verify(retrainOrchestrator, never()).maybeRetrain(any(Path.class), anyInt(), any(PreemptionToken.class));
        assertEquals("uaw.idle.abort.user_returned", TraceStore.getString("uaw.idle.last"));
    }

    @Test
    void tickBlocksRetrainWhenQualityGuardDeniesTraining() {
        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        when(autolearnService.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(3, 1, false, props.getDataset().getPath(),
                        0.80d, 0.08d, false, "writer_failed", "BLOCK_RETRAIN"));

        orchestrator().tick();

        verify(autolearnService).runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong());
        verify(retrainOrchestrator, never()).maybeRetrain(any(Path.class), anyInt(), any(PreemptionToken.class));
        assertEquals("uaw.idle.retrain.blocked", TraceStore.getString("uaw.idle.last"));
        @SuppressWarnings("unchecked")
        Map<String, Object> blocked = (Map<String, Object>) TraceStore.get("uaw.idle.retrain.blocked");
        assertEquals("writer_failed", blocked.get("topProblem"));
        assertEquals("BLOCK_RETRAIN", blocked.get("trainDecision"));
        assertEquals("writer_failed", blocked.get("dominantFailure"));
        assertEquals("dataset_writer_or_training_filter_rejected", blocked.get("diagnosis"));
        assertNotNull(TraceStore.get("uaw.idle.loop-diagnostics"));
        assertNotNull(TraceStore.get("uaw.autolearn.loop.hotspot"));
    }

    @Test
    void tickCuratesDatasetWithoutRetrainWhenRuntimeNodeDisablesHeavyWorkAndGpuGatewayIsReachable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        env.setProperty("uaw.autolearn.retrain.enabled", "true");
        env.setProperty("uaw.autolearn.dataset.name", "macmini-curated-rag");
        env.setProperty("uaw.autolearn.dataset.path", props.getDataset().getPath());
        env.setProperty("uaw.autolearn.runtime-node.role", "macmini-control-plane");
        env.setProperty("uaw.autolearn.runtime-node.execution-node", "macmini-m4-16gb");
        env.setProperty("uaw.autolearn.runtime-node.control-plane", "true");
        env.setProperty("uaw.autolearn.runtime-node.heavy-workloads-allowed", "false");
        env.setProperty("uaw.autolearn.runtime-node.learning-loop-mode", "curate-observe-schedule");
        env.setProperty("awx.gpu-gateway.enabled", "true");
        env.setProperty("awx.gpu-gateway.target-execution-node", "desktop-rtx3090-rtx3060");
        env.setProperty("awx.gpu-gateway.primary-chat-base-url", base + "/v1");
        env.setProperty("awx.gpu-gateway.fast-base-url", base + "/v1");
        env.setProperty("awx.gpu-gateway.embedding-base-url", base + "/api/embed");
        env.setProperty("awx.gpu-gateway.allowed-hosts", "127.0.0.1:" + port);
        props.getRuntimeNode().setRole("macmini-control-plane");
        props.getRuntimeNode().setExecutionNode("macmini-m4-16gb");
        props.getRuntimeNode().setControlPlane(true);
        props.getRuntimeNode().setHeavyWorkloadsAllowed(false);
        props.getRuntimeNode().setSchedulingAssistant(true);
        props.getRuntimeNode().setLearningLoopMode("curate-observe-schedule");

        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        when(autolearnService.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(2, 1, false, props.getDataset().getPath()));

        try {
            orchestrator().tick();
        } finally {
            server.stop(0);
        }

        verify(autolearnService).runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong());
        verify(retrainOrchestrator, never()).maybeRetrain(any(Path.class), anyInt(), any(PreemptionToken.class));
        verify(qualityTracker).mergeLastLoopDiagnostics(eq("retrain_disabled"), anyMap());
        assertEquals("ok", TraceStore.getString("uaw.gpu-gateway.admission.status"));
        assertEquals("runtime_heavy_workloads_disabled", TraceStore.getString("uaw.retrain.skip.reason"));
        assertEquals("uaw.idle.retrain.disabled", TraceStore.getString("uaw.idle.last"));
        @SuppressWarnings("unchecked")
        Map<String, Object> disabled = (Map<String, Object>) TraceStore.get("uaw.idle.retrain.disabled");
        assertEquals("runtime_heavy_workloads_disabled", disabled.get("reason"));
        assertEquals("macmini-control-plane", disabled.get("runtimeNodeRole"));
        assertEquals(Boolean.TRUE, disabled.get("runtimeNodeControlPlane"));
        assertEquals(Boolean.FALSE, disabled.get("runtimeNodeHeavyWorkloadsAllowed"));
        assertEquals("curate-observe-schedule", disabled.get("learningLoopMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> gpuGateway = (Map<String, Object>) disabled.get("gpuGateway");
        assertEquals("handoff_to_desktop_gpu", gpuGateway.get("routePolicy"));
        assertEquals("desktop-rtx3090-rtx3060", gpuGateway.get("targetExecutionNode"));
        assertEquals("ok", gpuGateway.get("primaryStatus"));
        assertEquals("ok", gpuGateway.get("embeddingStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> selfLearningBridge = (Map<String, Object>) disabled.get("selfLearningBridge");
        @SuppressWarnings("unchecked")
        Map<String, Object> idleTrain = (Map<String, Object>) selfLearningBridge.get("idleTrain");
        assertEquals("uaw.idle.*", idleTrain.get("tracePrefix"));
        assertEquals("curate-observe-schedule", idleTrain.get("mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> datasetPipeline = (Map<String, Object>) selfLearningBridge.get("datasetPipeline");
        assertEquals("macmini-curated-rag", datasetPipeline.get("datasetName"));
        assertFalse(datasetPipeline.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), datasetPipeline.get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), datasetPipeline.get("datasetFileLength"));
        @SuppressWarnings("unchecked")
        Map<String, Object> bridgeGpuGateway = (Map<String, Object>) selfLearningBridge.get("gpuGateway");
        assertEquals("handoff_to_desktop_gpu", bridgeGpuGateway.get("routePolicy"));
        assertEquals("ok", bridgeGpuGateway.get("primaryStatus"));
    }

    @Test
    void tickCuratesDatasetWithoutRetrainWhenDesktopGpuAdmissionWarns() {
        env.setProperty("uaw.autolearn.retrain.enabled", "true");
        env.setProperty("uaw.autolearn.runtime-node.role", "desktop-gpu-executor");
        env.setProperty("uaw.autolearn.runtime-node.execution-node", "desktop-rtx3090-rtx3060");
        env.setProperty("uaw.autolearn.runtime-node.control-plane", "false");
        env.setProperty("uaw.autolearn.runtime-node.heavy-workloads-allowed", "true");
        env.setProperty("uaw.autolearn.runtime-node.learning-loop-mode", "execute-and-curate");
        env.setProperty("awx.gpu-hardware.admission.enabled", "true");
        env.setProperty("awx.gpu-hardware.telemetry.enabled", "true");
        env.setProperty("awx.gpu-hardware.telemetry.timeout-ms", "100");
        env.setProperty("awx.gpu-hardware.admission.memory-warn-threshold", "0.001");
        env.setProperty("awx.gpu-hardware.admission.memory-block-threshold", "0.99");
        props.getRuntimeNode().setRole("desktop-gpu-executor");
        props.getRuntimeNode().setExecutionNode("desktop-rtx3090-rtx3060");
        props.getRuntimeNode().setControlPlane(false);
        props.getRuntimeNode().setHeavyWorkloadsAllowed(true);
        props.getRuntimeNode().setLearningLoopMode("execute-and-curate");

        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        when(autolearnService.runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong()))
                .thenReturn(new AutoLearnCycleResult(2, 1, false, props.getDataset().getPath()));

        orchestrator().tick();

        verify(autolearnService).runCycle(any(File.class), anyString(), any(PreemptionToken.class), anyLong());
        verify(retrainOrchestrator, never()).maybeRetrain(any(Path.class), anyInt(), any(PreemptionToken.class));
        verify(qualityTracker).mergeLastLoopDiagnostics(eq("retrain_disabled"), anyMap());
        assertTrue(TraceStore.getString("uaw.retrain.skip.reason").startsWith("gpu_hardware_"));
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.gpu-hardware.admission.retrainBlocked"));
        assertEquals("uaw.idle.retrain.disabled", TraceStore.getString("uaw.idle.last"));
        @SuppressWarnings("unchecked")
        Map<String, Object> disabled = (Map<String, Object>) TraceStore.get("uaw.idle.retrain.disabled");
        assertTrue(String.valueOf(disabled.get("reason")).startsWith("gpu_hardware_"));
        @SuppressWarnings("unchecked")
        Map<String, Object> gpuHardware = (Map<String, Object>) disabled.get("gpuHardware");
        assertTrue(java.util.Set.of("blocked", "degraded").contains(String.valueOf(gpuHardware.get("admissionStatus"))));
    }

    @Test
    void tickBlocksMacMiniAutolearnWhenDesktopGpuGatewayAdmissionFails() {
        env.setProperty("uaw.autolearn.runtime-node.role", "macmini-control-plane");
        env.setProperty("uaw.autolearn.runtime-node.execution-node", "macmini-m4-16gb");
        env.setProperty("uaw.autolearn.runtime-node.control-plane", "true");
        env.setProperty("uaw.autolearn.runtime-node.heavy-workloads-allowed", "false");
        env.setProperty("uaw.autolearn.runtime-node.learning-loop-mode", "curate-observe-schedule");
        env.setProperty("awx.gpu-gateway.enabled", "true");
        env.setProperty("awx.gpu-gateway.target-execution-node", "desktop-rtx3090-rtx3060");
        env.setProperty("awx.gpu-gateway.require-auth-for-remote", "true");
        env.setProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1");
        env.setProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11436/v1");
        env.setProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11436/api/embed");
        env.setProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435,desktop-gpu.internal:11436");
        props.getRuntimeNode().setRole("macmini-control-plane");
        props.getRuntimeNode().setExecutionNode("macmini-m4-16gb");
        props.getRuntimeNode().setControlPlane(true);
        props.getRuntimeNode().setHeavyWorkloadsAllowed(false);
        props.getRuntimeNode().setSchedulingAssistant(true);
        props.getRuntimeNode().setLearningLoopMode("curate-observe-schedule");

        when(absenceGate.isUserAbsentNow()).thenReturn(true);
        orchestrationGate.decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);

        orchestrator().tick();

        verify(autolearnService, never()).runCycle(any(), anyString(), any(), anyLong());
        verify(retrainOrchestrator, never()).maybeRetrain(any(Path.class), anyInt(), any(PreemptionToken.class));
        verify(qualityTracker).mergeLastLoopDiagnostics(eq("gpu_gateway_blocked"), anyMap());
        assertEquals("gpu_gateway_unreachable", TraceStore.getString("uaw.idle.skip.reason"));
        assertEquals("unreachable", TraceStore.getString("uaw.gpu-gateway.admission.status"));
        assertEquals("uaw.idle.gpu-gateway.blocked", TraceStore.getString("uaw.idle.last"));
        @SuppressWarnings("unchecked")
        Map<String, Object> blocked = (Map<String, Object>) TraceStore.get("uaw.idle.gpu-gateway.blocked");
        assertEquals("gpu_gateway_unreachable", blocked.get("reason"));
        assertEquals("BLOCK_HEAVY_WORK", blocked.get("trainDecision"));
        assertEquals("desktop_gpu_gateway_unreachable", blocked.get("diagnosis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> preflight = (Map<String, Object>) blocked.get("gpuGatewayPreflight");
        assertEquals("unreachable", preflight.get("status"));
        assertEquals(3, preflight.get("configuredCount"));
        assertEquals(0, preflight.get("reachableCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) preflight.get("endpoints");
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = (Map<String, Object>) endpoints.get("primaryChat");
        assertEquals("skipped_remote_auth_missing", primary.get("status"));
    }

    private UawAutolearnOrchestrator orchestrator() {
        return new UawAutolearnOrchestrator(
                env,
                props,
                absenceGate,
                orchestrationGate,
                autolearnService,
                retrainOrchestrator,
                budgetManager,
                qualityTracker);
    }

    private static final class FakeOrchestrationGate extends UawOrchestrationGate {
        UawOrchestrationGate.Decision decision = new UawOrchestrationGate.Decision(true, "ok", 0.10d);
        int calls;

        private FakeOrchestrationGate(UserAbsenceGate absenceGate) {
            super(absenceGate);
        }

        @Override
        public UawOrchestrationGate.Decision decide(String stageKey, double idleCpuThreshold, String... breakerKeys) {
            calls++;
            return decision;
        }
    }
}
