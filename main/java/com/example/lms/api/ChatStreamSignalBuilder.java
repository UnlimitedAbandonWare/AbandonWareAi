package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.SelectionEntropyTraceSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ChatStreamSignalBuilder {
    private static final long SLOW_MODEL_TOOK_MS = 60_000L;
    static final String REQUEST_ATTEMPT_SUMMARY_KEY = "llm.requestAttempt.summary.v1";
    private static final int REQUEST_ATTEMPT_SUMMARY_CAPACITY = 8;

    private static final System.Logger LOG = System.getLogger(ChatStreamSignalBuilder.class.getName());

    private ChatStreamSignalBuilder() {
    }

    static ChatStreamEvent.SelectionEntropySignal buildSelectionEntropySignal(
            Map<String, Object> meta) {
        return SelectionEntropyTraceSupport.fromTrace(meta)
                .map(value -> new ChatStreamEvent.SelectionEntropySignal(
                        value.schema(),
                        value.mode(),
                        value.algorithmVersion(),
                        value.replayAccepted(),
                        value.coherenceStatus(),
                        value.seedFingerprint(),
                        value.decisionDigest(),
                        value.decisionCount(),
                        value.drawCount(),
                        value.stableTieBreakCount(),
                        value.candidateDriftCount(),
                        value.routerDrawCount(),
                        value.strategyDrawCount(),
                        value.ensembleDrawCount(),
                        value.completionOrderDeterministic(),
                        value.reasonCode()))
                .orElse(null);
    }

    static ChatStreamEvent.TraceSignal buildTraceSignal(
            Map<String, Object> meta,
            String traceId,
            String requestId,
            String sessionId) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        Map<String, Integer> stageCounts = toIntegerMap(firstNonNull(
                safeMeta.get("rag.eval.stageCounts"),
                safeMeta.get("stageCounts")));
        int eventCount = collectionSize(safeMeta.get("orch.events.v1"));
        if (eventCount == 0) {
            eventCount = numberAsInt(safeMeta.get("orch.eventCount"), 0);
        }
        String failureClass = firstNonBlank(
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("lastFailureReason")),
                safeString(safeMeta.get("rag.eval.failureClass")),
                providerCancellationFailureClass(safeMeta));
        String reasonCode = firstNonBlank(
                safeString(safeMeta.get("reasonCode")),
                safeString(safeMeta.get("lastControlAction")),
                safeString(safeMeta.get("rag.eval.reasonCode")));
        return new ChatStreamEvent.TraceSignal(
                hashIdentifier(firstNonBlank(traceId, safeString(safeMeta.get("trace.id")), safeString(safeMeta.get("traceId")))),
                hashIdentifier(firstNonBlank(requestId, safeString(safeMeta.get("requestId")), safeString(safeMeta.get("x-request-id")))),
                hashIdentifier(firstNonBlank(sessionId, safeString(safeMeta.get("sessionId")), safeString(safeMeta.get("sid")))),
                eventCount,
                failureClass,
                reasonCode,
                stageCounts);
    }

    static ChatStreamEvent.PipelineSnapshot buildPipelineSnapshot(
            Map<String, Object> meta,
            String answerMode,
            Long traceTurnId,
            ChatStreamEvent.TraceSignal traceSignal) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        Integer webCount = firstNonNull(
                countValue(safeMeta.get("webCount")),
                countValue(safeMeta.get("web.count")),
                countValue(safeMeta.get("finalWebTopKCount")),
                collectionSizeOrNull(safeMeta.get("finalWebTopK")));
        Integer vectorCount = firstNonNull(
                countValue(safeMeta.get("vectorCount")),
                countValue(safeMeta.get("vector.count")),
                countValue(safeMeta.get("finalVectorTopKCount")),
                collectionSizeOrNull(safeMeta.get("finalVectorTopK")));
        Integer finalContextCount = firstNonNull(
                countValue(safeMeta.get("finalContextCount")),
                countValue(safeMeta.get("final.context.count")),
                countValue(safeMeta.get("prompt.context.count")));
        if (finalContextCount == null && (webCount != null || vectorCount != null)) {
            finalContextCount = Math.max(0, webCount == null ? 0 : webCount) + Math.max(0, vectorCount == null ? 0 : vectorCount);
        }

        String failureClass = firstNonBlank(
                traceSignal == null ? null : traceSignal.failureClass(),
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("lastFailureReason")),
                safeString(safeMeta.get("rag.eval.failureClass")),
                providerCancellationFailureClass(safeMeta));
        String disabledReason = firstNonBlank(
                safeString(safeMeta.get("llm.final.skipped")),
                safeString(safeMeta.get("disabledReason")),
                safeString(safeMeta.get("disabledReasonCanonical")),
                safeString(safeMeta.get("web.naver.disabledReasonCanonical")),
                safeString(safeMeta.get("web.brave.disabledReasonCanonical")),
                safeString(safeMeta.get("web.serpapi.disabledReasonCanonical")),
                safeString(safeMeta.get("web.tavily.disabledReasonCanonical")),
                safeString(safeMeta.get("selfask.3way.api.disabledReason")),
                safeString(safeMeta.get("llmrouter.api.disabledReason")));

        ChatStreamEvent.PipelineSnapshot snapshot = new ChatStreamEvent.PipelineSnapshot(
                firstNonBlank(
                        safeString(safeMeta.get("plan.id")),
                        safeString(safeMeta.get("plan.auto")),
                        safeString(safeMeta.get("planId")),
                        safeString(safeMeta.get("planApplied"))),
                firstNonBlank(
                        safeString(safeMeta.get("route")),
                        safeString(safeMeta.get("rag.route")),
                        safeString(safeMeta.get("rag.route.hint")),
                        safeString(safeMeta.get("answer.route"))),
                firstNonBlank(answerMode, safeString(safeMeta.get("answer.mode"))),
                traceTurnId,
                webCount,
                vectorCount,
                finalContextCount,
                firstNonNull(
                        asDouble(safeMeta.get("citationCoverage"), null),
                        asDouble(safeMeta.get("citation.coverage"), null),
                        asDouble(safeMeta.get("rag.citation.coverage"), null),
                        asDouble(safeMeta.get("citationGate.coverage"), null)),
                firstNonNull(
                        asDouble(safeMeta.get("finalSigmoid"), null),
                        asDouble(safeMeta.get("finalSigmoid.score"), null),
                        asDouble(safeMeta.get("gate.finalSigmoid.score"), null),
                        asDouble(safeMeta.get("finalSigmoidGate.score"), null)),
                failureClass,
                disabledReason);
        return isEmptyPipelineSnapshot(snapshot) ? null : snapshot;
    }

    static ChatStreamEvent.DebugFxSignal buildDebugFxSignal(
            Map<String, Object> meta,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        String copilotReason = debugCopilotReason(safeMeta);
        String reason = firstNonBlank(
                traceSignal == null ? null : traceSignal.reasonCode(),
                pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason(),
                pipelineSnapshot == null ? null : pipelineSnapshot.failureClass(),
                copilotReason,
                safeString(safeMeta.get("failureClass")),
                safeString(safeMeta.get("reasonCode")));
        Map<?, ?> boundaryStep = primaryBoundaryStep(safeMeta);
        String phase = firstNonBlank(
                boundaryField(boundaryStep, "phase"),
                pipelineSnapshot == null ? null : pipelineSnapshot.route(),
                safeString(safeMeta.get("orch.mode")),
                "pipeline");
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("answerMode", pipelineSnapshot == null ? null : pipelineSnapshot.answerMode());
        labels.put("failureClass", pipelineSnapshot == null ? null : pipelineSnapshot.failureClass());
        labels.put("disabledReason", pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason());
        labels.put("traceIdHash", traceSignal == null ? null : traceSignal.traceIdHash());
        labels.put("debugCause", copilotReason);
        labels.put("localLlmTriggerReason", safeString(safeMeta.get("llm.localSmoke.operatorAction.triggerReason")));
        labels.put("localLlmFailureClass", safeString(safeMeta.get("llm.localSmoke.operatorAction.failureClass")));
        labels.put("localLlmNextAction", safeString(safeMeta.get("llm.localSmoke.operatorAction.nextAction")));
        labels.put("localLlmActionScore", safeString(safeMeta.get("llm.localSmoke.operatorAction.actionScore")));
        labels.put("localLlmScoreDelta", safeString(safeMeta.get("llm.localSmoke.operatorAction.scoreDelta")));
        labels.put("localLlmUpstreamStatus", safeString(safeMeta.get("llm.localSmoke.operatorAction.upstreamStatus")));
        labels.put("localLlmUpstreamFailureClass",
                safeString(safeMeta.get("llm.localSmoke.operatorAction.upstreamFailureClass")));
        labels.put("localLlmUpstreamNextAction",
                safeString(safeMeta.get("llm.localSmoke.operatorAction.upstreamNextAction")));
        labels.put("ollamaNativeRoute", safeString(safeMeta.get("llm.ollamaNative.route")));
        labels.put("ollamaNativeGpuMode", safeString(safeMeta.get("llm.ollamaNative.gpuMode")));
        labels.put("ollamaNativeNumGpu", safeString(safeMeta.get("llm.ollamaNative.numGpu")));
        labels.put("ollamaNativePromptLength", nonNegativeCountLabel(safeMeta.get("llm.ollamaNative.promptLength")));
        labels.put("ollamaNativeMaxTokens", nonNegativeCountLabel(safeMeta.get("llm.ollamaNative.maxTokens")));
        labels.put("gatewaySelectedRoute", gatewayLabel(safeMeta.get("llm.gateway.selectedRoute")));
        labels.put("gatewayLocalState", gatewayLabel(safeMeta.get("llm.localEndpoint.state")));
        labels.put("gatewayLocalReason", gatewayLabel(safeMeta.get("llm.localEndpoint.reason")));
        labels.put("gatewayGpuState", gatewayLabel(safeMeta.get("llm.localEndpoint.gpuState")));
        labels.put("gatewayModelState", gatewayLabel(safeMeta.get("llm.localEndpoint.modelState")));
        labels.put("gatewayFallbackRoute", gatewayLabel(safeMeta.get("llm.gateway.fallback.selectedRoute")));
        labels.put("gatewayFallbackCount", combinedFallbackCountLabel(safeMeta));
        labels.put("gatewayAttemptCount", nonNegativeCountLabel(safeMeta.get("llm.gateway.attemptCount")));
        labels.put("gatewayLatencyMs", nonNegativeCountLabel(safeMeta.get("llm.gateway.latencyMs")));
        labels.put("gatewayRemainingMs", nonNegativeCountLabel(safeMeta.get("llm.gateway.fallback.remainingMs")));
        labels.put("gatewayHealthAgeMs", nonNegativeCountLabel(safeMeta.get("llm.localEndpoint.healthAgeMs")));
        labels.put("llmTimeoutWorkerTermination",
                safeString(safeMeta.get("llm.call.timeout.workerTerminationEvidence")));
        if (boundaryStep != null) {
            labels.put("stageBoundaryStage", boundaryField(boundaryStep, "stage"));
            labels.put("stageBoundaryFailureClass", boundaryField(boundaryStep, "failureClass"));
            labels.put("stageBoundaryReason", boundaryField(boundaryStep, "reasonCode"));
        }
        Map<?, ?> verificationStep = boundaryStep(safeMeta, "verification");
        if (verificationStep != null) {
            labels.put("verificationStatus", boundaryField(verificationStep, "status"));
            labels.put("verificationFailureClass", boundaryField(verificationStep, "failureClass"));
            labels.put("verificationReason", boundaryField(verificationStep, "reasonCode"));
        }
        labels.put("chatHarmonyDecision", safeString(safeMeta.get("chat.harmony.postprocess.decision")));
        labels.put("chatHarmonyReason", safeString(safeMeta.get("chat.harmony.postprocess.reason")));
        labels.put("chatHarmonyScore", safeString(safeMeta.get("chat.harmony.postprocess.weightedScore")));
        labels.put("chatHarmonyEvidenceCount", safeString(safeMeta.get("chat.harmony.postprocess.evidenceCount")));
        labels.put("chatHarmonyDegraded", safeString(safeMeta.get("chat.harmony.postprocess.degraded")));
        labels.put("chatHarmonyInputPresent", chatHarmonyInputPresent(safeMeta) ? "true" : null);
        labels.put("traceMemoryRouteDecision",
                traceMemoryLabel(safeMeta, "routeDecision", "traceMemory.recovery.routeDecision"));
        labels.put("traceMemoryVirtualCheckpointKey",
                traceMemoryLabel(safeMeta, "virtualCheckpointLatestKey", "traceMemory.virtualCheckpoint.latestKey"));
        labels.put("traceMemoryVirtualCheckpointStage",
                traceMemoryLabel(safeMeta, "virtualCheckpointLatestStage", "traceMemory.virtualCheckpoint.latestStage"));
        labels.put("traceMemoryVirtualCheckpointPhase",
                traceMemoryLabel(safeMeta, "virtualCheckpointLatestPhase", "traceMemory.virtualCheckpoint.latestPhase"));
        labels.put("traceMemoryCfvmOffered",
                traceMemoryLabel(safeMeta, "cfvmOffered", "traceMemory.cfvm.offered"));
        labels.put("traceMemoryCfvmPatternId",
                traceMemoryLabel(safeMeta, "cfvmPatternId", "traceMemory.cfvm.patternId"));
        labels.put("browserStatus", safeString(safeMeta.get("prompt.agentDebugEvidence.external.browser.status")));
        labels.put("browserEvidenceNeeded",
                safeString(safeMeta.get("prompt.agentDebugEvidence.external.browser.evidenceNeeded")));
        labels.put("browserNextAction",
                externalProofActionLabel(
                        safeMeta.get("prompt.agentDebugEvidence.external.browser.nextAction"), "browser"));
        labels.put("computerUseStatus",
                safeString(safeMeta.get("prompt.agentDebugEvidence.external.computerUse.status")));
        labels.put("computerUseEvidenceNeeded",
                safeString(safeMeta.get("prompt.agentDebugEvidence.external.computerUse.evidenceNeeded")));
        labels.put("computerUseNextAction",
                externalProofActionLabel(
                        safeMeta.get("prompt.agentDebugEvidence.external.computerUse.nextAction"), "computer"));
        labels.put("supabaseStatus", supabaseStatusLabel(safeMeta));
        labels.put("supabaseEvidenceNeeded",
                supabaseEvidenceNeededLabel(safeMeta));
        labels.put("supabaseNextAction",
                supabaseNextActionLabel(safeMeta));
        labels.put("agentDbContextStatus", agentDbContextLabel(safeMeta, "status"));
        labels.put("agentDbContextReason", agentDbContextLabel(safeMeta, "reason"));
        labels.put("agentDbContextNextAction", agentDbContextLabel(safeMeta, "nextAction"));
        labels.put("debugAiMatrixCount", virtualMatrixLabel(safeMeta, "count"));
        labels.put("debugAiMatrixChunkCount", virtualMatrixLabel(safeMeta, "chunkCount"));
        labels.put("debugAiMatrixScore", virtualMatrixLabel(safeMeta, "weightedScore"));
        labels.put("debugAiMatrixDecision", virtualMatrixLabel(safeMeta, "decision"));
        labels.put("debugAiMatrixHotChunkIndex", virtualMatrixLabel(safeMeta, "hotChunkIndex"));
        labels.put("debugAiMatrixHotChunkRiskScore", virtualMatrixLabel(safeMeta, "hotChunkRiskScore"));
        labels.put("debugAiNextAction", debugAiNextAction(safeMeta));
        labels.put("debugAiNextReason", debugAiNextReason(safeMeta));
        labels.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        ensureSmoothChatDebugAiNextAction(labels, safeMeta);
        return new ChatStreamEvent.DebugFxSignal(
                phase,
                firstNonBlank(reason, "ok"),
                reason == null ? "diagnostics" : "resilience",
                reason == null ? "pipeline diagnostics updated" : "pipeline resilience signal updated",
                null,
                labels);
    }

    static List<ChatStreamEvent.TransformerBlockSignal> buildTransformerBlocks(
            Map<String, Object> meta,
            ChatStreamEvent.StatusSignal statusSignal,
            ChatStreamEvent.PipelineSnapshot pipelineSnapshot,
            ChatStreamEvent.TraceSignal traceSignal,
            ChatStreamEvent.ScoreDeltaSignal scoreDeltaSignal,
            ChatStreamEvent.DebugFxSignal debugFxSignal) {
        String streamCode = statusSignal == null ? null : statusSignal.code();
        String streamStatus = streamStatus(streamCode);
        boolean complete = "complete".equalsIgnoreCase(streamCode) || "final".equalsIgnoreCase(streamCode)
                || "finalizing".equalsIgnoreCase(streamCode);
        boolean cancelled = "cancelled".equalsIgnoreCase(streamCode);
        boolean error = "error".equalsIgnoreCase(streamCode) || cancelled;
        boolean modelTerminal = complete;
        String plan = pipelineSnapshot == null ? null : pipelineSnapshot.planId();
        String route = pipelineSnapshot == null ? null : pipelineSnapshot.route();
        Integer web = pipelineSnapshot == null ? null : pipelineSnapshot.webCount();
        Integer vector = pipelineSnapshot == null ? null : pipelineSnapshot.vectorCount();
        Integer context = pipelineSnapshot == null ? null : pipelineSnapshot.finalContextCount();
        String failure = firstNonBlank(
                traceSignal == null ? null : traceSignal.failureClass(),
                pipelineSnapshot == null ? null : pipelineSnapshot.failureClass());
        String disabledReason = pipelineSnapshot == null ? null : pipelineSnapshot.disabledReason();
        String debugReason = debugFxSignal == null ? null : debugFxSignal.code();
        String copilotReason = debugCopilotReason(meta);
        String failureTag = firstFailureTag(meta);
        String boundaryFailure = firstBoundaryFailure(meta);
        Long modelTookMs = statusSignal == null ? null : statusSignal.tookMs();
        String observedModelStatus = modelStatus(meta, error, modelTerminal, modelTookMs);
        String observedModelReason = modelReason(meta, error, cancelled, modelTerminal, modelTookMs);
        boolean observedFallbackRecovery = modelAttemptState(meta).recovered();
        String modelBlockStatus = observedFallbackRecovery
                ? observedModelStatus
                : boundaryStatus(meta, "llm", observedModelStatus);
        String modelBlockReason = observedFallbackRecovery
                ? observedModelReason
                : firstNonBlank(boundaryFailure(meta, "llm"), observedModelReason);
        String recoveryBlockStatus = observedFallbackRecovery
                ? "done"
                : firstNonBlank(boundaryFailure, failure, disabledReason, debugReason, copilotReason, failureTag) == null
                        ? "done"
                        : "warn";
        String recoveryBlockReason = observedFallbackRecovery
                ? observedModelReason
                : firstNonBlank(boundaryFailure, failure, disabledReason, debugReason, copilotReason, failureTag, "ok");
        boolean harmonyObserved = harmonyObserved(meta);
        boolean richDebug = hasAdvancedCoreDebug(meta);
        boolean gatewayObserved = hasAnyPrefix(meta, "llm.localEndpoint.", "llm.gateway.fallback.");
        int gatewayOffset = gatewayObserved ? 1 : 0;

        if (richDebug) {
            List<ChatStreamEvent.TransformerBlockSignal> blocks = new ArrayList<>();
            blocks.add(block("intake", "Intake", "stream", streamStatus,
                    firstNonBlank(streamCode, "stream"), 0, statusSignal == null ? null : statusSignal.tookMs()));
            blocks.add(block("plan", "Plan / MoE", "orchestration",
                    firstNonBlank(plan, route) == null ? (complete ? "skipped" : "queued") : "done",
                    firstNonBlank(plan, route, complete ? "not-measured" : "auto"), 1, null));
            if (queryRewriteObserved(meta)) {
                blocks.add(block("rewrite", "Query Rewrite", "query",
                        queryRewriteStatus(meta, complete),
                        queryRewriteReason(meta), 2, null));
            }
            blocks.add(block("anchor", "Anchor", "compression",
                    anchorStatus(meta, complete),
                    anchorReason(meta), 2, null));
            blocks.add(block("retrieve", "Retrieve", "rag",
                    boundaryStatus(meta, "search",
                            countPositive(web) || countPositive(vector) ? "done" : (complete ? "skipped" : "queued")),
                    firstNonBlank(boundaryFailure(meta, "search"),
                            String.format("web:%d-vector:%d", web == null ? 0 : web, vector == null ? 0 : vector)), 3, null));
            blocks.add(block("rerank", "DPP / Rerank", "rerank",
                    rerankStatus(meta, complete),
                    rerankReason(meta), 4, null));
            blocks.add(block("compose", "Context", "prompt",
                    boundaryStatus(meta, "prompt",
                            countPositive(context) ? "done" : (complete ? "skipped" : "queued")),
                    firstNonBlank(boundaryFailure(meta, "prompt"),
                            context == null ? "context-pending" : String.format("context:%d", context)), 5, null));
            blocks.add(block("model", "Model", "llm",
                    modelBlockStatus,
                    modelBlockReason,
                    6, modelTookMs));
            if (gatewayObserved) {
                blocks.add(block("gateway", "GPU / API Failover", "llm",
                        truthy(value(meta, "llm.gateway.fallback.succeeded")) || truthy(value(meta, "llm.localEndpoint.gpuRecoveryVerified")) ? "done" : "warn",
                        firstNonBlank(gatewayLabel(value(meta, "llm.localEndpoint.reason")),
                                gatewayLabel(value(meta, "llm.gateway.fallback.skippedReason")), "health_unverified"),
                        7, asLong(value(meta, "llm.localEndpoint.latencyMs"))));
            }
            if (harmonyObserved) {
                blocks.add(block("harmony", "Chat Harmony", "postprocess",
                        harmonyStatus(meta, complete),
                        harmonyReason(meta), 7 + gatewayOffset, null));
            }
            blocks.add(block("cfvm", "CFVM Failure", "memory",
                    cfvmStatus(meta, complete),
                    cfvmReason(meta), (harmonyObserved ? 8 : 7) + gatewayOffset, null));
            blocks.add(block("recover", "Resilience", "recovery",
                    recoveryBlockStatus,
                    recoveryBlockReason,
                    (harmonyObserved ? 9 : 8) + gatewayOffset, null));
            blocks.add(block("supabase", "Supabase", "external",
                    supabaseStatus(meta, complete),
                    supabaseReason(meta), (harmonyObserved ? 10 : 9) + gatewayOffset, null));
            return List.copyOf(blocks);
        }

        return List.of(
                block("intake", "Intake", "stream", streamStatus,
                        firstNonBlank(streamCode, "stream"), 0, statusSignal == null ? null : statusSignal.tookMs()),
                block("route", "MoE Route", "orchestration",
                        firstNonBlank(plan, route) == null ? (complete ? "skipped" : "queued") : "done",
                        firstNonBlank(plan, route, complete ? "not measured" : "auto"), 1, null),
                block("retrieve", "Retrieve", "rag",
                        boundaryStatus(meta, "search",
                                countPositive(web) || countPositive(vector) ? "done" : (complete ? "skipped" : "queued")),
                        firstNonBlank(boundaryFailure(meta, "search"),
                                String.format("web=%d vector=%d", web == null ? 0 : web, vector == null ? 0 : vector)), 2, null),
                block("compose", "Context", "prompt",
                        boundaryStatus(meta, "prompt",
                                countPositive(context) ? "done" : (complete ? "skipped" : "queued")),
                        firstNonBlank(boundaryFailure(meta, "prompt"),
                                context == null ? "context pending" : String.format("context=%d", context)), 3, null),
                block("model", "Model", "llm",
                        modelBlockStatus,
                        modelBlockReason,
                        4, modelTookMs),
                block("recover", "Resilience", "recovery",
                        recoveryBlockStatus,
                        recoveryBlockReason, 5, null));
    }

    private static boolean hasAdvancedCoreDebug(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return false;
        }
        return hasAnyPrefix(meta, "llm.localEndpoint.", "llm.gateway.fallback.", "overdrive.", "rag.anchor.", "anchor.", "dpp.", "hypernova.dpp", "rerank.",
                "llm.fastBail", "llm.error.", "llm.endpoint.", "llm.modelGuard.", "llm.model.policy.",
                "llm.defaultModel.", "llm.client.", "llm.ollamaNative.", "llm.gateway.route.", "modelGuard.",
                "cfvm.", "supabase.", "failureTags", "dbg.copilot.", "queryTransformer.subQueries.superTokens.",
                "mla.breadcrumb.step.", "ml.breadcrumbs.v1", "chat.harmony.", "harmony.score.",
                "rag.answerQuality.", "debug.ai.metrics.virtualMatrix.");
    }

    private static boolean hasAnyPrefix(Map<String, Object> meta, String... prefixes) {
        if (meta == null || prefixes == null) {
            return false;
        }
        for (String key : meta.keySet()) {
            if (key == null) {
                continue;
            }
            for (String prefix : prefixes) {
                if (prefix != null && key.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<?, ?> primaryBoundaryStep(Map<String, Object> meta) {
        Map<?, ?> supportingStep = null;
        for (String stage : List.of("request", "orchestration", "search", "prompt", "llm", "sse")) {
            Map<?, ?> step = boundaryStep(meta, stage);
            if (step == null) {
                continue;
            }
            if (supportingStep == null) {
                supportingStep = step;
            }
            String failureClass = boundaryField(step, "failureClass");
            if (failureClass != null
                    && !"fallback".equalsIgnoreCase(failureClass)
                    && !"ok".equalsIgnoreCase(failureClass)) {
                return step;
            }
        }
        return supportingStep;
    }

    private static Map<?, ?> boundaryStep(Map<String, Object> meta, String stage) {
        Object value = value(meta, "mla.breadcrumb.step." + stage);
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String boundaryFailure(Map<String, Object> meta, String stage) {
        Map<?, ?> step = boundaryStep(meta, stage);
        if (step == null) {
            return null;
        }
        return firstNonBlank(
                boundaryField(step, "failureClass"),
                boundaryField(step, "reasonCode"));
    }

    private static String firstBoundaryFailure(Map<String, Object> meta) {
        Map<?, ?> step = primaryBoundaryStep(meta);
        return step == null ? null : firstNonBlank(
                boundaryField(step, "failureClass"),
                boundaryField(step, "reasonCode"));
    }

    private static String boundaryStatus(Map<String, Object> meta, String stage, String fallback) {
        String failure = boundaryFailure(meta, stage);
        if (failure == null || "ok".equalsIgnoreCase(failure)) {
            return fallback;
        }
        return "warn";
    }

    private static String boundaryField(Map<?, ?> step, String key) {
        if (step == null || key == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(step.get(key), null);
    }

    private static String anchorStatus(Map<String, Object> meta, boolean complete) {
        if (truthy(value(meta, "overdrive.activated"))
                || truthy(value(meta, "overdrive.triggered"))
                || truthy(value(meta, "rag.anchor.enabled"))
                || numberAsInt(value(meta, "overdrive.stagesApplied"), 0) > 0
                || numberAsInt(value(meta, "overdrive.anchor.narrowed.k"), 0) > 0
                || numberAsInt(value(meta, "rag.anchor.acceptedCandidateCount"), 0) > 0) {
            return "done";
        }
        if (firstNonBlank(
                safeString(value(meta, "overdrive.anchor.error")),
                safeString(value(meta, "overdrive.bypassReason")),
                safeString(value(meta, "overdrive.blackbox.disabledReason"))) != null) {
            return "warn";
        }
        return complete ? "skipped" : "queued";
    }

    private static String anchorReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "rag.anchor.reason")),
                safeString(value(meta, "overdrive.anchor.narrowedReason")),
                safeString(value(meta, "overdrive.reason")),
                safeString(value(meta, "overdrive.skipReason")),
                safeString(value(meta, "overdrive.bypassReason")),
                countReason("candidates", value(meta, "overdrive.finalCandidateCount")),
                "anchor-pending");
    }

    private static boolean queryRewriteObserved(Map<String, Object> meta) {
        return truthy(value(meta, "queryTransformer.subQueries.superTokens.enabled"))
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.branchCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.tokenCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.subModelCount"), 0) > 0
                || numberAsInt(value(meta, "queryTransformer.subQueries.superTokens.branchTitleCount"), 0) > 0
                || truthy(value(meta, "queryTransformer.subQueries.fallback"))
                || truthy(value(meta, "queryTransformer.subQueries.refined"));
    }

    private static String queryRewriteStatus(Map<String, Object> meta, boolean complete) {
        if (queryRewriteObserved(meta)) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String queryRewriteReason(Map<String, Object> meta) {
        String lanes = queryRewriteLaneReason(meta);
        String temperatures = queryRewriteTemperatureReason(meta);
        String profile = queryRewriteProfileReason(meta);
        String counts = firstNonBlank(lanes, temperatures, profile) == null
                ? joinNonBlank("_",
                countReason("models", value(meta, "queryTransformer.subQueries.superTokens.subModelCount")),
                countReason("asgn", value(meta, "queryTransformer.subQueries.superTokens.subModelAssignmentCount")),
                countReason("titles", value(meta, "queryTransformer.subQueries.superTokens.branchTitleCount")),
                countReason("title-counts", firstNonNull(
                        value(meta, "queryTransformer.subQueries.superTokens.branchTitleHashCount"),
                        branchTitleHashCount(value(meta, "queryTransformer.subQueries.superTokens.branchTitleHashes")))),
                countReason("supers", value(meta, "queryTransformer.subQueries.superTokens.tokenCount")),
                countReason("branches", value(meta, "queryTransformer.subQueries.superTokens.branchCount")),
                countReason("axes", firstNonNull(
                        value(meta, "queryTransformer.subQueries.superTokens.axisCount"),
                        branchAxisCount(value(meta, "queryTransformer.subQueries.superTokens.axes")))),
                countReason("padded", value(meta, "queryTransformer.subQueries.refined.paddedCount")))
                : joinNonBlank("_",
                countReason("models", value(meta, "queryTransformer.subQueries.superTokens.subModelCount")),
                countReason("supers", value(meta, "queryTransformer.subQueries.superTokens.tokenCount")),
                countReason("branches", value(meta, "queryTransformer.subQueries.superTokens.branchCount")),
                countReason("axes", firstNonNull(
                        value(meta, "queryTransformer.subQueries.superTokens.axisCount"),
                        branchAxisCount(value(meta, "queryTransformer.subQueries.superTokens.axes")))),
                lanes,
                temperatures,
                profile);
        return firstNonBlank(
                counts,
                truthy(value(meta, "queryTransformer.subQueries.superTokens.titlePresent")) ? "title-present" : null,
                safeString(value(meta, "queryTransformer.subQueries.superTokens.reason")),
                safeString(value(meta, "queryTransformer.subQueries.fallback.reason")),
                safeString(value(meta, "queryTransformer.subQueries.refined.reason")),
                "rewrite-observed");
    }

    private static String queryRewriteLaneReason(Map<String, Object> meta) {
        int verification = numberAsInt(firstNonNull(
                value(meta, "web.query.rewrite.verificationLaneCount"),
                value(meta, "web.naver.adaptive.verificationLaneCount"),
                value(meta, "web.brave.adaptive.verificationLaneCount"),
                value(meta, "web.query.rewrite.requestedVerificationLaneCount")), -1);
        int exploration = numberAsInt(firstNonNull(
                value(meta, "web.query.rewrite.explorationLaneCount"),
                value(meta, "web.naver.adaptive.explorationLaneCount"),
                value(meta, "web.brave.adaptive.explorationLaneCount"),
                value(meta, "web.query.rewrite.requestedExplorationLaneCount")), -1);
        if (verification < 0 || exploration < 0) {
            return null;
        }
        return "lanes:" + verification + "x" + exploration;
    }

    private static String queryRewriteTemperatureReason(Map<String, Object> meta) {
        Double validation = asDouble(firstNonNull(
                value(meta, "web.query.rewrite.validationTemperature"),
                value(meta, "web.naver.adaptive.validationTemperature"),
                value(meta, "web.brave.adaptive.validationTemperature"),
                value(meta, "web.query.rewrite.requestedValidationTemperature")), null);
        Double exploration = asDouble(firstNonNull(
                value(meta, "web.query.rewrite.explorationTemperature"),
                value(meta, "web.naver.adaptive.explorationTemperature"),
                value(meta, "web.brave.adaptive.explorationTemperature"),
                value(meta, "web.query.rewrite.requestedExplorationTemperature")), null);
        if (validation == null || exploration == null) {
            return null;
        }
        return "temp:" + Double.toString(validation) + "x" + Double.toString(exploration);
    }

    private static String queryRewriteProfileReason(Map<String, Object> meta) {
        String profile = SafeRedactor.traceLabelOrFallback(firstNonBlank(
                safeString(value(meta, "web.query.rewrite.temperatureProfile")),
                safeString(value(meta, "web.naver.adaptive.temperatureProfile")),
                safeString(value(meta, "web.brave.adaptive.temperatureProfile")),
                safeString(value(meta, "web.query.rewrite.requestedTemperatureProfile"))), null);
        return profile == null ? null : "profile:" + profile;
    }

    private static Integer branchAxisCount(Object value) {
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                String axis = safeString(item);
                if ("definition".equals(axis) || "alias".equals(axis) || "relation".equals(axis)) {
                    count++;
                }
            }
            return count > 0 ? count : null;
        }
        String axis = safeString(value);
        return ("definition".equals(axis) || "alias".equals(axis) || "relation".equals(axis)) ? 1 : null;
    }

    private static Integer branchTitleHashCount(Object value) {
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                if (isHash12(item)) {
                    count++;
                }
            }
            return count > 0 ? count : null;
        }
        return isHash12(value) ? 1 : null;
    }

    private static boolean isHash12(Object value) {
        if (value == null) {
            return false;
        }
        return String.valueOf(value).trim().matches("[a-f0-9]{12}");
    }

    private static String rerankStatus(Map<String, Object> meta, boolean complete) {
        if (truthy(value(meta, "hypernova.dppApplied"))
                || truthy(value(meta, "cihRag.dppApplied"))
                || truthy(value(meta, "rerank.onnx.orchestrator.executed"))
                || numberAsInt(value(meta, "dpp.rerank.outputCount"), 0) > 0
                || numberAsInt(value(meta, "selfask.branchQuality.dpp.outputCount"), 0) > 0) {
            return "done";
        }
        if (truthy(value(meta, "rag.orchestrator.suppressed.dpp"))
                || truthy(value(meta, "dpp.rerank.skipped"))
                || truthy(value(meta, "selfask.branchQuality.dpp.failSoft"))
                || firstNonBlank(
                safeString(value(meta, "hypernova.dppDisabledReason")),
                safeString(value(meta, "cihRag.dppDisabledReason")),
                safeString(value(meta, "rerank.onnx.orchestrator.failureClass"))) != null) {
            return "warn";
        }
        return complete ? "skipped" : "queued";
    }

    private static String rerankReason(Map<String, Object> meta) {
        return firstNonBlank(
                countReason("selected", value(meta, "dpp.rerank.outputCount")),
                countReason("selected", value(meta, "hypernova.dppOutputCount")),
                countReason("selected", value(meta, "selfask.branchQuality.dpp.outputCount")),
                countReason("selected", value(meta, "rerank.onnx.orchestrator.selectedCount")),
                safeString(value(meta, "hypernova.dppDisabledReason")),
                safeString(value(meta, "cihRag.dppDisabledReason")),
                safeString(value(meta, "dpp.rerank.skipReason")),
                safeString(value(meta, "rerank.onnx.orchestrator.failureClass")),
                "rerank-pending");
    }

    private static String cfvmStatus(Map<String, Object> meta, boolean complete) {
        if (firstNonBlank(
                safeString(value(meta, "cfvm.failureRecorder")),
                safeString(value(meta, "cfvm.record.failureClass")),
                safeString(value(meta, "cfvm.record.skipReason")),
                safeString(value(meta, "cfvm.retrievalOrderDisabledReason"))) != null
                || truthy(value(meta, "cfvm.triggered"))
                || truthy(value(meta, "cfvm.record.bufferFailed"))
                || truthy(value(meta, "cfvm.record.memoryFailed"))) {
            return "warn";
        }
        if (numberAsInt(value(meta, "cfvm.boltzmannTemp"), -1) >= 0
                || numberAsInt(value(meta, "cfvm.rawTile.slotCount"), 0) > 0
                || truthy(value(meta, "cfvm.rawTile.enabled"))
                || truthy(value(meta, "cfvm.snapshot.saved"))) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String cfvmReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "cfvm.failureRecorder")),
                safeString(value(meta, "cfvm.record.failureClass")),
                safeString(value(meta, "cfvm.record.skipReason")),
                safeString(value(meta, "cfvm.retrievalOrderDisabledReason")),
                safeString(value(meta, "cfvm.tempSource")),
                countReason("tile", value(meta, "cfvm.activeTile")),
                "cfvm-pending");
    }

    private static String supabaseStatus(Map<String, Object> meta, boolean complete) {
        if (firstNonBlank(
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason"))) != null) {
            return "warn";
        }
        if (truthy(value(meta, "supabase.readOnly"))
                || truthy(value(meta, "supabase.projectScoped"))
                || truthy(value(meta, "supabase.connected"))) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String supabaseReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason")),
                truthy(value(meta, "supabase.readOnly")) ? "read-only" : null,
                "evidence-needed");
    }

    private static String supabaseStatusLabel(Map<String, Object> meta) {
        String promptStatus = firstNonBlank(
                safeString(value(meta, "prompt.agentDebugEvidence.external.supabase.status")),
                safeString(value(meta, "debug.ai.agentDebugEvidence.external.supabase.status")));
        if (promptStatus != null) {
            return promptStatus;
        }
        if (firstNonBlank(
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason"))) != null) {
            return "WARN";
        }
        if (agentVisibleExternalEvidencePresent(meta)) {
            return "WARN";
        }
        if (truthy(value(meta, "supabase.readOnly"))
                || truthy(value(meta, "supabase.projectScoped"))
                || truthy(value(meta, "supabase.connected"))) {
            return "OK";
        }
        return null;
    }

    private static String supabaseEvidenceNeededLabel(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "prompt.agentDebugEvidence.external.supabase.evidenceNeeded")),
                safeString(value(meta, "debug.ai.agentDebugEvidence.external.supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.evidenceNeeded")),
                safeString(value(meta, "supabase.projectRefMissing")),
                safeString(value(meta, "supabase.disabledReason")),
                agentVisibleExternalEvidencePresent(meta) ? "supabase_project_scope_or_auth_unverified" : null);
    }

    private static String supabaseNextActionLabel(Map<String, Object> meta) {
        String promptNext = firstNonBlank(
                safeString(value(meta, "prompt.agentDebugEvidence.external.supabase.nextAction")),
                safeString(value(meta, "debug.ai.agentDebugEvidence.external.supabase.nextAction")));
        if (promptNext != null) {
            return externalProofActionLabel(promptNext, "supabase");
        }
        String evidence = supabaseEvidenceNeededLabel(meta);
        if (evidence != null) {
            return agentVisibleExternalEvidencePresent(meta) && !hasAnyPrefix(meta, "supabase.")
                    ? "authenticate_supabase_mcp_or_cli"
                    : "collect_supabase_readonly_evidence";
        }
        if (truthy(value(meta, "supabase.readOnly"))
                || truthy(value(meta, "supabase.projectScoped"))
                || truthy(value(meta, "supabase.connected"))) {
            return "continue_supabase_readonly_monitor";
        }
        return null;
    }

    private static String externalProofActionLabel(Object raw, String lane) {
        String action = safeString(raw);
        if (action == null || action.isBlank()) {
            return action;
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if ("browser".equals(lane)
                && (normalized.equals("run_browser_local_ui_smoke")
                || normalized.equals("rerun_browser_local_ui_smoke")
                || normalized.equals("start_local_server_then_rerun_browser_local_ui_smoke"))) {
            return "refresh_local_interaction_smokes.ps1:browser";
        }
        if ("computer".equals(lane)
                && (normalized.equals("run_computer_use_lightweight_smoke")
                || normalized.equals("rerun_computer_use_lightweight_smoke"))) {
            return "refresh_local_interaction_smokes.ps1:computer";
        }
        if ("supabase".equals(lane)
                && (normalized.equals("inspect_supabase_shadow_snapshot")
                || normalized.equals("collect_supabase_shadow_snapshot"))) {
            return "supabase_context_probe:evidence_needed";
        }
        return action;
    }

    private static String agentDbContextLabel(Map<String, Object> meta, String field) {
        String label = firstNonBlank(
                safeString(value(meta, "prompt.agentDebugEvidence.agentDbContext." + field)),
                safeString(value(meta, "debug.ai.agentDebugEvidence.agentDbContext." + field)),
                safeString(value(meta, "agent.dbContext.agentVisible." + field)));
        if (label != null || !agentVisibleEvidencePresent(meta)) {
            return label;
        }
        return switch (field) {
            case "status" -> "DISABLED";
            case "reason" -> "agent_db_context_disabled";
            case "nextAction" -> "enable_agent_db_context_for_full_pipeline_health";
            default -> null;
        };
    }

    private static String virtualMatrixLabel(Map<String, Object> meta, String field) {
        String label = firstNonBlank(
                safeString(value(meta, "debug.ai.metrics.virtualMatrix." + field)),
                safeString(value(meta, "prompt.agentDebugEvidence.virtualMatrix." + field)));
        if (label != null || !agentVisibleEvidencePresent(meta)) {
            return label;
        }
        return switch (field) {
            case "count" -> "300";
            case "chunkCount" -> "30";
            case "weightedScore" -> "0.0";
            case "decision" -> "unavailable";
            case "hotChunkIndex" -> "-1";
            case "hotChunkRiskScore" -> "0.0";
            default -> null;
        };
    }

    private static String traceMemoryLabel(Map<String, Object> meta, String field, String rawTraceKey) {
        return firstNonBlank(
                safeString(value(meta, "prompt.agentDebugEvidence.traceMemory." + field)),
                safeString(value(meta, "debug.ai.agentDebugEvidence.traceMemory." + field)),
                safeString(value(meta, rawTraceKey)));
    }

    private static boolean agentVisibleEvidencePresent(Map<String, Object> meta) {
        return hasAnyPrefix(meta, "prompt.agentDebugEvidence.", "debug.ai.agentDebugEvidence.",
                "chat.harmony.");
    }

    private static boolean agentVisibleExternalEvidencePresent(Map<String, Object> meta) {
        return hasAnyPrefix(meta, "prompt.agentDebugEvidence.external.", "debug.ai.agentDebugEvidence.external.");
    }

    private static boolean harmonyObserved(Map<String, Object> meta) {
        return hasAnyPrefix(meta, "chat.harmony.", "harmony.score.", "rag.answerQuality.",
                "debug.ai.metrics.virtualMatrix.", "prompt.agentDebugEvidence.virtualMatrix.");
    }

    private static String harmonyStatus(Map<String, Object> meta, boolean complete) {
        String decision = safeString(value(meta, "chat.harmony.postprocess.decision"));
        String qualityDecision = safeString(value(meta, "rag.answerQuality.decision"));
        String matrixDecision = virtualMatrixLabel(meta, "decision");
        double score = firstNonNull(
                asDouble(value(meta, "chat.harmony.postprocess.weightedScore"), null),
                asDouble(value(meta, "harmony.score.lastComputed"), null),
                asDouble(value(meta, "rag.answerQuality.faithfulnessScore"), null),
                asDouble(value(meta, "debug.ai.metrics.virtualMatrix.weightedScore"), null),
                asDouble(value(meta, "prompt.agentDebugEvidence.virtualMatrix.weightedScore"), null),
                -1.0d);
        if (truthy(value(meta, "chat.harmony.postprocess.degraded"))
                || "blank_guard".equalsIgnoreCase(decision)
                || "evidence_limited".equalsIgnoreCase(decision)
                || "REPAIR_WITH_WEB".equalsIgnoreCase(qualityDecision)
                || "investigate_hot_chunk".equalsIgnoreCase(matrixDecision)
                || (score >= 0.0d && score < 0.45d)) {
            return "warn";
        }
        if (truthy(value(meta, "chat.harmony.postprocess.applied"))
                || score >= 0.0d
                || qualityDecision != null
                || matrixDecision != null) {
            return "done";
        }
        return complete ? "skipped" : "queued";
    }

    private static String harmonyReason(Map<String, Object> meta) {
        return firstNonBlank(
                safeString(value(meta, "chat.harmony.postprocess.reason")),
                safeString(value(meta, "chat.harmony.postprocess.decision")),
                safeString(value(meta, "rag.answerQuality.reason")),
                safeString(value(meta, "rag.answerQuality.decision")),
                virtualMatrixLabel(meta, "decision"),
                countReason("evidence", value(meta, "chat.harmony.postprocess.evidenceCount")),
                "harmony-pending");
    }

    private static boolean chatHarmonyInputPresent(Map<String, Object> meta) {
        return hasAnyPrefix(meta, "chat.harmony.", "harmony.score.", "rag.answerQuality.")
                || truthy(value(meta, "chat.harmony.postprocess.agentVisible"));
    }

    private static String debugAiNextAction(Map<String, Object> meta) {
        String postprocessed = firstNonBlank(
                safeString(value(meta, "debug.ai.metrics.nextAction")),
                safeString(value(meta, "prompt.agentDebugEvidence.chatHarmony.nextAction")));
        if (postprocessed != null) {
            return postprocessed;
        }
        if (chatHarmonyNeedsInspection(meta)) {
            return "inspect_chat_harmony_trace";
        }
        String matrixDecision = traceLabel(virtualMatrixLabel(meta, "decision"), null);
        if (matrixDecision != null || chatHarmonyInputPresent(meta)) {
            return matrixActionFromDecision(matrixDecision);
        }
        return null;
    }

    private static void ensureSmoothChatDebugAiNextAction(
            LinkedHashMap<String, String> labels,
            Map<String, Object> meta) {
        if (labels == null || labels.containsKey("debugAiNextAction")) {
            return;
        }
        String decision = firstNonBlank(
                labels.get("chatHarmonyDecision"),
                safeString(value(meta, "chat.harmony.postprocess.decision")));
        String degraded = firstNonBlank(
                labels.get("chatHarmonyDegraded"),
                safeString(value(meta, "chat.harmony.postprocess.degraded")));
        if ("smooth_chat".equalsIgnoreCase(decision) && !"true".equalsIgnoreCase(degraded)) {
            labels.put("debugAiNextAction", "continue_observing_chat_harmony");
        }
    }

    private static String debugAiNextReason(Map<String, Object> meta) {
        String postprocessed = firstNonBlank(
                safeString(value(meta, "debug.ai.metrics.nextReason")),
                safeString(value(meta, "prompt.agentDebugEvidence.chatHarmony.nextReason")));
        if (postprocessed != null) {
            return postprocessed;
        }
        if (chatHarmonyNeedsInspection(meta)) {
            return chatHarmonyFailureClass(meta);
        }
        String matrixDecision = traceLabel(virtualMatrixLabel(meta, "decision"), null);
        if (matrixDecision != null || chatHarmonyInputPresent(meta)) {
            return traceLabel(matrixDecision, "observe");
        }
        return null;
    }

    private static boolean chatHarmonyNeedsInspection(Map<String, Object> meta) {
        if (!chatHarmonyInputPresent(meta)) {
            return false;
        }
        if (truthy(value(meta, "chat.harmony.postprocess.degraded"))) {
            return true;
        }
        String decision = traceLabel(value(meta, "chat.harmony.postprocess.decision"), null);
        return decision != null && !"smooth_chat".equalsIgnoreCase(decision);
    }

    private static String chatHarmonyFailureClass(Map<String, Object> meta) {
        String suffix = firstNonBlank(
                traceLabel(value(meta, "chat.harmony.postprocess.reason"), null),
                traceLabel(value(meta, "chat.harmony.postprocess.decision"), null),
                "observed");
        return suffix.startsWith("chat_harmony.") ? suffix : "chat_harmony." + suffix;
    }

    private static String matrixActionFromDecision(String matrixDecision) {
        String decision = traceLabel(matrixDecision, "observe");
        if ("mitigate_now".equals(decision)) {
            return "mitigate_debug_ai_hot_chunk";
        }
        if ("investigate_hot_chunk".equals(decision)) {
            return "investigate_debug_ai_hot_chunk";
        }
        return "continue_observing_chat_harmony";
    }

    static List<Map<String, Object>> summarizeRequestAttempts(List<Map<String, Object>> ledgerRows) {
        if (ledgerRows == null || ledgerRows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summary = new ArrayList<>(
                Math.min(REQUEST_ATTEMPT_SUMMARY_CAPACITY, ledgerRows.size()));
        for (Map<String, Object> source : ledgerRows) {
            if (summary.size() >= REQUEST_ATTEMPT_SUMMARY_CAPACITY) {
                break;
            }
            if (source == null) {
                continue;
            }
            String lane = attemptLane(source.get("role"));
            String outcome = attemptOutcome(source.get("outcome"));
            String terminal = attemptTerminal(source.get("terminalClass"));
            Long elapsed = asLong(source.get("elapsedMs"));
            if (lane == null || outcome == null || terminal == null || elapsed == null
                    || !hasAttemptObservationFields(source)) {
                continue;
            }
            boolean adapterAttempt = observed(source, "modelAdapterAttemptObserved");
            boolean clientExchange = observed(source, "clientHttpExchangeObserved");
            boolean clientResponse = observed(source, "clientHttpResponseObserved");
            boolean providerAttempt = observed(source, "providerAttemptObserved");
            boolean wireAttempt = observed(source, "wireAttemptObserved");
            boolean responseObserved = observed(source, "responseObserved");
            boolean attemptObserved = adapterAttempt || clientExchange || providerAttempt || wireAttempt;

            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("sequence", Math.min(1_024, Math.max(1,
                    numberAsInt(source.get("sequence"), summary.size() + 1))));
            row.put("lane", lane);
            row.put("outcome", outcome);
            row.put("failureClass", attemptFailureClass(source.get("failureClass")));
            row.put("terminal", terminal);
            row.put("modelAdapterAttemptObserved", adapterAttempt);
            row.put("clientHttpExchangeObserved", clientExchange);
            row.put("clientHttpResponseObserved", clientResponse);
            row.put("providerAttemptObserved", providerAttempt);
            row.put("attemptObserved", attemptObserved);
            row.put("wireAttemptObserved", wireAttempt);
            row.put("deliveryObserved", responseObserved);
            row.put("trusted", true);
            row.put("observedAtElapsedMs", Math.min(86_400_000L, Math.max(0L, elapsed)));
            summary.add(Map.copyOf(row));
        }
        return List.copyOf(summary);
    }

    private static String modelStatus(Map<String, Object> meta, boolean streamError, boolean complete, Long tookMs) {
        if (safeString(value(meta, "llm.final.skipped")) != null) {
            return "skipped";
        }
        if (streamError) {
            return "warn";
        }
        ModelAttemptState attempt = modelAttemptState(meta);
        if (attempt.successful()) {
            return slowModel(tookMs) ? "warn" : "done";
        }
        if (llmRouteHealthDegraded(meta)
                || truthy(value(meta, "llm.client.blank"))
                || truthy(value(meta, "llm.client.failed"))
                || truthy(value(meta, "llm.call.blank"))
                || truthy(value(meta, "llm.output.blank"))
                || truthy(value(meta, "llm.fastBailTimeout"))
                || truthy(value(meta, "llm.endpoint.compat.mismatch"))
                || truthy(value(meta, "llm.modelGuard.triggered"))
                || truthy(value(meta, "llm.model.policy.blocked"))
                || firstNonBlank(
                safeString(value(meta, "llm.error.code")),
                safeString(value(meta, "llm.modelGuard.failReason")),
                safeString(value(meta, "llm.endpoint.compat.hint")),
                safeString(value(meta, "llm.endpoint.compat.detail"))) != null) {
            return "warn";
        }
        if (attempt.failed() || attempt.cancelled()) {
            return "warn";
        }
        return complete ? "warn" : "running";
    }

    private static String modelReason(
            Map<String, Object> meta,
            boolean streamError,
            boolean streamCancelled,
            boolean complete,
            Long tookMs) {
        String skipped = safeString(value(meta, "llm.final.skipped"));
        if (skipped != null) {
            return skipped;
        }
        if (streamError) {
            return streamCancelled ? "model_stream_cancelled" : "model_stream_failed";
        }
        ModelAttemptState attempt = modelAttemptState(meta);
        if (attempt.successful()) {
            return slowModel(tookMs) ? countReason("slow-model-ms", tookMs) : attempt.successReason();
        }
        String explicitFailure = firstNonBlank(
                llmRouteHealthReason(meta),
                truthy(value(meta, "llm.client.blank")) ? "client-blank" : null,
                truthy(value(meta, "llm.client.failed"))
                        ? firstNonBlank(safeString(value(meta, "llm.client.errorType")), "client-failed")
                        : null,
                truthy(value(meta, "llm.call.blank")) || truthy(value(meta, "llm.output.blank")) ? "blank-response" : null,
                truthy(value(meta, "llm.fastBailTimeout"))
                        ? countReason("timeout-fast-bail", value(meta, "llm.fastBailTimeout.timeoutHits"))
                        : null,
                truthy(value(meta, "llm.defaultModel.waitStatus"))
                        ? firstNonBlank(safeString(value(meta, "llm.defaultModel.waitStatus.code")), "waiting-for-default-model")
                        : null,
                safeString(value(meta, "llm.modelGuard.failReason")),
                safeString(value(meta, "llm.endpoint.compat.hint")),
                safeString(value(meta, "llm.endpoint.compat.detail")),
                safeString(value(meta, "llm.error.code")),
                safeString(value(meta, "llm.model.policy.blocked.reason")));
        if (explicitFailure != null) {
            return explicitFailure;
        }
        if (attempt.cancelled()) {
            return "model_attempt_cancelled";
        }
        if (attempt.failed()) {
            return "model_attempt_failed";
        }
        if (complete) {
            return attempt.attemptObserved()
                    ? "model_delivery_not_observed"
                    : "model_attempt_not_observed";
        }
        return firstNonBlank(
                safeString(value(meta, "llm.endpoint.compat.healedBy")),
                safeString(value(meta, "llm.model")),
                "model-pending");
    }

    private static ModelAttemptState modelAttemptState(Map<String, Object> meta) {
        Object raw = value(meta, REQUEST_ATTEMPT_SUMMARY_KEY);
        if (!(raw instanceof Collection<?> rows) || rows.isEmpty()) {
            return ModelAttemptState.NONE;
        }
        ModelAttemptObservation primary = null;
        ModelAttemptObservation fallback = null;
        int inspected = 0;
        for (Object candidate : rows) {
            if (inspected++ >= REQUEST_ATTEMPT_SUMMARY_CAPACITY) {
                break;
            }
            if (!(candidate instanceof Map<?, ?> row) || !Boolean.TRUE.equals(row.get("trusted"))) {
                continue;
            }
            String lane = attemptLane(firstNonNull(row.get("lane"), row.get("role")));
            String outcome = attemptOutcome(row.get("outcome"));
            String terminal = attemptTerminal(firstNonNull(row.get("terminal"), row.get("terminalClass")));
            boolean observedAttempt = Boolean.TRUE.equals(row.get("attemptObserved"));
            boolean observedDelivery = Boolean.TRUE.equals(row.get("deliveryObserved"));
            if (lane == null || outcome == null || terminal == null || !observedAttempt) {
                continue;
            }
            boolean rowCancelled = "cancelled".equals(outcome) || "cancelled".equals(terminal);
            boolean rowSucceeded = "success".equals(outcome)
                    && "success".equals(terminal)
                    && observedDelivery;
            boolean rowFailed = !rowCancelled && ("failed".equals(outcome) || attemptFailureTerminal(terminal));
            ModelAttemptObservation observation = new ModelAttemptObservation(
                    Math.max(1, numberAsInt(row.get("sequence"), inspected)),
                    rowSucceeded,
                    rowFailed,
                    rowCancelled);
            if ("primary".equals(lane)) {
                if (primary == null || observation.sequence() >= primary.sequence()) {
                    primary = observation;
                }
            } else if ("fallback".equals(lane)) {
                if (fallback == null || observation.sequence() >= fallback.sequence()) {
                    fallback = observation;
                }
            }
        }
        return new ModelAttemptState(primary, fallback);
    }

    private static boolean observed(Map<String, Object> source, String key) {
        return Boolean.TRUE.equals(source.get(key));
    }

    private static boolean hasAttemptObservationFields(Map<String, Object> source) {
        return source.get("modelAdapterAttemptObserved") instanceof Boolean
                && source.get("clientHttpExchangeObserved") instanceof Boolean
                && source.get("clientHttpResponseObserved") instanceof Boolean
                && source.get("providerAttemptObserved") instanceof Boolean
                && source.get("wireAttemptObserved") instanceof Boolean
                && source.get("responseObserved") instanceof Boolean;
    }

    private static String attemptLane(Object value) {
        String canonical = canonicalAttemptValue(value);
        return "primary".equals(canonical) || "fallback".equals(canonical) ? canonical : null;
    }

    private static String attemptOutcome(Object value) {
        String canonical = canonicalAttemptValue(value);
        return switch (canonical) {
            case "success", "failed", "cancelled" -> canonical;
            default -> null;
        };
    }

    private static String attemptTerminal(Object value) {
        String canonical = canonicalAttemptValue(value);
        return switch (canonical) {
            case "none", "success", "cancelled", "timeout", "error", "request_budget_exhausted",
                    "configuration_error", "upstream_5xx", "blank_response", "model_unavailable" -> canonical;
            default -> null;
        };
    }

    private static String attemptFailureClass(Object value) {
        String canonical = canonicalAttemptValue(value);
        return switch (canonical) {
            case "none", "auth_missing", "health_down", "model_missing", "vram_oom", "timeout_soft",
                    "soft_circuit_open", "rate_limit_cooldown", "cancelled_neutral", "provider_error",
                    "context_too_small", "embedding_dim_mismatch", "local_unsupported_managed_rag",
                    "stream_error", "response_model_unverified", "disabled", "unknown" -> canonical;
            default -> "unknown";
        };
    }

    private static boolean attemptFailureTerminal(String terminal) {
        return terminal != null && !"none".equals(terminal) && !"success".equals(terminal)
                && !"cancelled".equals(terminal);
    }

    private static String canonicalAttemptValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private record ModelAttemptObservation(
            int sequence,
            boolean successful,
            boolean failed,
            boolean cancelled) {
    }

    private record ModelAttemptState(
            ModelAttemptObservation primary,
            ModelAttemptObservation fallback) {
        private static final ModelAttemptState NONE = new ModelAttemptState(null, null);

        private ModelAttemptObservation effective() {
            if (primary == null) {
                return fallback;
            }
            if (fallback == null) {
                return primary;
            }
            return fallback.sequence() >= primary.sequence() ? fallback : primary;
        }

        private boolean successful() {
            ModelAttemptObservation effective = effective();
            return effective != null && effective.successful();
        }

        private boolean recovered() {
            return fallback != null
                    && fallback.successful()
                    && (primary == null || primary.failed());
        }

        private boolean attemptObserved() {
            return effective() != null;
        }

        private boolean failed() {
            ModelAttemptObservation effective = effective();
            return effective != null && effective.failed();
        }

        private boolean cancelled() {
            ModelAttemptObservation effective = effective();
            return effective != null && effective.cancelled();
        }

        private String successReason() {
            if (effective() == primary) {
                return "model_attempt_succeeded";
            }
            if (primary == null) {
                return "fallback_done_primary_not_observed";
            }
            if (primary.cancelled()) {
                return "primary_cancelled_fallback_done";
            }
            if (primary.failed()) {
                return "primary_failed_fallback_done";
            }
            if (!primary.successful()) {
                return "fallback_done_primary_delivery_not_observed";
            }
            return "fallback_done_primary_observed";
        }
    }

    private static boolean slowModel(Long tookMs) {
        return tookMs != null && tookMs >= SLOW_MODEL_TOOK_MS;
    }

    private static boolean llmRouteHealthDegraded(Map<String, Object> meta) {
        double pressure = asDouble(value(meta, "llm.gateway.route.healthFailurePressure"), 0.0d);
        String hint = safeString(value(meta, "llm.gateway.route.healthRoutingHint"));
        return pressure >= 0.50d || "llm_route_degrade".equalsIgnoreCase(hint);
    }

    private static String llmRouteHealthReason(Map<String, Object> meta) {
        if (!llmRouteHealthDegraded(meta)) {
            return null;
        }
        return firstNonBlank(
                safeString(value(meta, "llm.gateway.route.healthRoutingHint")),
                "llm-health-pressure");
    }

    private static String firstFailureTag(Map<String, Object> meta) {
        Object tags = value(meta, "failureTags");
        if (tags instanceof Collection<?> collection) {
            for (Object tag : collection) {
                String safeTag = safeFailureTag(tag);
                if (safeTag != null) {
                    return safeTag;
                }
            }
            return null;
        }
        return safeFailureTag(tags);
    }

    private static String safeFailureTag(Object tag) {
        if (tag == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(tag, null);
    }

    private static String debugCopilotReason(Map<String, Object> meta) {
        Object causes = value(meta, "dbg.copilot.causes");
        if (causes instanceof Collection<?> collection) {
            for (Object cause : collection) {
                String reason = debugCopilotCauseReason(cause);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }
        return debugCopilotCauseReason(causes);
    }

    private static String debugCopilotCauseReason(Object cause) {
        if (cause instanceof Map<?, ?> row) {
            return SafeRedactor.traceLabelOrFallback(firstNonBlank(
                    safeString(row.get("id")),
                    safeString(row.get("title"))), null);
        }
        return safeFailureTag(cause);
    }

    private static String traceLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static String countReason(String label, Object value) {
        int n = numberAsInt(value, -1);
        if (n < 0) {
            return null;
        }
        String safeLabel = SafeRedactor.traceLabelOrFallback(label, "count");
        return safeLabel + ":" + n;
    }

    private static String joinNonBlank(String delimiter, String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return String.join(delimiter == null ? " " : delimiter, out);
    }

    private static Object value(Map<String, Object> meta, String key) {
        return meta == null ? null : meta.get(key);
    }

    static ChatStreamEvent.PipelineSnapshot withTraceTurnId(
            ChatStreamEvent.PipelineSnapshot snapshot,
            String answerMode,
            Long traceTurnId) {
        if (snapshot == null) {
            return buildPipelineSnapshot(null, answerMode, traceTurnId, null);
        }
        return new ChatStreamEvent.PipelineSnapshot(
                snapshot.planId(),
                snapshot.route(),
                firstNonBlank(answerMode, snapshot.answerMode()),
                traceTurnId,
                snapshot.webCount(),
                snapshot.vectorCount(),
                snapshot.finalContextCount(),
                snapshot.citationCoverage(),
                snapshot.finalSigmoid(),
                snapshot.failureClass(),
                snapshot.disabledReason());
    }

    @SuppressWarnings("unchecked")
    static ChatStreamEvent.ScoreDeltaSignal buildScoreDeltaSignal(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Object obj = meta.get("ablation.scoreDelta.latest");
        if (!(obj instanceof Map<?, ?>)) {
            obj = meta.get("ablation.traceAnchor.top");
        }
        if (!(obj instanceof Map<?, ?> m)) {
            return null;
        }
        Map<Object, Object> row = (Map<Object, Object>) m;
        Double scoreDelta = doubleFromMap(row, "scoreDelta", "delta");
        Double dropRatio = doubleFromMap(row, "dropRatio");
        Double maxDrawdown = doubleFromMap(row, "maxDrawdown");
        if (maxDrawdown == null) {
            maxDrawdown = asDouble(meta.get("ablation.maxDrawdown"), null);
        }
        Double expectedDelta = doubleFromMap(row, "expectedDelta");
        if (expectedDelta == null) {
            expectedDelta = asDouble(meta.get("ablation.expectedDelta.max"), null);
        }
        Double rawScoreDelta = doubleFromMap(row, "rawScoreDelta", "delta");
        if (scoreDelta == null && dropRatio == null && expectedDelta == null && rawScoreDelta == null) {
            return null;
        }
        return new ChatStreamEvent.ScoreDeltaSignal(
                scoreDelta,
                dropRatio,
                maxDrawdown,
                expectedDelta,
                rawScoreDelta,
                safeString(row.get("clampName")),
                firstNonBlank(safeString(row.get("stage")), safeString(row.get("step"))),
                safeString(row.get("guard")),
                asLong(row.get("eventId")));
    }

    private static boolean isEmptyPipelineSnapshot(ChatStreamEvent.PipelineSnapshot snapshot) {
        return snapshot == null
                || (snapshot.planId() == null
                && snapshot.route() == null
                && snapshot.answerMode() == null
                && snapshot.traceTurnId() == null
                && snapshot.webCount() == null
                && snapshot.vectorCount() == null
                && snapshot.finalContextCount() == null
                && snapshot.citationCoverage() == null
                && snapshot.finalSigmoid() == null
                && snapshot.failureClass() == null
                && snapshot.disabledReason() == null);
    }

    private static ChatStreamEvent.TransformerBlockSignal block(
            String id,
            String label,
            String phase,
            String status,
            String reason,
            int order,
            Long tookMs) {
        return new ChatStreamEvent.TransformerBlockSignal(id, label, phase, status, reason, order, tookMs);
    }

    private static String streamStatus(String code) {
        if (code == null || code.isBlank()) {
            return "running";
        }
        String lower = code.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("complete") || lower.equals("final") || lower.equals("finalizing")) {
            return "done";
        }
        if (lower.equals("error") || lower.equals("cancelled") || lower.equals("timeout")) {
            return "warn";
        }
        return "running";
    }

    private static boolean countPositive(Integer value) {
        return value != null && value > 0;
    }

    private static Integer collectionSizeOrNull(Object value) {
        // An observed empty result is zero; only an absent/unsupported value is unknown.
        return value instanceof Collection<?> || value instanceof Map<?, ?> ? collectionSize(value) : null;
    }

    private static Integer countValue(Object value) {
        if (value == null) {
            return null;
        }
        return numberAsInt(value, 0);
    }

    private static String gatewayLabel(Object value) {
        return value instanceof String text ? SafeRedactor.traceLabelOrFallback(text, "unverified") : null;
    }

    private static String nonNegativeCountLabel(Object value) {
        Long count = asLong(value);
        if (count == null) {
            return null;
        }
        return String.valueOf(Math.max(0L, Math.min((long) Integer.MAX_VALUE, count)));
    }

    private static String combinedFallbackCountLabel(Map<String, Object> meta) {
        Long generated = asLong(meta.get("llm.gateway.fallback.count"));
        Long preselected = asLong(meta.get("llm.gateway.preselectionFallbackCount"));
        if (generated == null && preselected == null) return null;
        long first = generated == null ? 0 : Math.max(0L, Math.min((long) Integer.MAX_VALUE, generated));
        long second = preselected == null ? 0 : Math.max(0L, Math.min((long) Integer.MAX_VALUE, preselected));
        return nonNegativeCountLabel(first + second);
    }

    private static Map<String, Integer> toIntegerMap(Object value) {
        if (!(value instanceof Map<?, ?> input) || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), numberAsInt(entry.getValue(), 0));
            if (out.size() >= 24) {
                break;
            }
        }
        return out;
    }

    private static int collectionSize(Object value) {
        if (value instanceof Collection<?> c) {
            return c.size();
        }
        if (value instanceof Map<?, ?> m) {
            return m.size();
        }
        return 0;
    }

    private static int numberAsInt(Object value, int fallback) {
        Long n = asLong(value);
        if (n == null) {
            return fallback;
        }
        if (n > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (n < 0L) {
            return 0;
        }
        return n.intValue();
    }

    private static Double doubleFromMap(Map<Object, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && row.containsKey(key)) {
                Double v = asDouble(row.get(key), null);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Double asDouble(Object value, Double fallback) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed("signal.asDouble", ignore);
            return fallback;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            traceSuppressed("signal.asLong", ignore);
            return null;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("chat.stream.signal.suppressed.stage", safeStage);
        TraceStore.put("chat.stream.signal.suppressed.errorType", safeErrorType);
        TraceStore.put("chat.stream.signal.suppressed." + safeStage, true);
        TraceStore.put("chat.stream.signal.suppressed." + safeStage + ".errorType", safeErrorType);
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Chat stream signal numeric fallback stage={0} errorType={1}",
                    safeStage,
                    safeErrorType);
        }
    }

    private static String errorType(RuntimeException failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure.getClass().getSimpleName();
    }

    private static String hashIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String s = value.trim();
        if (s.startsWith("hash:")) {
            return s;
        }
        return SafeRedactor.hashValue(s);
    }

    private static String providerCancellationFailureClass(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        for (String provider : java.util.List.of("naver", "brave", "serpapi", "tavily")) {
            if (truthy(meta.get("web." + provider + ".cancelled"))
                    || "cancelled".equalsIgnoreCase(String.valueOf(meta.get("web." + provider + ".exceptionType")))) {
                return "cancelled";
            }
        }
        return null;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).replace('\n', ' ').replace('\r', ' ').trim();
        return s.isBlank() ? null : SafeRedactor.redact(s);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
