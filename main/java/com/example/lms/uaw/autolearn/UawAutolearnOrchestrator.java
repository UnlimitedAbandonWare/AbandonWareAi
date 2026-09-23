package com.example.lms.uaw.autolearn;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.health.GpuGatewayDiagnostics;
import com.example.lms.health.GpuHardwareDiagnostics;
import com.example.lms.uaw.presence.UserAbsenceGate;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import com.example.lms.uaw.selfclean.UawSelfCleanOrchestrator;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single entry point orchestrator:
 * <pre>
 * user absent + spare capacity => autolearn dataset append => optional retrain ingest
 * </pre>
 */
@Component
public class UawAutolearnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UawAutolearnOrchestrator.class);

    private final Environment env;
    private final UawAutolearnProperties props;
    private final UserAbsenceGate userAbsenceGate;
    private final UawOrchestrationGate uawGate;
    private final UawAutolearnService autolearnService;
    private final AutolearnRagRetrainOrchestrator retrainOrchestrator;
    private final AutoLearnBudgetManager budgetManager;
    private final UawAutolearnQualityTracker qualityTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UawSelfCleanOrchestrator selfCleanOrchestrator;

    private final ReentrantLock lock = new ReentrantLock();

    public UawAutolearnOrchestrator(Environment env,
                                   UawAutolearnProperties props,
                                   UserAbsenceGate userAbsenceGate,
                                   UawOrchestrationGate uawGate,
                                   UawAutolearnService autolearnService,
                                   AutolearnRagRetrainOrchestrator retrainOrchestrator,
                                   AutoLearnBudgetManager budgetManager,
                                   UawAutolearnQualityTracker qualityTracker) {
        this.env = env;
        this.props = props;
        this.userAbsenceGate = userAbsenceGate;
        this.uawGate = uawGate;
        this.autolearnService = autolearnService;
        this.retrainOrchestrator = retrainOrchestrator;
        this.budgetManager = budgetManager;
        this.qualityTracker = qualityTracker;
    }

    @Scheduled(fixedDelayString = "${uaw.autolearn.tickMs:${idle.pollMillis:60000}}", scheduler = "uawAutolearnTaskScheduler")
    public void tick() {
        boolean enabled = isEnabled();
        boolean idleTriggerEnabled = isIdleTriggerEnabled();
        traceIdleCheck(enabled, idleTriggerEnabled);
        if (!enabled) {
            traceIdleSkip("disabled", -1.0d);
            return;
        }
        if (!idleTriggerEnabled) {
            traceIdleSkip("idle_trigger_disabled", -1.0d);
            return;
        }

        // [PATCH] 공통 게이트: 유저 부재 + CPU 여유 + 주요 breaker OPEN 여부
        double idleThreshold = props.getIdle().getCpuThreshold();
        UawOrchestrationGate.Decision d = uawGate.decide(
                OrchStageKeys.UAW_AUTOLEARN,
                idleThreshold,
                NightmareKeys.CHAT_DRAFT,
                NightmareKeys.FAST_LLM_COMPLETE
        );
        if (!d.allowed()) {
            switch (d.reason()) {
                case "user_present" -> log.debug("[UAW] Skip: not user-absent.");
                case "cpu_high" -> log.debug("[UAW] Skip: cpu high load={} threshold={}", d.cpuLoad(), idleThreshold);
                case "breaker_open" -> log.debug("[UAW] Skip: breaker-open.");
                case "stage_disabled" -> log.debug("[UAW] Skip: stage policy disabled.");
                default -> log.debug("[UAW] Skip: {}", d.reason());
            }
            traceIdleSkip(d.reason(), d.cpuLoad());
            return;
        }

        if (failurePatterns != null && failurePatterns.isCoolingDown("llm")) {
            log.warn("[UAW] Skip: llm failure cooldown.");
            traceIdleSkip("llm_cooldown", d.cpuLoad());
            return;
        }

        Map<String, Object> gpuAdmission = gpuGatewayAdmissionPreflight();
        if (gpuAdmission != null && !"ok".equals(String.valueOf(gpuAdmission.getOrDefault("status", "")))) {
            log.info("[UAW] Skip: desktop GPU gateway admission status={}", gpuAdmission.getOrDefault("status", ""));
            traceIdleGpuGatewayBlocked(gpuAdmission, d.cpuLoad());
            return;
        }

        if (!lock.tryLock()) {
            // already running
            traceIdleSkip("already_running", d.cpuLoad());
            return;
        }

        AutoLearnBudgetManager.BudgetLease lease = null;
        long startMs = System.currentTimeMillis();
        boolean success = false;
        try {
            lease = budgetManager.tryAcquire();
            if (lease == null) {
                log.debug("[UAW] Skip: budget/cooldown/backoff.");
                traceIdleSkip("budget_denied", d.cpuLoad());
                return;
            }

            if (lease.probe()) {
                log.info("[UAW] Probe budget lease granted (fail-soft recovery check).");
            }

            String sessionId = "uaw-idle-" + startMs;
            String traceId = "uaw-" + startMs;
			try (TraceContext ignored = TraceContext.attach(sessionId, traceId)) {
				int maxCycleSeconds = Math.max(1, props.getMaxCycleSeconds());
				long deadline = System.nanoTime() + Duration.ofSeconds(maxCycleSeconds).toNanos();
				traceIdleStart(sessionId, traceId, lease.probe(), maxCycleSeconds);

				PreemptionToken token = () -> !userAbsenceGate.isUserAbsentNow();

				File dataset = new File(resolveDatasetPath());
				AutoLearnCycleResult result = autolearnService.runCycle(dataset, sessionId, token, deadline);
				traceIdleResult(result);

				if (result.abortedByUser()) {
					log.info("[UAW] User returned; abort cycle (attempted={}, accepted={}).", result.attempted(), result.acceptedCount());
					traceIdleAbortUserReturned(result);
					success = true; // aborted is not a failure
					return;
				}

                if (result.acceptedCount() > 0) {
					log.info("[UAW] Cycle done: attempted={}, accepted={}, datasetFileHash={}, datasetFileLength={}, datasetPathHash={}",
							result.attempted(), result.acceptedCount(),
                            datasetFileHash(result.datasetPath()), datasetFileLength(result.datasetPath()),
                            hashOrEmpty(result.datasetPath()));
				}

            // Closed-loop: retrain/reindex step
				if (token.shouldAbort()) {
					traceIdleAbortUserReturned(result);
					success = true;
					return;
				}
				if (!result.trainAllowed()) {
					log.warn("[UAW] Retrain blocked by AutoLearn quality guard: errorRate={}, max={}, topProblem={}, decision={}",
							result.errorRateWindow(), props.getValidation().getMaxTrainErrorRate(), result.topProblem(), result.trainDecision());
					traceIdleRetrainBlocked(result);
					success = true;
					return;
				}
                String retrainDisabledReason = retrainDisabledReason();
                if (!retrainDisabledReason.isBlank()) {
                    traceIdleRetrainDisabled(result, retrainDisabledReason);
                    success = true;
                    return;
                }
				int ingested = retrainOrchestrator.maybeRetrain(dataset.toPath(), result.acceptedCount(), token);
				traceIdleRetrainResult(result, ingested);

				// [PATCH] Optional: run one self-clean cycle after retrain (shadow-merge/quarantine-redrive/global rebuild)
				if (selfCleanOrchestrator != null) {
					if (token.shouldAbort()) {
						traceIdleAbortUserReturned(result);
						success = true;
						return;
					}
					try {
						selfCleanOrchestrator.tick();
					} catch (Exception sc) {
						log.debug("[UAW] self-clean invocation failed (fail-soft). errorHash={} errorLength={}",
                                SafeRedactor.hashValue(messageOf(sc)), messageLength(sc));
					}
				}

				success = true;
			}
        } catch (Exception e) {
            log.warn("[UAW] tick error. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        } finally {
            if (lease != null) {
                budgetManager.onFinish(lease, success);
            }
            lock.unlock();
            long dur = System.currentTimeMillis() - startMs;
            if (dur > 1000) {
                log.debug("[UAW] tick finished in {} ms", dur);
            }
        }
    }

    private boolean isEnabled() {
        Boolean uaw = env.getProperty("uaw.autolearn.enabled", Boolean.class);
        if (uaw != null) return uaw;
        Boolean legacy = env.getProperty("autolearn.enabled", Boolean.class);
        if (legacy != null) return legacy;
        Boolean trainIdle = env.getProperty("train_idle.enabled", Boolean.class);
        if (trainIdle != null) return trainIdle;
        return true;
    }

    private boolean isIdleTriggerEnabled() {
        boolean propEnabled = props.getIdleTrigger() == null || props.getIdleTrigger().isEnabled();
        Boolean envEnabled = env.getProperty("uaw.autolearn.idle-trigger.enabled", Boolean.class);
        return propEnabled && (envEnabled == null || envEnabled);
    }

    private String retrainDisabledReason() {
        UawAutolearnProperties.RuntimeNode node = props == null ? null : props.getRuntimeNode();
        if (node != null && !node.isHeavyWorkloadsAllowed()) {
            return "runtime_heavy_workloads_disabled";
        }
        boolean propEnabled = props.getRetrain() == null || props.getRetrain().isEnabled();
        Boolean envEnabled = env.getProperty("uaw.autolearn.retrain.enabled", Boolean.class);
        if (!propEnabled || (envEnabled != null && !envEnabled)) {
            return "runtime_retrain_disabled";
        }
        String gpuReason = gpuHardwareRetrainDisabledReason();
        if (!gpuReason.isBlank()) {
            return gpuReason;
        }
        return "";
    }

    private String gpuHardwareRetrainDisabledReason() {
        if (!boolProp("awx.gpu-hardware.admission.enabled",
                boolProp("awx.gpu-hardware.telemetry.enabled", false))) {
            return "";
        }
        Map<String, Object> hardware = GpuHardwareDiagnostics.snapshot(env);
        Map<String, Object> admission = GpuHardwareDiagnostics.admissionFromSnapshot(hardware);
        if (Boolean.TRUE.equals(admission.getOrDefault("retrainAllowed", true))) {
            return "";
        }
        String reason = safe(String.valueOf(admission.getOrDefault("reason", "")));
        TraceStore.put("uaw.gpu-hardware.admission.retrainBlocked", true);
        return "gpu_hardware_" + (reason.isBlank() ? "admission_blocked" : reason);
    }

    private Map<String, Object> gpuGatewayAdmissionPreflight() {
        if (!gpuGatewayAdmissionRequired()) {
            return null;
        }
        try {
            Map<String, Object> preflight = GpuGatewayDiagnostics.preflight(env);
            String status = safe(String.valueOf(preflight.getOrDefault("status", "")));
            TraceStore.put("uaw.gpu-gateway.admission.required", true);
            TraceStore.put("uaw.gpu-gateway.admission.status", status);
            return preflight;
        } catch (Exception ex) {
            Map<String, Object> preflight = new LinkedHashMap<>();
            preflight.put("enabled", true);
            preflight.put("status", "preflight_error");
            preflight.put("errorClass", ex.getClass().getSimpleName());
            TraceStore.put("uaw.gpu-gateway.admission.required", true);
            TraceStore.put("uaw.gpu-gateway.admission.status", "preflight_error");
            return preflight;
        }
    }

    private boolean gpuGatewayAdmissionRequired() {
        if (!boolProp("awx.gpu-gateway.preflight.admission-enabled", true)) {
            return false;
        }
        if (!boolProp("awx.gpu-gateway.enabled", false)) {
            return false;
        }
        UawAutolearnProperties.RuntimeNode node = props == null ? null : props.getRuntimeNode();
        boolean controlPlane = node == null
                ? boolProp("uaw.autolearn.runtime-node.control-plane", boolProp("awx.node.control-plane", false))
                : node.isControlPlane();
        boolean heavyAllowed = node == null
                ? boolProp("uaw.autolearn.runtime-node.heavy-workloads-allowed",
                        boolProp("awx.node.heavy-workloads-allowed", true))
                : node.isHeavyWorkloadsAllowed();
        return controlPlane && !heavyAllowed;
    }

    private boolean boolProp(String key, boolean defaultValue) {
        String value = env == null ? null : env.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String resolveDatasetPath() {
        String p = env.getProperty("uaw.autolearn.dataset.path");
        if (p != null && !p.isBlank()) return p;
        p = env.getProperty("autolearn.dataset.path");
        if (p != null && !p.isBlank()) return p;
        p = env.getProperty("dataset.train-file-path");
        if (p != null && !p.isBlank()) return p;
        return props.getDataset().getPath();
    }

    private void traceIdleCheck(boolean enabled, boolean idleTriggerEnabled) {
        Map<String, Object> data = baseIdleData();
        data.put("enabled", enabled);
        data.put("idleTriggerEnabled", idleTriggerEnabled);
        data.put("quietGate", "pending");
        traceIdle("uaw.idle.check", data);
    }

    private void traceIdleSkip(String reason, double cpuLoad) {
        Map<String, Object> data = baseIdleData();
        data.put("reason", safe(reason));
        data.put("cpuLoad", cpuLoad);
        TraceStore.put("uaw.idle.skip.reason", safe(reason));
        traceIdle("uaw.idle.skip", data);
    }

    private void traceIdleStart(String sessionId, String traceId, boolean probe, int maxCycleSeconds) {
        Map<String, Object> data = baseIdleData();
        putSessionDiagnostics(data, sessionId);
        data.put("hasTraceId", !trimToEmpty(traceId).isEmpty());
        data.put("traceHash", hashOrEmpty(traceId));
        data.put("probe", probe);
        data.put("deadlineSeconds", maxCycleSeconds);
        traceIdle("uaw.idle.start", data);
    }

    private void traceIdleResult(AutoLearnCycleResult result) {
        Map<String, Object> data = baseIdleData();
        if (result != null) {
            data.put("attempted", result.attempted());
            data.put("accepted", result.acceptedCount());
            data.put("abortedByUser", result.abortedByUser());
            putDatasetDiagnostics(data, result.datasetPath());
            data.put("errorRateWindow", result.errorRateWindow());
            data.put("thresholdTuningDelta", result.thresholdTuningDelta());
            data.put("trainAllowed", result.trainAllowed());
            data.put("topProblem", safe(result.topProblem()));
            data.put("trainDecision", safe(result.trainDecision()));
            data.put("errorCount", result.errorCount());
            data.put("errorRate", result.errorRate());
            data.put("dominantFailure", safe(result.dominantFailure()));
            data.put("diagnosis", safe(result.diagnosis()));
            data.put("phaseFailures", result.phaseFailures());
        }
        traceIdle("uaw.idle.result", data);
    }

    private void traceIdleRetrainBlocked(AutoLearnCycleResult result) {
        Map<String, Object> data = baseIdleData();
        if (result != null) {
            data.put("attempted", result.attempted());
            data.put("accepted", result.acceptedCount());
            putDatasetDiagnostics(data, result.datasetPath());
            data.put("errorRateWindow", result.errorRateWindow());
            data.put("thresholdTuningDelta", result.thresholdTuningDelta());
            data.put("topProblem", safe(result.topProblem()));
            data.put("trainDecision", safe(result.trainDecision()));
            data.put("errorCount", result.errorCount());
            data.put("errorRate", result.errorRate());
            data.put("dominantFailure", safe(result.dominantFailure()));
            data.put("diagnosis", safe(result.diagnosis()));
            data.put("phaseFailures", result.phaseFailures());
        }
        traceIdle("uaw.idle.retrain.blocked", data);
        try {
            TraceStore.putIfAbsent("uaw.idle.loop-diagnostics", new LinkedHashMap<>(data));
            Map<String, Object> hotspot = new LinkedHashMap<>();
            hotspot.put("hotspot", hotspotForFailure(String.valueOf(data.getOrDefault("dominantFailure", ""))));
            hotspot.put("topProblem", data.getOrDefault("topProblem", ""));
            hotspot.put("reasonCounts", data.getOrDefault("phaseFailures", Map.of()));
            hotspot.put("trainDecision", data.getOrDefault("trainDecision", ""));
            hotspot.put("errorRateWindow", data.getOrDefault("errorRateWindow", 0.0d));
            hotspot.put("flags", java.util.List.of("error_rate_threshold"));
            TraceStore.putIfAbsent("uaw.autolearn.loop.hotspot", hotspot);
        } catch (Throwable ignore) {
            traceSuppressed("quality.hotspot");
        }
    }

    private void traceIdleRetrainDisabled(AutoLearnCycleResult result, String reason) {
        Map<String, Object> data = baseIdleData();
        data.put("reason", safe(reason));
        if (result != null) {
            data.put("attempted", result.attempted());
            data.put("accepted", result.acceptedCount());
            putDatasetDiagnostics(data, result.datasetPath());
            data.put("trainDecision", "CURATE_ONLY");
            data.put("dominantFailure", "");
            data.put("diagnosis", "dataset_curated_without_retrain");
        }
        TraceStore.put("uaw.retrain.skip.reason", safe(reason));
        TraceStore.put("uaw.retrain.disabledReason", safe(reason));
        traceIdle("uaw.idle.retrain.disabled", data);
        try {
            qualityTracker.mergeLastLoopDiagnostics("retrain_disabled", data);
        } catch (Throwable ignore) {
            traceSuppressed("quality.retrainDisabled");
        }
    }

    private void traceIdleGpuGatewayBlocked(Map<String, Object> preflight, double cpuLoad) {
        Map<String, Object> data = baseIdleData();
        String status = safe(String.valueOf(preflight == null ? "" : preflight.getOrDefault("status", "")));
        data.put("reason", "gpu_gateway_unreachable");
        data.put("cpuLoad", cpuLoad);
        data.put("trainDecision", "BLOCK_HEAVY_WORK");
        data.put("diagnosis", "desktop_gpu_gateway_unreachable");
        data.put("gpuGatewayPreflight", compactPreflight(preflight));
        TraceStore.put("uaw.idle.skip.reason", "gpu_gateway_unreachable");
        TraceStore.put("uaw.gpu-gateway.admission.blocked", true);
        TraceStore.put("uaw.gpu-gateway.admission.blocked.count", 1);
        TraceStore.put("uaw.gpu-gateway.admission.status", status);
        traceIdle("uaw.idle.gpu-gateway.blocked", data);
        try {
            qualityTracker.mergeLastLoopDiagnostics("gpu_gateway_blocked", data);
        } catch (Throwable ignore) {
            traceSuppressed("quality.gpuGatewayBlocked");
        }
    }

    private void traceIdleRetrainResult(AutoLearnCycleResult result, int ingested) {
        Map<String, Object> data = baseIdleData();
        if (result != null) {
            data.put("accepted", result.acceptedCount());
            putDatasetDiagnostics(data, result.datasetPath());
        }
        data.put("ingestedCount", Math.max(0, ingested));
        data.put("skipReason", safeTraceString("uaw.retrain.skip.reason"));
        data.put("check", traceMap("uaw.retrain.check"));
        data.put("ingestCount", safeTraceNumber("uaw.retrain.ingest.count"));
        data.put("ingestParsed", safeTraceNumber("uaw.retrain.ingest.parsed"));
        data.put("ingestQueued", safeTraceNumber("uaw.retrain.ingest.queued"));
        data.put("ingestFailed", safeTraceNumber("uaw.retrain.ingest.failed"));
        data.put("ingestSummary", traceMap("uaw.retrain.ingest.summary"));
        traceIdle("uaw.idle.retrain.result", data);
        try {
            qualityTracker.mergeLastLoopDiagnostics("retrain", data);
        } catch (Throwable ignore) {
            traceSuppressed("quality.retrain");
        }
    }

    private void traceIdleAbortUserReturned(AutoLearnCycleResult result) {
        Map<String, Object> data = baseIdleData();
        if (result != null) {
            data.put("attempted", result.attempted());
            data.put("accepted", result.acceptedCount());
            putDatasetDiagnostics(data, result.datasetPath());
            data.put("errorCount", result.errorCount());
            data.put("errorRate", result.errorRate());
            data.put("dominantFailure", safe(result.dominantFailure()));
            data.put("diagnosis", safe(result.diagnosis()));
            data.put("phaseFailures", result.phaseFailures());
        }
        traceIdle("uaw.idle.abort.user_returned", data);
    }

    private void traceIdle(String key, Map<String, Object> data) {
        try {
            TraceStore.put(key, new LinkedHashMap<>(data));
            TraceStore.put("uaw.idle.last", key);
        } catch (Throwable ignore) {
            traceSuppressed("traceIdle.store");
        }
        if (!isIdleBreadcrumbEnabled()) {
            return;
        }
        try {
            OrchEventEmitter.breadcrumb(key, "idle-autolearn", "UawAutolearnOrchestrator", data);
        } catch (Throwable ignore) {
            traceSuppressed("traceIdle.breadcrumb");
        }
    }

    private boolean isIdleBreadcrumbEnabled() {
        return props.getIdleTrigger() == null || props.getIdleTrigger().isBreadcrumbEnabled();
    }

    private Map<String, Object> baseIdleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("component", "UawAutolearnOrchestrator");
        data.put("rule", "idle_autolearn_user_absence_gate");
        UawAutolearnProperties.RuntimeNode node = props == null ? null : props.getRuntimeNode();
        if (node != null) {
            data.put("runtimeNodeRole", safe(node.getRole()));
            data.put("runtimeNodeExecution", safe(node.getExecutionNode()));
            data.put("runtimeNodeControlPlane", node.isControlPlane());
            data.put("runtimeNodeHeavyWorkloadsAllowed", node.isHeavyWorkloadsAllowed());
            data.put("runtimeNodeSchedulingAssistant", node.isSchedulingAssistant());
            data.put("learningLoopMode", safe(node.getLearningLoopMode()));
        }
        Map<String, Object> gpuGateway = GpuGatewayDiagnostics.snapshot(env);
        Map<String, Object> gpuHardware = GpuHardwareDiagnostics.snapshot(env);
        data.put("gpuGateway", SelfLearningBridgeDiagnostics.compactGpuGateway(gpuGateway));
        data.put("gpuHardware", SelfLearningBridgeDiagnostics.compactGpuHardware(gpuHardware));
        data.put("selfLearningBridge", SelfLearningBridgeDiagnostics.snapshot(env, gpuGateway, gpuHardware));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compactPreflight(Map<String, Object> preflight) {
        if (preflight == null || preflight.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", preflight.getOrDefault("enabled", false));
        out.put("status", safe(String.valueOf(preflight.getOrDefault("status", ""))));
        out.put("configuredCount", preflight.getOrDefault("configuredCount", 0));
        out.put("reachableCount", preflight.getOrDefault("reachableCount", 0));
        out.put("targetExecutionNode", safe(String.valueOf(preflight.getOrDefault("targetExecutionNode", ""))));
        Object endpoints = preflight.get("endpoints");
        if (endpoints instanceof Map<?, ?> endpointMap) {
            Map<String, Object> endpointStatuses = new LinkedHashMap<>();
            endpointMap.forEach((name, value) -> {
                if (value instanceof Map<?, ?> endpoint) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("status", safeObject(endpoint.get("status")));
                    e.put("guardStatus", safeObject(endpoint.get("guardStatus")));
                    e.put("reachable", Boolean.TRUE.equals(endpoint.get("reachable")));
                    e.put("endpointHost", safeObject(endpoint.get("endpointHost")));
                    e.put("endpointHostPort", safeObject(endpoint.get("endpointHostPort")));
                    endpointStatuses.put(String.valueOf(name), e);
                }
            });
            out.put("endpoints", endpointStatuses);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> traceMap(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    private static Number safeTraceNumber(String key) {
        Object value = TraceStore.get(key);
        return value instanceof Number number ? number : 0;
    }

    private static String safeTraceString(String key) {
        Object value = TraceStore.get(key);
        return value == null ? "" : safe(String.valueOf(value));
    }

    private static String safe(String value) {
        return value == null ? "" : SafeRedactor.traceLabel(value);
    }

    private static String safeObject(Object value) {
        return value == null ? "" : safe(String.valueOf(value));
    }

    private static void putSessionDiagnostics(Map<String, Object> data, String sessionId) {
        String sid = trimToEmpty(sessionId);
        data.put("hasSessionId", !sid.isEmpty());
        data.put("sessionHash", hashOrEmpty(sid));
    }

    private static void putDatasetDiagnostics(Map<String, Object> data, String datasetPath) {
        String path = trimToEmpty(datasetPath);
        data.put("hasDatasetPath", !path.isEmpty());
        data.put("datasetFileHash", datasetFileHash(path));
        data.put("datasetFileLength", datasetFileLength(path));
        data.put("datasetPathHash", hashOrEmpty(path));
    }

    private static String datasetFileName(String path) {
        String p = trimToEmpty(path).replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }

    private static String datasetFileHash(String path) {
        String fileName = datasetFileName(path);
        return fileName.isEmpty() ? "" : hashOrEmpty(fileName);
    }

    private static int datasetFileLength(String path) {
        return datasetFileName(path).length();
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceSuppressed(String stage) {
        log.debug("[UAW] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String hotspotForFailure(String failure) {
        String f = failure == null ? "" : failure.toLowerCase(java.util.Locale.ROOT);
        if (f.contains("writer") || f.contains("ingest") || f.contains("upsert")) {
            return "writer";
        }
        if (f.contains("provider") || f.contains("api_disabled") || f.contains("disabled")) {
            return "provider";
        }
        if (f.contains("insufficient_evidence") || f.contains("final_gate") || f.contains("empty_result")
                || f.contains("low_context_diversity")) {
            return "evidence_gate";
        }
        if (f.contains("context_contamination") || f.contains("contamination") || f.contains("legacy_context")) {
            return "context_contamination";
        }
        if (f.contains("sample_score") || f.contains("error_rate") || f.contains("threshold")) {
            return "threshold";
        }
        if (f.contains("requery")) {
            return "requery";
        }
        if (f.contains("spike") || f.contains("drift")) {
            return "signal_shift";
        }
        if (f.contains("validation")) {
            return "validation";
        }
        return "none";
    }

}
