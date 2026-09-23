package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadata;
import com.example.lms.uaw.autolearn.AutoLearnRunStateStore;
import com.example.lms.uaw.autolearn.OpenCodeFreeQuotaGuard;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import com.example.lms.uaw.autolearn.UawLearningAgentHandoffWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UawAutolearnDiagnosticsControllerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void loopEndpointExposesLastLoopSnapshotAndAllowlistedRequestTrace() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        TraceStore.put("learning.loop.errorRate", 1.0d);
        TraceStore.put("learning.error.hotspot", "writer");
        TraceStore.put("blackbox.risk.dominantFailure", "provider_disabled");
        TraceStore.put("blackbox.risk.riskScore", 0.91d);
        TraceStore.put("blackbox.risk.patternId", "12345");
        TraceStore.put("uaw.retrain.ingest.count", 3);
        TraceStore.put("uaw.retrain.ingest.summary", Map.of(
                "readLines", 5,
                "parsedLines", 3,
                "queuedDocs", 3,
                "failedBatches", 0,
                "acceptedDocs", 3,
                "reason", ""));
        TraceStore.put("uaw.dataset-api.status", "rejected");
        TraceStore.put("uaw.dataset-api.accepted", false);
        TraceStore.put("uaw.dataset-api.disabledReason", "sample_score_below_threshold");
        TraceStore.put("uaw.dataset-api.datasetFile", "train_rag_curated.jsonl");
        TraceStore.put("uaw.dataset-api.datasetPathHash", SafeRedactor.hashValue("C:\\private\\macmini\\train_rag_curated.jsonl"));
        TraceStore.put("uaw.dataset-api.sessionHash", SafeRedactor.hashValue("raw-dataset-session"));
        TraceStore.put("uaw.dataset-api.validationDecision", "rejected");
        TraceStore.put("uaw.dataset-api.rejectReasons", List.of("sample_score_below_threshold"));
        TraceStore.put("awx.learning-ops.collector.status", "written");
        TraceStore.put("awx.learning-ops.collector.itemCount", 2);
        TraceStore.put("awx.learning-ops.collector.outputPathHash",
                SafeRedactor.hashValue("C:\\private\\macmini\\learning-ops-curation.jsonl"));
        TraceStore.put("awx.learning-ops.collector.rawQuery", "raw collector query must not leak");
        TraceStore.put("uaw.gpu-hardware.status", "ok");
        TraceStore.put("uaw.gpu-hardware.heavyLaneReady", true);
        TraceStore.put("learning.validation.decision", "rejected");
        TraceStore.put("learning.validation.rejectReasons", List.of("sample_score_below_threshold"));
        TraceStore.put("learning.validation.sampleScore", 0.42d);
        TraceStore.put("learning.validation.thresholdBreaks", List.of(Map.of(
                "reason", "sample_score_below_threshold",
                "metric", "sampleScore",
                "value", 0.42d,
                "threshold", 0.55d)));
        TraceStore.put("ownerToken", "must-not-leak");
        TraceStore.put("rawQuery", "must-not-leak");
        TraceStore.put("uaw.idle.loop-diagnostics", Map.of(
                "sessionId", "request-raw-session",
                "datasetPath", "C:\\secret\\train_rag.jsonl",
                "_nova.origText", "raw original compressed text"));
        MockEnvironment env = macMiniEnv()
                .withProperty("uaw.dataset-api.enabled", "true")
                .withProperty("uaw.dataset-api.key", "internal-secret-value")
                .withProperty("uaw.autolearn.enabled", "true")
                .withProperty("uaw.autolearn.idle-trigger.enabled", "true")
                .withProperty("uaw.autolearn.retrain.enabled", "false")
                .withProperty("uaw.autolearn.dataset.path", "C:\\private\\macmini\\train_rag_curated.jsonl")
                .withProperty("uaw.autolearn.dataset.name", "macmini-curated-rag")
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", "C:\\private\\macmini\\learning-ops-curation.jsonl")
                .withProperty("llm.owner-token", "owner-proxy-secret-value")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.target-execution-node", "desktop-rtx3090-rtx3060")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://desktop-gpu.internal:11436/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://desktop-gpu.internal:11436/api/embed")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435,desktop-gpu.internal:11436")
                .withProperty("nova.orch.failure.feedback.enabled", "true");
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, env)).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality.errorRateWindow").value(1.0d))
                .andExpect(jsonPath("$.lastLoopDiagnostics.trainDecision").value("BLOCK_RETRAIN"))
                .andExpect(jsonPath("$.lastLoopDiagnostics.sessionId").doesNotExist())
                .andExpect(jsonPath("$.lastLoopDiagnostics.datasetPath").doesNotExist())
                .andExpect(jsonPath("$.lastLoopDiagnostics.hasSessionId").value(true))
                .andExpect(jsonPath("$.lastLoopDiagnostics.sessionHash").value(SafeRedactor.hashValue("s1")))
                .andExpect(jsonPath("$.lastLoopDiagnostics.datasetFile").doesNotExist())
                .andExpect(jsonPath("$.lastLoopDiagnostics.datasetFileHash").value(SafeRedactor.hashValue("train_rag.jsonl")))
                .andExpect(jsonPath("$.lastLoopDiagnostics.datasetFileLength").value("train_rag.jsonl".length()))
                .andExpect(jsonPath("$.lastLoopDiagnostics.datasetPathHash").value(SafeRedactor.hashValue("C:\\secret\\train_rag.jsonl")))
                .andExpect(jsonPath("$.lastLoopHotspot.hotspot").value("threshold"))
                .andExpect(jsonPath("$.requestThreadTrace['learning.loop.errorRate']").value(1.0d))
                .andExpect(jsonPath("$.requestThreadTrace['learning.error.hotspot']").value("writer"))
                .andExpect(jsonPath("$.requestThreadTrace['blackbox.risk.dominantFailure']").value("provider_disabled"))
                .andExpect(jsonPath("$.requestThreadTrace['blackbox.risk.riskScore']").value(0.91d))
                .andExpect(jsonPath("$.requestThreadTrace['blackbox.risk.patternId']").value("12345"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.retrain.ingest.count']").value(3))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.retrain.ingest.summary'].queuedDocs").value(3))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.status']").value("rejected"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.accepted']").value(false))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.disabledReason']").value("sample_score_below_threshold"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.datasetFile']").doesNotExist())
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.datasetFileHash']").value(SafeRedactor.hashValue("train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.datasetFileLength']").value("train_rag_curated.jsonl".length()))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.datasetPathHash']").value(SafeRedactor.hashValue("C:\\private\\macmini\\train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.sessionHash']").value(SafeRedactor.hashValue("raw-dataset-session")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.validationDecision']").value("rejected"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.dataset-api.rejectReasons'][0]").value("sample_score_below_threshold"))
                .andExpect(jsonPath("$.requestThreadTrace['awx.learning-ops.collector.status']").value("written"))
                .andExpect(jsonPath("$.requestThreadTrace['awx.learning-ops.collector.itemCount']").value(2))
                .andExpect(jsonPath("$.requestThreadTrace['awx.learning-ops.collector.outputPathHash']").value(SafeRedactor.hashValue("C:\\private\\macmini\\learning-ops-curation.jsonl")))
                .andExpect(jsonPath("$.requestThreadTrace['awx.learning-ops.collector.rawQuery'].hash12").value(SafeRedactor.hash12("raw collector query must not leak")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.gpu-hardware.status']").value("disabled_by_config"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.gpu-hardware.heavyLaneReady']").value(false))
                .andExpect(jsonPath("$.requestThreadTrace['learning.validation.decision']").value("rejected"))
                .andExpect(jsonPath("$.requestThreadTrace['learning.validation.rejectReasons'][0]").value("sample_score_below_threshold"))
                .andExpect(jsonPath("$.requestThreadTrace['learning.validation.sampleScore']").value(0.42d))
                .andExpect(jsonPath("$.requestThreadTrace['learning.validation.thresholdBreaks'][0].reason").value("sample_score_below_threshold"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.idle.loop-diagnostics'].sessionId").value(SafeRedactor.hashValue("request-raw-session")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.idle.loop-diagnostics'].datasetPath").value(SafeRedactor.hashValue("C:\\secret\\train_rag.jsonl")))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.idle.loop-diagnostics']['_nova.origText'].hash12").value(SafeRedactor.hash12("raw original compressed text")))
                .andExpect(jsonPath("$.nodeProfile.node.role").value("macmini-control-plane"))
                .andExpect(jsonPath("$.nodeProfile.node.executionNode").value("macmini-m4-16gb"))
                .andExpect(jsonPath("$.nodeProfile.node.controlPlane").value(true))
                .andExpect(jsonPath("$.nodeProfile.node.heavyWorkloadsAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.node.schedulingAssistant").value(true))
                .andExpect(jsonPath("$.nodeProfile.node.workloadPolicy").value("control_plane_curate_observe_schedule"))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.enabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.hasKey").value(true))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.keySource").value("property:uaw.dataset-api.key"))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.disabledReason").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.datasetApi.curationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.dataset.datasetFile").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.dataset.datasetFileHash").value(SafeRedactor.hashValue("train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.dataset.datasetFileLength").value("train_rag_curated.jsonl".length()))
                .andExpect(jsonPath("$.nodeProfile.dataset.datasetPathHash").value(SafeRedactor.hashValue("C:\\private\\macmini\\train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.autolearnEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.idleTriggerEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.retrainEnabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.retrainAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.datasetApiCurationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.learningOpsCollectorEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.learningOpsCollectorWritesDataset").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.curationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.failurePatternFeedbackEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.matrixTraceSurface").value("requestThreadTrace.blackbox.risk.*"))
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.rootDir").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.rootDirHash").value(SafeRedactor.hashValue("codex")))
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.rootDirLength").value("codex".length()))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.planDsl.planId").value("safe_autorun.v1"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.idleTrain.tracePrefix").value("uaw.idle.*"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.idleTrain.mode").value("curate-observe-schedule"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.failurePattern.feedbackEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.matrix9.tracePrefix").value("blackbox.risk.*"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetFile").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetFileHash").value(SafeRedactor.hashValue("train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetFileLength").value("train_rag_curated.jsonl".length()))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetPathHash").value(SafeRedactor.hashValue("C:\\private\\macmini\\train_rag_curated.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiHasKey").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiCurationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.idleTrainCurationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorMode").value("read_only_curation_candidates"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorWritesDataset").value(false))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorRequiresReview").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorOutputFile").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorOutputFileHash").value(SafeRedactor.hashValue("learning-ops-curation.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorOutputFileLength").value("learning-ops-curation.jsonl".length()))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorOutputPathHash").value(SafeRedactor.hashValue("C:\\private\\macmini\\learning-ops-curation.jsonl")))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.collectorStatus").value("ready_read_only_curation_queue"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.status").value("curate_via_dataset_api"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.gpuGateway.routePolicy").value("handoff_to_desktop_gpu"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.gpuGateway.primaryStatus").value("ok"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.gpuGateway.embeddingStatus").value("ok"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.enabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.mode").value("control_plane_to_gpu_executor"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.routePolicy").value("handoff_to_desktop_gpu"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.targetExecutionNode").value("desktop-rtx3090-rtx3060"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.hasOwnerToken").value(true))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.ownerTokenAttachable").value(true))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.disabledReason").doesNotExist())
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.primaryChat.endpointHost").value("desktop-gpu.internal"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.primaryChat.endpointHostPort").value("desktop-gpu.internal:11435"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.primaryChat.device").value("rtx3090"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.primaryChat.status").value("ok"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.embedding.device").value("rtx3060"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.embedding.status").value("ok"))
                .andExpect(jsonPath("$.nodeProfile.gpuHardware.status").value("disabled_by_config"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.gpuHardware.status").value("disabled_by_config"))
                .andExpect(jsonPath("$.requestThreadTrace.ownerToken").doesNotExist())
                .andExpect(jsonPath("$.requestThreadTrace.rawQuery").doesNotExist())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("raw-dataset-session")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("raw collector query must not leak")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("owner-proxy-secret-value")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("internal-secret-value")))));
    }

    @Test
    void loopEndpointKeepsLastLoopSnapshotAfterTraceStoreClears() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        TraceStore.clear();
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, new MockEnvironment())).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastLoopDiagnostics.trainDecision").value("BLOCK_RETRAIN"))
                .andExpect(jsonPath("$.lastLoopDiagnostics.errorRateWindow").value(1.0d))
                .andExpect(jsonPath("$.lastLoopHotspot.hotspot").value("threshold"))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.enabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.hasKey").value(false))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.disabledReason").value("disabled_by_config"))
                .andExpect(jsonPath("$.requestThreadTrace").isMap())
                .andExpect(jsonPath("$.requestThreadTrace['uaw.idle.loop-diagnostics']").doesNotExist());
    }

    @Test
    void loopEndpointExposesExternalQuotaStatusWithoutSecretsOrPrompts() throws Exception {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getBudget().setStatePath(tempDir.resolve("autolearn_state.json").toString());
        props.getExternalQuota().setEnabled(true);
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.autolearn.strict.model", "llmrouter.external")
                .withProperty("llmrouter.models.external.enabled", "true")
                .withProperty("llmrouter.models.external.name", "deepseek-v4-flash-free")
                .withProperty("llmrouter.models.external.base-url", "https://opencode.ai/zen/v1")
                .withProperty("OPENCODE_API_KEY", "opencode-secret-must-not-leak");
        OpenCodeFreeQuotaGuard guard = new OpenCodeFreeQuotaGuard(props, new AutoLearnRunStateStore(), env);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("externalQuotaGuard", guard);
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(
                trackerWithBlockedCycle(),
                env,
                null,
                factory.getBeanProvider(OpenCodeFreeQuotaGuard.class))).build();

        String body = mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalQuota.enabled").value(true))
                .andExpect(jsonPath("$.externalQuota.routeModel").value("llmrouter.external"))
                .andExpect(jsonPath("$.externalQuota.providerHost").value("opencode.ai"))
                .andExpect(jsonPath("$.externalQuota.endpointHost").value("opencode.ai"))
                .andExpect(jsonPath("$.externalQuota.model").value("deepseek-v4-flash-free"))
                .andExpect(jsonPath("$.externalQuota.freeModel").value("deepseek-v4-flash-free"))
                .andExpect(jsonPath("$.externalQuota.routeEnabled").value(true))
                .andExpect(jsonPath("$.externalQuota.hasKey").value(true))
                .andExpect(jsonPath("$.externalQuota.consumed").value(false))
                .andExpect(jsonPath("$.externalQuota.nextReservationTokens").value(512))
                .andExpect(jsonPath("$.externalQuota.resetZone").value("UTC"))
                .andExpect(jsonPath("$.externalQuota.privacyMode").value("STATIC_SYNTHETIC_ONLY"))
                .andExpect(jsonPath("$.externalQuota.canonicalTrainingPolicy").value("EXTERNAL_FREE_CURATE_ONLY"))
                .andExpect(jsonPath("$.externalQuota.endpointFamily").value("CHAT_COMPLETIONS"))
                .andExpect(jsonPath("$.externalQuota.modelPolicy").value("CURATE_ONLY"))
                .andExpect(jsonPath("$.externalQuota.routeConfigured").value(true))
                .andExpect(jsonPath("$.externalQuota.remainingCalls").value(3))
                .andExpect(jsonPath("$.externalQuota.remainingOutputTokens").value(1536))
                .andExpect(jsonPath("$.externalQuota.allowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.externalQuota.endpointHost").value("opencode.ai"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("opencode-secret-must-not-leak"));
        assertFalse(body.contains("raw prompt"));
        assertFalse(body.contains("Authorization"));
    }

    @Test
    void agentHandoffEndpointExposesFileSummariesAndLoopNodeProfileWithoutRawText() throws Exception {
        UawAutolearnProperties props = new UawAutolearnProperties();
        Path root = tempDir.resolve("codex");
        props.getAgentHandoff().setRootPath(root.toString());
        props.getAgentHandoff().setAcceptedPath(root.resolve("accepted.jsonl").toString());
        props.getAgentHandoff().setRejectedPath(root.resolve("rejected.jsonl").toString());
        props.getAgentHandoff().setCyclePath(root.resolve("cycles.jsonl").toString());
        props.getAgentHandoff().setManifestPath(root.resolve("manifest.json").toString());
        UawLearningAgentHandoffWriter writer = new UawLearningAgentHandoffWriter(props);
        writer.recordSample(
                "raw-session-id",
                "ds",
                "raw question ownerToken=must-not-leak",
                "raw answer Bearer " + "abc.def.ghi",
                "local-model",
                2,
                new UawDatasetWriter.TrainingMetadata(
                        "uaw_autolearn",
                        "local",
                        "",
                        2,
                        true,
                        0.9d,
                        LearningSampleValidationMetadata.empty()),
                true);

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("handoffWriter", writer);
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(
                trackerWithBlockedCycle(),
                new MockEnvironment(),
                factory.getBeanProvider(UawLearningAgentHandoffWriter.class))).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/agent-handoff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rootDir").doesNotExist())
                .andExpect(jsonPath("$.rootDirHash").value(SafeRedactor.hashValue("codex")))
                .andExpect(jsonPath("$.rootDirLength").value("codex".length()))
                .andExpect(jsonPath("$.rootPathHash").value(SafeRedactor.hashValue(root.toString())))
                .andExpect(jsonPath("$.files.accepted.fileName").doesNotExist())
                .andExpect(jsonPath("$.files.accepted.fileNameHash").value(SafeRedactor.hashValue("accepted.jsonl")))
                .andExpect(jsonPath("$.files.accepted.fileNameLength").value("accepted.jsonl".length()))
                .andExpect(jsonPath("$.files.accepted.exists").value(true))
                .andExpect(jsonPath("$.files.accepted.lineCount").value(1))
                .andExpect(jsonPath("$.files.rejected.exists").value(false))
                .andExpect(jsonPath("$.files.manifest.fileName").doesNotExist())
                .andExpect(jsonPath("$.files.manifest.fileNameHash").value(SafeRedactor.hashValue("manifest.json")))
                .andExpect(jsonPath("$.files.manifest.fileNameLength").value("manifest.json".length()))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("raw-session-id")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("raw question")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("Bearer " + "abc.def.ghi")))))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("ownerToken")))));

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.enabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.files.accepted.exists").value(true))
                .andExpect(jsonPath("$.nodeProfile.agentHandoff.files.accepted.lineCount").value(1))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("raw answer")))));
    }

    @Test
    void loopEndpointReportsEnabledDatasetApiWithoutKeyAsMissingInternalKey() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        MockEnvironment env = macMiniEnv()
                .withProperty("uaw.dataset-api.enabled", "true")
                .withProperty("uaw.dataset-api.key", "sk-local")
                .withProperty("uaw.autolearn.retrain.enabled", "false");
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, env)).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeProfile.datasetApi.enabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.hasKey").value(false))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.keySource").value(""))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.disabledReason").value("missing_internal_key"))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.curationAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.datasetApiCurationAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.retrainAllowed").value(false));
    }

    @Test
    void loopEndpointTreatsDatasetApiAsMacMiniCurationEvenWhenAutolearnIsDisabled() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        MockEnvironment env = macMiniEnv()
                .withProperty("uaw.dataset-api.enabled", "true")
                .withProperty("uaw.dataset-api.key", "internal-secret-value")
                .withProperty("uaw.autolearn.enabled", "false")
                .withProperty("uaw.autolearn.idle-trigger.enabled", "false")
                .withProperty("uaw.autolearn.retrain.enabled", "false")
                .withProperty("uaw.autolearn.dataset.path", "C:\\private\\macmini\\train_rag_curated.jsonl")
                .withProperty("uaw.autolearn.dataset.name", "macmini-curated-rag");
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, env)).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeProfile.datasetApi.enabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.hasKey").value(true))
                .andExpect(jsonPath("$.nodeProfile.datasetApi.curationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.autolearnEnabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.idleTriggerEnabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.datasetApiCurationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.curationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.retrainAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiEnabled").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiHasKey").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.datasetApiCurationAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.idleTrainCurationAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.status").value("curate_via_dataset_api"));
    }

    @Test
    void loopEndpointReportsDesktopGpuExecutorProfileWithoutEnablingAutolearnByDefault() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.role", "desktop-gpu-executor")
                .withProperty("awx.node.execution-node", "desktop-rtx3090-rtx3060")
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "true")
                .withProperty("awx.node.learning-loop-mode", "execute-and-curate")
                .withProperty("uaw.autolearn.runtime-node.scheduling-assistant", "false")
                .withProperty("uaw.autolearn.enabled", "false")
                .withProperty("uaw.autolearn.idle-trigger.enabled", "false")
                .withProperty("uaw.autolearn.retrain.enabled", "false")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.target-execution-node", "desktop-rtx3090-rtx3060")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://127.0.0.1:11435/v1")
                .withProperty("awx.gpu-gateway.fast-base-url", "http://127.0.0.1:11436/v1")
                .withProperty("awx.gpu-gateway.embedding-base-url", "http://127.0.0.1:11436/api/embed")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true");
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, env)).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeProfile.node.role").value("desktop-gpu-executor"))
                .andExpect(jsonPath("$.nodeProfile.node.executionNode").value("desktop-rtx3090-rtx3060"))
                .andExpect(jsonPath("$.nodeProfile.node.controlPlane").value(true))
                .andExpect(jsonPath("$.nodeProfile.node.heavyWorkloadsAllowed").value(true))
                .andExpect(jsonPath("$.nodeProfile.node.workloadPolicy").value("gpu_executor_heavy_workloads"))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.autolearnEnabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.idleTriggerEnabled").value(false))
                .andExpect(jsonPath("$.nodeProfile.learningLoop.retrainAllowed").value(false))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.mode").value("local_gpu_executor"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.routePolicy").value("execute_on_this_node"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.primaryChat.device").value("rtx3090"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.fastHelper.device").value("rtx3060"))
                .andExpect(jsonPath("$.nodeProfile.gpuGateway.endpoints.embedding.device").value("rtx3060"))
                .andExpect(jsonPath("$.nodeProfile.gpuHardware.status").value("disabled_by_config"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.idleTrain.mode").value("execute-and-curate"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.datasetPipeline.status").value("disabled_by_config"))
                .andExpect(jsonPath("$.nodeProfile.selfLearningBridge.gpuGateway.routePolicy").value("execute_on_this_node"));
    }

    @Test
    void gpuGatewayPreflightEndpointPublishesAllowlistedTraceWithoutRawSecrets() throws Exception {
        UawAutolearnQualityTracker tracker = trackerWithBlockedCycle();
        MockEnvironment env = macMiniEnv()
                .withProperty("llm.owner-token", "")
                .withProperty("awx.gpu-gateway.enabled", "true")
                .withProperty("awx.gpu-gateway.primary-chat-base-url", "http://desktop-gpu.internal:11435/v1")
                .withProperty("awx.gpu-gateway.allowed-hosts", "desktop-gpu.internal:11435")
                .withProperty("awx.gpu-gateway.require-auth-for-remote", "true");
        MockMvc mvc = standaloneSetup(new UawAutolearnDiagnosticsController(tracker, env)).build();

        mvc.perform(get("/api/diagnostics/uaw/autolearn/gpu-gateway/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preflight.status").value("unreachable"))
                .andExpect(jsonPath("$.preflight.configuredCount").value(1))
                .andExpect(jsonPath("$.preflight.reachableCount").value(0))
                .andExpect(jsonPath("$.preflight.endpoints.primaryChat.status").value("skipped_remote_auth_missing"))
                .andExpect(jsonPath("$.preflight.endpoints.primaryChat.endpointHost").value("desktop-gpu.internal"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.gpu-gateway.preflight.status']").value("unreachable"))
                .andExpect(jsonPath("$.requestThreadTrace['uaw.gpu-gateway.preflight'].endpoints.primaryChat.status").value("skipped_remote_auth_missing"))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasToString(org.hamcrest.Matchers.containsString("owner-proxy-secret-value")))));
    }

    @Test
    void numericPropertyFallbacksEmitNamedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/UawAutolearnDiagnosticsController.java"));

        assertTrue(source.contains("traceSuppressed(\"uawDiagnostics.intProp\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"uawDiagnostics.doubleProp\", ignored);"));
        assertTrue(source.contains("TraceStore.put(\"uaw.diagnostics.suppressed.\" + safeStage, true);"));
    }

    @Test
    void numericPropertyFallbacksUseStableReasonCodeWithoutRawValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.autolearn.agent-handoff.max-line-bytes", "private max-line value")
                .withProperty("uaw.autolearn.diagnostic-double", "private double value");
        UawAutolearnDiagnosticsController controller =
                new UawAutolearnDiagnosticsController(trackerWithBlockedCycle(), env);

        Object intValue = ReflectionTestUtils.invokeMethod(
                controller,
                "intProp",
                "uaw.autolearn.agent-handoff.max-line-bytes",
                32768);
        assertEquals(32768, ((Number) intValue).intValue());
        assertEquals("uawDiagnostics.intProp", TraceStore.get("uaw.diagnostics.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("uaw.diagnostics.suppressed.errorType"));
        assertEquals("invalid_number",
                TraceStore.get("uaw.diagnostics.suppressed.uawDiagnostics.intProp.errorType"));

        TraceStore.clear();
        Object doubleValue = ReflectionTestUtils.invokeMethod(
                controller,
                "doubleProp",
                "uaw.autolearn.diagnostic-double",
                0.75d);
        assertEquals(0.75d, ((Number) doubleValue).doubleValue(), 0.0001d);
        assertEquals("uawDiagnostics.doubleProp", TraceStore.get("uaw.diagnostics.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("uaw.diagnostics.suppressed.errorType"));
        assertEquals("invalid_number",
                TraceStore.get("uaw.diagnostics.suppressed.uawDiagnostics.doubleProp.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private"));
    }

    private static UawAutolearnQualityTracker trackerWithBlockedCycle() {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getValidation().setWindowSize(4);
        props.getValidation().setMaxTrainErrorRate(0.35d);
        UawAutolearnQualityTracker tracker = new UawAutolearnQualityTracker(props, null, null);
        tracker.recordExternalError("writer_failed");
        tracker.recordExternalError("empty_result");
        tracker.finishCycle("s1", 2, 0, false, "C:\\secret\\train_rag.jsonl");
        return tracker;
    }

    private static MockEnvironment macMiniEnv() {
        return new MockEnvironment()
                .withProperty("awx.node.role", "macmini-control-plane")
                .withProperty("awx.node.execution-node", "macmini-m4-16gb")
                .withProperty("awx.node.control-plane", "true")
                .withProperty("awx.node.heavy-workloads-allowed", "false")
                .withProperty("awx.node.learning-loop-mode", "curate-observe-schedule")
                .withProperty("uaw.autolearn.runtime-node.scheduling-assistant", "true");
    }
}
