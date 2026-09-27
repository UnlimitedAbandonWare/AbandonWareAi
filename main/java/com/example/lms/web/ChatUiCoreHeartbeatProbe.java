package com.example.lms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.debug.ai.DebugAiRawTile;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.transform.QueryTransformer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ChatUiCoreHeartbeatProbe {
    private static final Logger log = LoggerFactory.getLogger(ChatUiCoreHeartbeatProbe.class);

    private static final String COMPUTER_USE_SMOKE_PATH = "var/codex-smoke/computer-use-smoke.json";
    private static final String BROWSER_UI_SMOKE_PATH = "var/codex-smoke/browser-ui-smoke.json";
    private static final String GOAL_NEXT_AUTO_SUMMARY_PATH = "var/codex-smoke/goal-next-auto/goal-next-auto.summary.json";
    private static final int DEFAULT_EXTERNAL_SMOKE_STALE_AFTER_MINUTES = 60;
    private static final Duration LOCAL_LLM_DEBUG_EVENT_OPERATOR_ACTION_TTL = Duration.ofMinutes(30);
    private static final String LATEST_HARMONY_TRACE_ROUTE = "/api/diagnostics/trace/snapshots/latest-harmony/html";
    private static final String LATEST_TRACE_MEMORY_ROUTE = "/api/diagnostics/trace/snapshots/latest-trace-memory/html";
    private static final String LATEST_TRACE_MEMORY_CHECKPOINTS_ROUTE =
            DebugAiMetricsService.TRACE_MEMORY_CHECKPOINTS_ROUTE;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChatUiCoreHeartbeatProbe() {
    }

    static Map<String, Object> snapshot(String reason,
                                        ObjectProvider<ApplicationContext> applicationContextProvider,
                                        ObjectProvider<PromptBuilder> promptBuilderProvider,
                                        ObjectProvider<QueryTransformer> queryTransformerProvider,
                                        ObjectProvider<HybridWebSearchProvider> hybridWebSearchProvider,
                                        ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
                                        ObjectProvider<DebugEventStore> debugEventStoreProvider,
                                        ObjectProvider<DebugAiMetricsService> debugAiMetricsServiceProvider,
                                        ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        return snapshot(reason,
                applicationContextProvider,
                promptBuilderProvider,
                queryTransformerProvider,
                hybridWebSearchProvider,
                nightmareBreakerProvider,
                debugEventStoreProvider,
                debugAiMetricsServiceProvider,
                traceSnapshotStoreProvider,
                null);
    }

    static Map<String, Object> snapshot(String reason,
                                        ObjectProvider<ApplicationContext> applicationContextProvider,
                                        ObjectProvider<PromptBuilder> promptBuilderProvider,
                                        ObjectProvider<QueryTransformer> queryTransformerProvider,
                                        ObjectProvider<HybridWebSearchProvider> hybridWebSearchProvider,
                                        ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
                                        ObjectProvider<DebugEventStore> debugEventStoreProvider,
                                        ObjectProvider<DebugAiMetricsService> debugAiMetricsServiceProvider,
                                        ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider,
                                        LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService) {
        boolean promptBuilder = beanPresent(promptBuilderProvider);
        boolean queryTransformer = beanPresent(queryTransformerProvider);
        boolean webSearch = beanPresent(hybridWebSearchProvider);
        boolean breaker = beanPresent(nightmareBreakerProvider);
        DebugEventStore debugEventStore = debugEventStore(debugEventStoreProvider);
        boolean debugEvents = debugEventStore != null;
        TraceSnapshotStore traceSnapshotStore = traceSnapshotStore(traceSnapshotStoreProvider);
        boolean traceSnapshots = traceSnapshotStore != null;
        List<Map<String, Object>> traceSnapshotSummaries = traceSnapshotSummaries(traceSnapshotStore, 20);
        boolean webFailSoftAspect = beanTypePresent(applicationContextProvider, "ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect");
        boolean emptyFallbackAspect = beanTypePresent(applicationContextProvider, "ai.abandonware.nova.orch.aop.HybridWebSearchEmptyFallbackAspect");
        boolean cancelShieldPostProcessor = beanTypePresent(applicationContextProvider, "ai.abandonware.nova.boot.exec.CancelShieldExecutorServicePostProcessor");
        boolean dppReranker = beanTypePresent(applicationContextProvider, "com.example.lms.service.rag.rerank.DppDiversityReranker");
        boolean cfvmFailureRecorder = beanTypePresent(applicationContextProvider, "com.example.lms.cfvm.CfvmFailureRecorder");
        boolean cfvmRawMatrixBuffer = beanTypePresent(applicationContextProvider, "com.example.lms.cfvm.RawMatrixBuffer");
        int supplementalSearchProviderCount = beanTypeCount(applicationContextProvider,
                "com.acme.aicore.domain.ports.WebSearchProvider");
        String coreStatus = promptBuilder && queryTransformer && webSearch ? "OK" : "WARN";
        String coreReason = "OK".equals(coreStatus) ? "core_beans_present" : "core_beans_missing";
        String heartbeatReason = heartbeatRollupReason(coreStatus, reason);
        boolean desktopOnlyReady = "supporting_external_evidence_missing".equals(heartbeatReason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capturedAt", LocalDateTime.now().toString());
        out.put("status", desktopOnlyReady ? "OK" : "WARN");
        out.put("reason", heartbeatReason);
        out.put("nextAction", heartbeatRollupNextAction(heartbeatReason));
        out.put("decision", desktopOnlyReady ? "desktop_only_ready" : "evidence_needed");
        out.put("externalEvidenceMode", "optional");
        // 선택 레인은 core 상태에서 분리해 표기한다: Agent DB 컨텍스트 빈이 없으면
        // 레인만 WARN이고 core heartbeat의 OK/WARN 판정에는 영향을 주지 않는다.
        out.put("optionalLanes", List.of(Map.of(
                "lane", "agentDbContext",
                "status", "WARN",
                "reason", "agent_db_context_disabled".equals(reason)
                        ? "agent_db_context_disabled" : "agent_db_context_unavailable",
                "nextAction", "enable_agent_db_context_for_full_pipeline_health")));
        out.put("requireProducerBundles", false);
        out.put("supabaseLiveProofRequired", false);
        out.put("memoryGate", Map.of(
                "status", "DISABLED",
                "reason", reason,
                "active", 0,
                "pending", 0,
                "quarantined", 0,
                "stale", 0,
                "total", 0));
        out.put("debugOverview", List.of(
                overview("heartbeat", "WARN", heartbeatReason, "direct_core_heartbeat"),
                overview("coreRuntime", coreStatus, coreReason,
                        "promptBuilder=" + present(promptBuilder)
                                + " queryTransformer=" + present(queryTransformer)
                                + " search=" + present(webSearch)
                                + " dpp=" + present(dppReranker)
                                + " cfvm=" + present(cfvmFailureRecorder && cfvmRawMatrixBuffer)
                                + " breaker=" + present(breaker)
                                + " debug=" + present(debugEvents)
                                + " trace=" + present(traceSnapshots)),
                overview("uiDebug", "OK", "heartbeat_json_available", "admin_pipeline_not_exposed"),
                overview("externalEvidence", "WARN", "external_evidence_needed",
                        "supabase=read_only computer=gui_support browser=local_ui_proof"),
                overview("webProviders", webSearch ? "OK" : "WARN",
                        webSearch ? "hybrid_provider_present" : "hybrid_provider_missing",
                        "hybrid=" + present(webSearch)
                                + " supplemental=" + supplementalSearchProviderCount)));
        out.put("lanes", List.of(
                lane("promptBuilder", promptBuilder, "prompt_builder_missing"),
                lane("queryTransformer", queryTransformer, "query_transformer_missing"),
                lane("webSearch", webSearch, "hybrid_web_search_provider_missing"),
                lane("circuitBreaker", breaker, "nightmare_breaker_missing"),
                lane("debugEventStore", debugEvents, "debug_event_store_missing"),
                lane("traceSnapshotStore", traceSnapshots, "trace_snapshot_store_missing"),
                lane("webFailSoftAspect", webFailSoftAspect, "web_failsoft_aspect_missing"),
                lane("hybridEmptyFallbackAspect", emptyFallbackAspect, "hybrid_empty_fallback_aspect_missing"),
                lane("dppDiversityReranker", dppReranker, "dpp_diversity_reranker_missing"),
                lane("cfvmFailureRecorder", cfvmFailureRecorder, "cfvm_failure_recorder_missing"),
                lane("cfvmRawMatrixBuffer", cfvmRawMatrixBuffer, "cfvm_raw_matrix_buffer_missing"),
                lane("cancelShieldPostProcessor", cancelShieldPostProcessor, "cancel_shield_post_processor_missing")));
        out.put("debugEventHealth", Map.of(
                "status", debugEvents ? "OK" : "WARN",
                "reason", debugEvents ? "available" : "debug_event_store_missing",
                "available", debugEvents,
                "recentEventCount", 0,
                "fingerprintCount", 0));
        out.put("debugAiMetrics", debugAiMetrics(debugAiMetricsServiceProvider, debugEvents));
        out.put("modelRuntime", modelRuntime(localLlmSmokeHistoryDiagnosticsService, debugEventStore));
        out.put("traceSnapshotHealth", traceSnapshotHealth(traceSnapshots, traceSnapshotSummaries));
        out.put("chatHarmony", chatHarmony(traceSnapshotStore, traceSnapshotSummaries));
        out.put("traceMemory", traceMemory(traceSnapshotStore, traceSnapshotSummaries));
        out.put("queryRewrite", queryRewrite(traceSnapshotStore, traceSnapshotSummaries));
        out.put("webProviders", List.of(
                Map.of(
                        "provider", "hybrid",
                        "status", webSearch ? "OK" : "DISABLED",
                        "hasKey", webSearch,
                        "keySource", "bean-presence",
                        "disabledReason", webSearch ? "" : "hybrid_web_search_provider_missing"),
                Map.of(
                        "provider", "supplemental-multi-search",
                        "status", "OK",
                        "hasKey", supplementalSearchProviderCount > 0,
                        "keySource", "adapter.acme-websearch.enabled",
                        "optional", true,
                        "providerCount", supplementalSearchProviderCount,
                        "disabledReason", supplementalSearchProviderCount > 0
                                 ? ""
                                 : "supplemental_multi_search_providers_disabled")));
        out.put("providerStatus", providerStatus(traceSnapshotStore));
        out.put("providerRuntime", Map.of(
                "status", webSearch ? "OK" : "WARN",
                "reason", webSearch ? "provider_bean_present" : "provider_bean_missing",
                "source", "direct_core_bean_presence",
                "awaitTimeoutCount", 0,
                "cancelSuppressedCount", 0,
                "cooldownSkippedCount", 0));
        out.put("failSoftLadder", Map.of(
                "status", "WARN",
                "reason", "trace_not_observed",
                "source", "direct_core_bean_presence",
                "outCount", 0,
                "cacheOnlyMergedCount", 0,
                "tracePoolSize", 0,
                "rescueMergeUsed", false,
                "poolSafeEmpty", false));
        out.put("externalEvidence", List.of(
                goalNextExternal(),
                supabaseExternal(),
                computerUseExternal(),
                browserUseExternal()));
        out.put("strategyPerformances", List.of());
        out.put("hotspotDistribution", List.of());
        out.put("recentFailures", List.of());
        return out;
    }

    private static List<Map<String, Object>> providerStatus(TraceSnapshotStore traceSnapshotStore) {
        Map<String, Object> trace = new LinkedHashMap<>(latestProviderStatusTrace(traceSnapshotStore));
        trace.putAll(TraceStore.getAll());
        return List.of(
                providerStatusRow(trace, "brave", "search", "not_applicable"),
                providerStatusRow(trace, "naver", "search", "not_applicable"),
                providerStatusRow(trace, "gemini", "search-expansion", "unavailable"));
    }

    private static Map<String, Object> providerStatusRow(Map<String, Object> trace,
                                                          String provider,
                                                          String fallbackRoute,
                                                          String fallbackModel) {
        String exactPrefix = "provider.status." + provider + ".";
        String webPrefix = "web." + provider + ".";
        boolean observed = trace.containsKey(exactPrefix + "provider")
                || trace.containsKey(exactPrefix + "attemptCount")
                || trace.containsKey(webPrefix + "httpStatus")
                || trace.containsKey(webPrefix + "tookMs")
                || trace.containsKey(webPrefix + "failureReason");
        boolean enabled = coreBoolean(trace.get(exactPrefix + "enabled"), false)
                && !coreBoolean(trace.get(webPrefix + "providerDisabled"), false);
        String fallbackReason = coreProviderLabel(firstTraceValue(trace,
                exactPrefix + "fallbackReason",
                webPrefix + "disabledReasonCanonical",
                webPrefix + "disabledReason",
                webPrefix + "failureReason",
                webPrefix + "skipped.reason"), null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("route", coreProviderLabel(trace.get(exactPrefix + "route"), fallbackRoute));
        row.put("model", coreProviderLabel(trace.get(exactPrefix + "model"), fallbackModel));
        row.put("enabled", enabled);
        row.put("credentialPresent", coreBoolean(trace.get(exactPrefix + "credentialPresent"), false));
        row.put("attemptCount", (int) coreBoundedLong(trace.get(exactPrefix + "attemptCount"), 1_000L));
        row.put("statusCode", coreStatusCode(firstTraceValue(trace,
                exactPrefix + "statusCode", webPrefix + "httpStatus")));
        row.put("latencyMs", coreBoundedLong(firstTraceValue(trace,
                exactPrefix + "latencyMs", webPrefix + "tookMs"), 600_000L));
        row.put("cacheHit", coreBoolean(firstTraceValue(trace,
                exactPrefix + "cacheHit", webPrefix + "cacheOnly.hit"), false));
        row.put("quotaDecision", coreProviderQuota(trace, exactPrefix, webPrefix));
        row.put("fallbackReason", fallbackReason == null || fallbackReason.isBlank()
                ? (observed ? "not_observed" : "unavailable")
                : fallbackReason);
        row.put("errorClass", coreProviderLabel(firstTraceValue(trace,
                exactPrefix + "errorClass", webPrefix + "exceptionType"), "not_observed"));
        return row;
    }

    private static Map<String, Object> latestProviderStatusTrace(TraceSnapshotStore store) {
        if (store == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : store.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(String.valueOf(id));
                if (snapshot.isPresent() && hasProviderStatusSignal(snapshot.get().trace())) {
                    return snapshot.get().trace();
                }
            }
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("providerStatusSnapshot", ex);
        }
        return Map.of();
    }

    private static boolean hasProviderStatusSignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (String provider : List.of("brave", "naver", "gemini")) {
            if (trace.containsKey("provider.status." + provider + ".provider")
                    || trace.containsKey("provider.status." + provider + ".attemptCount")
                    || trace.containsKey("web." + provider + ".httpStatus")
                    || trace.containsKey("web." + provider + ".tookMs")
                    || trace.containsKey("web." + provider + ".failureReason")) {
                return true;
            }
        }
        return false;
    }

    private static String coreProviderQuota(Map<String, Object> trace,
                                            String exactPrefix,
                                            String webPrefix) {
        String exact = coreProviderLabel(trace.get(exactPrefix + "quotaDecision"), null);
        if (exact != null) {
            return exact;
        }
        if (coreBoolean(trace.get(webPrefix + "quota.exhausted"), false)) {
            return "exhausted";
        }
        return trace.containsKey(webPrefix + "quota.remaining") ? "allowed" : "not_observed";
    }

    private static boolean coreBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        return fallback;
    }

    private static Object coreStatusCode(Object value) {
        long code = coreBoundedLong(value, 999L);
        return code >= 100L && code <= 599L ? (int) code : "not_observed";
    }

    private static long coreBoundedLong(Object value, long maximum) {
        if (value == null) {
            return 0L;
        }
        try {
            long parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value).trim());
            return Math.max(0L, Math.min(maximum, parsed));
        } catch (NumberFormatException ex) {
            traceHeartbeatSuppressed("providerStatusNumber", ex);
            return 0L;
        }
    }

    private static String coreProviderLabel(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()
                || text.length() > 80
                || !text.matches("[A-Za-z0-9_.:-]{1,80}")
                || lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("secret=")
                || lower.contains("token=")) {
            return fallback;
        }
        return text;
    }

    private static String heartbeatRollupReason(String coreStatus, String fallbackReason) {
        if (!"OK".equals(coreStatus)) {
            return "core_beans_missing";
        }
        if ("agent_db_context_disabled".equals(fallbackReason)) {
            return "supporting_external_evidence_missing";
        }
        return SafeRedactor.traceLabelOrFallback(fallbackReason, "pipeline_health_unavailable");
    }

    private static String heartbeatRollupNextAction(String heartbeatReason) {
        return switch (heartbeatReason) {
            case "core_beans_missing" -> "restore_core_runtime_beans";
            case "supporting_external_evidence_missing" -> "none_for_desktop_only";
            case "pipeline_health_unavailable" -> "inspect_pipeline_health_controller";
            default -> "inspect_chat_ui_heartbeat";
        };
    }

    private static Map<String, Object> debugAiMetrics(ObjectProvider<DebugAiMetricsService> provider,
                                                      boolean debugEventsAvailable) {
        try {
            DebugAiMetricsService service = provider == null ? null : provider.getIfAvailable();
            if (service == null) {
                return debugAiMetricsUnavailable(debugEventsAvailable
                        ? "debug_ai_metrics_service_missing"
                        : "debug_event_store_missing");
            }
            Map<String, Object> compact = service.compactSnapshot(80, 300_000L);
            Map<String, Object> scorecard = mapValue(compact.get("scorecard"));
            List<Map<String, Object>> tiles = mapListValue(compact.get("tiles"));
            List<Map<String, Object>> planUsage = mapListValue(compact.get("planUsage"));
            List<Map<String, Object>> hotChunks = mapListValue(compact.get("virtualMatrixHotChunks"));
            boolean harmonyInputPresent = chatHarmonyInputPresent(scorecard, tiles, planUsage);
            String harmonyFailureClass = chatHarmonyFailureClass(scorecard, tiles);
            boolean traceMemoryInputPresent = traceMemoryInputPresent(scorecard, tiles, planUsage);
            String traceMemoryFailureClass = traceMemoryFailureClass(scorecard, tiles);
            String matrixDecision = stringValue(compact.get("virtualMatrixDecision"), "observe");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", statusFromCounts(compact.get("warnEvents"), compact.get("errorEvents")));
            out.put("reason", "debug_ai_metrics_compact");
            out.put("totalEvents", numberValue(compact.get("totalEvents")));
            out.put("warnEvents", numberValue(compact.get("warnEvents")));
            out.put("errorEvents", numberValue(compact.get("errorEvents")));
            out.put("topTile", stringValue(scorecard.get("hotTile"), "none"));
            out.put("topFailureClass", stringValue(scorecard.get("anomalyFailureClass"), "none"));
            out.put("chatHarmonyInputPresent", harmonyInputPresent);
            out.put("chatHarmonyFailureClass", harmonyFailureClass);
            out.put("traceMemoryInputPresent", traceMemoryInputPresent);
            out.put("traceMemoryFailureClass", traceMemoryFailureClass);
            out.put("evidenceOutputEvents", evidenceOutputEvents(tiles));
            out.put("nextDebugAction", nextDebugAction(matrixDecision,
                    harmonyInputPresent,
                    harmonyFailureClass,
                    traceMemoryInputPresent,
                    traceMemoryFailureClass));
            out.put("nextDebugReason", nextDebugReason(matrixDecision,
                    harmonyInputPresent,
                    harmonyFailureClass,
                    traceMemoryInputPresent,
                    traceMemoryFailureClass));
            out.put("hotChunkIndex", hotChunkNumber(hotChunks, "chunkIndex"));
            out.put("hotChunkRiskScore", hotChunkDouble(hotChunks, "riskScore"));
            out.put("virtualMatrixCount", numberValue(compact.get("virtualMatrixCount")));
            out.put("virtualMatrixChunkCount", numberValue(compact.get("virtualMatrixChunkCount")));
            out.put("virtualMatrixWeightedScore", doubleValue(compact.get("virtualMatrixWeightedScore")));
            out.put("virtualMatrixScoreRole", stringValue(compact.get("virtualMatrixScoreRole"), "evidence"));
            out.put("virtualMatrixScoreTrusted", truthy(compact.get("virtualMatrixScoreTrusted")));
            out.put("virtualMatrixDecision", matrixDecision);
            out.put("virtualMatrixActionAllowed", truthy(compact.get("virtualMatrixActionAllowed")));
            out.put("historyComparisonComparable", truthy(scorecard.get("historyComparisonComparable")));
            out.put("historyComparisonReason",
                    stringValue(scorecard.get("historyComparisonReason"), "baseline_missing"));
            out.put("chatUsage", mapValue(scorecard.get("chatUsage")));
            out.put("currentWindowMs", numberValue(scorecard.get("currentWindowMs")));
            out.put("previousWindowMs", numberValue(scorecard.get("previousWindowMs")));
            out.put("currentSampleLimit", numberValue(scorecard.get("currentSampleLimit")));
            out.put("previousSampleLimit", numberValue(scorecard.get("previousSampleLimit")));
            out.put("virtualMatrixHotChunks", listValue(compact.get("virtualMatrixHotChunks"), 3));
            return out;
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("debugAiMetrics", ex);
            return debugAiMetricsUnavailable("debug_ai_metrics_unavailable");
        }
    }

    private static Map<String, Object> debugAiMetricsUnavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "WARN");
        out.put("reason", reason);
        out.put("totalEvents", 0L);
        out.put("warnEvents", 0L);
        out.put("errorEvents", 0L);
        out.put("topTile", "none");
        out.put("topFailureClass", "none");
        out.put("chatHarmonyInputPresent", false);
        out.put("chatHarmonyFailureClass", "none");
        out.put("traceMemoryInputPresent", false);
        out.put("traceMemoryFailureClass", "none");
        out.put("evidenceOutputEvents", 0L);
        out.put("nextDebugAction", "debug_ai_metrics_unavailable");
        out.put("nextDebugReason", reason);
        out.put("hotChunkIndex", -1L);
        out.put("hotChunkRiskScore", 0.0d);
        out.put("virtualMatrixCount", 0L);
        out.put("virtualMatrixChunkCount", 0L);
        out.put("virtualMatrixWeightedScore", 0.0d);
        out.put("virtualMatrixScoreRole", "evidence");
        out.put("virtualMatrixScoreTrusted", false);
        out.put("virtualMatrixDecision", "unavailable");
        out.put("virtualMatrixActionAllowed", false);
        out.put("historyComparisonComparable", false);
        out.put("historyComparisonReason", "unavailable");
        out.put("currentWindowMs", 0L);
        out.put("previousWindowMs", 0L);
        out.put("currentSampleLimit", 0L);
        out.put("previousSampleLimit", 0L);
        out.put("virtualMatrixHotChunks", List.of());
        return out;
    }

    private static Map<String, Object> modelRuntime(
            LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService,
            DebugEventStore debugEventStore) {
        Map<String, Object> operatorAction = localLlmOperatorAction(TraceStore.getAll());
        String source = "traceStore";
        boolean staleDebugEvent = false;
        boolean staleSmokeHistory = false;
        String smokeHistoryEvidenceMode = null;
        if (operatorAction.isEmpty()) {
            LocalLlmDebugEventAction debugEventAction = localLlmDebugEventOperatorAction(debugEventStore);
            operatorAction = debugEventAction.operatorAction();
            staleDebugEvent = debugEventAction.stale();
            if (!operatorAction.isEmpty() || staleDebugEvent) {
                source = "debugEventStore";
            }
        }
        if (operatorAction.isEmpty()) {
            Map<String, Object> smokeHistory = localLlmSmokeHistorySnapshot(localLlmSmokeHistoryDiagnosticsService);
            staleSmokeHistory = Boolean.TRUE.equals(smokeHistory.get("reportStale"));
            smokeHistoryEvidenceMode = traceLabel(smokeHistory.get("evidenceMode"), null);
            Map<String, Object> smokeHistoryOperatorAction = staleSmokeHistory
                    ? Map.of()
                    : localLlmSmokeHistoryOperatorAction(smokeHistory);
            if (staleSmokeHistory) {
                if (!staleDebugEvent) {
                    source = "localLlmSmokeHistory";
                }
            } else if (!smokeHistoryOperatorAction.isEmpty() && localModelRuntimeRecentlySucceeded()) {
                operatorAction = localModelSuccessOperatorAction();
                source = "modelRuntimeHealth";
            } else {
                operatorAction = smokeHistoryOperatorAction;
                if (!operatorAction.isEmpty()) {
                    source = "localLlmSmokeHistory";
                } else if (!staleDebugEvent) {
                    source = "traceStore";
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        boolean cleared = clearedLocalLlmOperatorAction(operatorAction);
        boolean staleSupportingEvidenceOnly = operatorAction.isEmpty() && (staleDebugEvent || staleSmokeHistory);
        out.put("status", staleSupportingEvidenceOnly
                ? "UNKNOWN"
                : (operatorAction.isEmpty() || cleared ? "OK" : "WARN"));
        out.put("reason", operatorAction.isEmpty()
                ? (staleDebugEvent
                        ? "stale_local_llm_debug_event"
                        : (staleSmokeHistory ? "stale_local_llm_smoke_history" : "no_local_llm_operator_action"))
                : (cleared ? "recent_local_model_success" : "local_llm_operator_action"));
        out.put("source", source);
        if (staleDebugEvent) {
            out.put("localLlmDebugEventStale", true);
            out.put("localLlmDebugEventSuppressedReason", "debug_event_stale");
        }
        if (staleSmokeHistory) {
            out.put("localLlmSmokeHistoryStale", true);
            out.put("localLlmSmokeHistoryEvidenceMode",
                    smokeHistoryEvidenceMode == null || smokeHistoryEvidenceMode.isBlank()
                            ? "supporting_stale"
                            : smokeHistoryEvidenceMode);
            out.put("localLlmSmokeHistorySuppressedReason", "smoke_report_stale");
        }
        if (cleared && "modelRuntimeHealth".equals(source)) {
            out.put("localLlmSmokeHistoryStale", true);
            out.put("localLlmSmokeHistorySuppressedReason", "recent_local_model_success");
        }
        if (!operatorAction.isEmpty()) {
            out.put("localLlmOperatorAction", operatorAction);
        }
        return out;
    }

    private static boolean localModelRuntimeRecentlySucceeded() {
        return OllamaNativeChatModel.hasRecentNativeSuccess(Duration.ofMinutes(30))
                || ModelRuntimeHealthTracker.hasRecentLocalSuccess(Duration.ofMinutes(30));
    }

    private static Map<String, Object> localModelSuccessOperatorAction() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggered", false);
        out.put("triggerReason", "recent_local_model_success");
        out.put("failureClass", "none");
        out.put("nextAction", "none");
        out.put("actionScore", 0);
        out.put("scoreDelta", 0);
        out.put("negativeSignalCount", 0);
        return out;
    }

    private static boolean clearedLocalLlmOperatorAction(Map<String, Object> operatorAction) {
        if (operatorAction == null
                || !"none".equals(traceLabel(operatorAction.get("failureClass"), null))
                || !"none".equals(traceLabel(operatorAction.get("nextAction"), null))) {
            return false;
        }
        if (Boolean.FALSE.equals(operatorAction.get("triggered"))) {
            return true;
        }
        return !operatorAction.containsKey("triggered")
                && isLocalLlmSuccessTriggerReason(traceLabel(operatorAction.get("triggerReason"), null));
    }

    private static boolean isLocalLlmSuccessTriggerReason(String triggerReason) {
        return "native_success".equals(triggerReason)
                || "recent_local_model_success".equals(triggerReason);
    }

    private static Map<String, Object> localLlmSmokeHistoryOperatorAction(
            LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService) {
        return localLlmSmokeHistoryOperatorAction(localLlmSmokeHistorySnapshot(localLlmSmokeHistoryDiagnosticsService));
    }

    private static Map<String, Object> localLlmSmokeHistorySnapshot(
            LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService) {
        if (localLlmSmokeHistoryDiagnosticsService == null) {
            return Map.of();
        }
        try {
            return localLlmSmokeHistoryDiagnosticsService.snapshot(1);
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("localLlmSmokeHistory", ex);
            return Map.of();
        }
    }

    private static Map<String, Object> localLlmSmokeHistoryOperatorAction(Map<String, Object> snapshot) {
        Map<String, Object> latest = mapValue(snapshot.get("latest"));
        return localLlmOperatorActionFromMap(mapValue(latest.get("operatorAction")));
    }

    private static LocalLlmDebugEventAction localLlmDebugEventOperatorAction(DebugEventStore debugEventStore) {
        if (debugEventStore == null) {
            return LocalLlmDebugEventAction.empty();
        }
        try {
            for (DebugEvent event : debugEventStore.listByProbe(DebugProbeType.MODEL_GUARD, 20)) {
                Map<String, Object> operatorAction = localLlmOperatorActionFromDebugEvent(event);
                if (!operatorAction.isEmpty()) {
                    if (debugEventFresh(event, LOCAL_LLM_DEBUG_EVENT_OPERATOR_ACTION_TTL)) {
                        return new LocalLlmDebugEventAction(operatorAction, false);
                    }
                    return LocalLlmDebugEventAction.staleCandidate();
                }
            }
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("localLlmDebugEventOperatorAction", ex);
        }
        return LocalLlmDebugEventAction.empty();
    }

    private static boolean debugEventFresh(DebugEvent event, Duration ttl) {
        if (event == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return false;
        }
        Instant ts = event.ts();
        if (ts == null && event.tsMs() > 0L) {
            ts = Instant.ofEpochMilli(event.tsMs());
        }
        return ts != null && !ts.isBefore(Instant.now().minus(ttl));
    }

    private record LocalLlmDebugEventAction(Map<String, Object> operatorAction, boolean stale) {
        private static LocalLlmDebugEventAction empty() {
            return new LocalLlmDebugEventAction(Map.of(), false);
        }

        private static LocalLlmDebugEventAction staleCandidate() {
            return new LocalLlmDebugEventAction(Map.of(), true);
        }
    }

    private static Map<String, Object> localLlmOperatorActionFromDebugEvent(DebugEvent event) {
        Map<String, Object> data = event == null ? Map.of() : mapValue(event.data());
        if (!looksLikeLocalLlmOperatorAction(data)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        putIfPresent(normalized, "triggered", firstTraceValue(data,
                "triggered", "localLlmTriggered", "operatorActionTriggered"));
        putIfPresent(normalized, "triggerReason", firstTraceValue(data,
                "triggerReason", "localLlmTriggerReason", "operatorActionTriggerReason"));
        putIfPresent(normalized, "failureClass", firstTraceValue(data,
                "failureClass", "localLlmFailureClass", "operatorActionFailureClass"));
        putIfPresent(normalized, "nextAction", firstTraceValue(data,
                "nextAction", "localLlmNextAction", "operatorActionNext"));
        putIfPresent(normalized, "actionScore", firstTraceValue(data,
                "actionScore", "localLlmActionScore", "operatorActionScore"));
        putIfPresent(normalized, "scoreDelta", firstTraceValue(data,
                "scoreDelta", "localLlmScoreDelta", "operatorActionScoreDelta"));
        putIfPresent(normalized, "negativeSignalCount", firstTraceValue(data,
                "negativeSignalCount", "localLlmNegativeSignalCount", "operatorActionNegativeSignalCount"));
        putIfPresent(normalized, "upstreamStatus", firstTraceValue(data,
                "upstreamStatus", "localLlmUpstreamStatus", "operatorActionUpstreamStatus"));
        putIfPresent(normalized, "upstreamFailureClass", firstTraceValue(data,
                "upstreamFailureClass", "localLlmUpstreamFailureClass", "operatorActionUpstreamFailureClass"));
        putIfPresent(normalized, "upstreamNextAction", firstTraceValue(data,
                "upstreamNextAction", "localLlmUpstreamNextAction", "operatorActionUpstreamNextAction"));
        Map<String, Object> operatorAction = localLlmOperatorActionFromMap(normalized);
        if (operatorAction.isEmpty()) {
            return Map.of();
        }
        String failureClass = traceLabel(operatorAction.get("failureClass"), null);
        String nextAction = traceLabel(operatorAction.get("nextAction"), null);
        if (!operatorAction.containsKey("triggered")
                && ((hasText(failureClass) && !isNone(failureClass))
                || (hasText(nextAction) && !isNone(nextAction)))) {
            operatorAction.put("triggered", true);
        }
        return operatorAction;
    }

    private static boolean looksLikeLocalLlmOperatorAction(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        String stage = traceLabel(data.get("stage"), "");
        String nextAction = traceLabel(firstTraceValue(data,
                "nextAction", "localLlmNextAction", "operatorActionNext"), null);
        return "local_llm_operator_action".equals(stage)
                || data.containsKey("localLlmNextAction")
                || data.containsKey("operatorActionNext")
                || "inspect_ollama_runtime_capacity".equals(nextAction);
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (out != null && key != null && value != null) {
            out.put(key, value);
        }
    }

    private static Map<String, Object> localLlmOperatorAction(Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        putOperatorBoolean(out, "triggered", trace,
                "llm.localSmoke.operatorAction.triggered",
                "prompt.agentDebugEvidence.localLlm.operatorAction.triggered",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.triggered");
        putOperatorLabel(out, "triggerReason", trace,
                "llm.localSmoke.operatorAction.triggerReason",
                "prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.triggerReason");
        putOperatorLabel(out, "failureClass", trace,
                "llm.localSmoke.operatorAction.failureClass",
                "prompt.agentDebugEvidence.localLlm.operatorAction.failureClass",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.failureClass");
        putOperatorLabel(out, "nextAction", trace,
                "llm.localSmoke.operatorAction.nextAction",
                "prompt.agentDebugEvidence.localLlm.operatorAction.nextAction",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.nextAction");
        putOperatorInt(out, "actionScore", trace,
                "llm.localSmoke.operatorAction.actionScore",
                "prompt.agentDebugEvidence.localLlm.operatorAction.actionScore",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.actionScore");
        putOperatorInt(out, "scoreDelta", trace,
                "llm.localSmoke.operatorAction.scoreDelta",
                "prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.scoreDelta");
        putOperatorInt(out, "negativeSignalCount", trace,
                "llm.localSmoke.operatorAction.negativeSignalCount",
                "prompt.agentDebugEvidence.localLlm.operatorAction.negativeSignalCount",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.negativeSignalCount");
        putOperatorInt(out, "upstreamStatus", trace,
                "llm.localSmoke.operatorAction.upstreamStatus",
                "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamStatus",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.upstreamStatus");
        putOperatorLabel(out, "upstreamFailureClass", trace,
                "llm.localSmoke.operatorAction.upstreamFailureClass",
                "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamFailureClass",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.upstreamFailureClass");
        putOperatorLabel(out, "upstreamNextAction", trace,
                "llm.localSmoke.operatorAction.upstreamNextAction",
                "prompt.agentDebugEvidence.localLlm.operatorAction.upstreamNextAction",
                "debug.ai.agentDebugEvidence.localLlm.operatorAction.upstreamNextAction");
        promoteUpstreamNextAction(out);
        return out.isEmpty() ? Map.of() : out;
    }

    private static Map<String, Object> localLlmOperatorActionFromMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putOperatorBoolean(out, "triggered", source, "triggered");
        putOperatorLabel(out, "triggerReason", source, "triggerReason");
        putOperatorLabel(out, "failureClass", source, "failureClass");
        putOperatorLabel(out, "nextAction", source, "nextAction");
        putOperatorInt(out, "actionScore", source, "actionScore");
        putOperatorInt(out, "scoreDelta", source, "scoreDelta");
        putOperatorInt(out, "negativeSignalCount", source, "negativeSignalCount");
        putOperatorInt(out, "upstreamStatus", source, "upstreamStatus");
        putOperatorLabel(out, "upstreamFailureClass", source, "upstreamFailureClass");
        putOperatorLabel(out, "upstreamNextAction", source, "upstreamNextAction");
        promoteUpstreamNextAction(out);
        return out.isEmpty() ? Map.of() : out;
    }

    private static void promoteUpstreamNextAction(Map<String, Object> operatorAction) {
        if (operatorAction == null || operatorAction.isEmpty()) {
            return;
        }
        String upstreamFailure = traceLabel(operatorAction.get("upstreamFailureClass"), null);
        String upstreamNext = traceLabel(operatorAction.get("upstreamNextAction"), null);
        int upstreamStatus = (int) Math.min(Integer.MAX_VALUE, numberValue(operatorAction.get("upstreamStatus")));
        if (upstreamNext == null || upstreamNext.isBlank()) {
            return;
        }
        if (upstreamStatus >= 400 || (upstreamFailure != null && !upstreamFailure.isBlank())) {
            operatorAction.put("nextAction", upstreamNext);
        }
    }

    private static void putOperatorBoolean(Map<String, Object> out,
                                           String outputKey,
                                           Map<String, Object> trace,
                                           String... traceKeys) {
        Object value = firstTraceValue(trace, traceKeys);
        if (value != null) {
            out.put(outputKey, truthy(value));
        }
    }

    private static void putOperatorLabel(Map<String, Object> out,
                                         String outputKey,
                                         Map<String, Object> trace,
                                         String... traceKeys) {
        String value = traceLabel(firstTraceValue(trace, traceKeys), null);
        if (value != null) {
            out.put(outputKey, value);
        }
    }

    private static void putOperatorInt(Map<String, Object> out,
                                       String outputKey,
                                       Map<String, Object> trace,
                                       String... traceKeys) {
        Object value = firstTraceValue(trace, traceKeys);
        if (value != null) {
            out.put(outputKey, (int) Math.min(Integer.MAX_VALUE, numberValue(value)));
        }
    }

    private static Object firstTraceValue(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && trace.containsKey(key)) {
                return trace.get(key);
            }
        }
        return null;
    }

    private static String nextDebugAction(String matrixDecision,
                                          boolean harmonyInputPresent,
                                          String harmonyFailureClass,
                                          boolean traceMemoryInputPresent,
                                          String traceMemoryFailureClass) {
        if (traceMemoryInputPresent && containsTraceMemory(traceMemoryFailureClass)
                && !"trace_memory.observed".equals(traceMemoryFailureClass)) {
            return "inspect_trace_memory_trace";
        }
        if (harmonyInputPresent && containsChatHarmony(harmonyFailureClass)
                && !"chat_harmony.smooth_chat".equals(harmonyFailureClass)) {
            return "inspect_chat_harmony_trace";
        }
        return matrixActionFromDecision(matrixDecision);
    }

    private static String nextDebugReason(String matrixDecision,
                                          boolean harmonyInputPresent,
                                          String harmonyFailureClass,
                                          boolean traceMemoryInputPresent,
                                          String traceMemoryFailureClass) {
        if (traceMemoryInputPresent && containsTraceMemory(traceMemoryFailureClass)
                && !"trace_memory.observed".equals(traceMemoryFailureClass)) {
            return traceLabel(traceMemoryFailureClass, "trace_memory_observed");
        }
        if (harmonyInputPresent && containsChatHarmony(harmonyFailureClass)
                && !"chat_harmony.smooth_chat".equals(harmonyFailureClass)) {
            return traceLabel(harmonyFailureClass, "chat_harmony_observed");
        }
        return traceLabel(matrixDecision, "observe");
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

    private static long hotChunkNumber(List<Map<String, Object>> hotChunks, String key) {
        if (hotChunks == null || hotChunks.isEmpty()) {
            return -1L;
        }
        return numberValue(hotChunks.get(0).get(key));
    }

    private static double hotChunkDouble(List<Map<String, Object>> hotChunks, String key) {
        if (hotChunks == null || hotChunks.isEmpty()) {
            return 0.0d;
        }
        return doubleValue(hotChunks.get(0).get(key));
    }

    private static boolean chatHarmonyInputPresent(Map<String, Object> scorecard,
                                                   List<Map<String, Object>> tiles,
                                                   List<Map<String, Object>> planUsage) {
        for (Map<String, Object> row : planUsage) {
            if ("chat.harmony.postprocess".equals(stringValue(row.get("planId"), ""))) {
                return true;
            }
        }
        return hasChatHarmonyFailureClass(scorecard, tiles);
    }

    private static boolean traceMemoryInputPresent(Map<String, Object> scorecard,
                                                   List<Map<String, Object>> tiles,
                                                   List<Map<String, Object>> planUsage) {
        for (Map<String, Object> row : planUsage) {
            String planId = stringValue(row.get("planId"), "");
            if ("trace.memory.fingerprint".equals(planId)) {
                return true;
            }
        }
        return hasTraceMemoryFailureClass(scorecard, tiles);
    }

    private static String chatHarmonyFailureClass(Map<String, Object> scorecard,
                                                  List<Map<String, Object>> tiles) {
        Object anomaly = scorecard.get("anomalyFailureClass");
        if (containsChatHarmony(anomaly)) {
            return traceLabel(anomaly, "none");
        }
        for (Map<String, Object> tile : tiles) {
            Object value = tile.get("topFailureClass");
            if (containsChatHarmony(value)) {
                return traceLabel(value, "none");
            }
        }
        return "none";
    }

    private static String traceMemoryFailureClass(Map<String, Object> scorecard,
                                                  List<Map<String, Object>> tiles) {
        Object anomaly = scorecard.get("anomalyFailureClass");
        if (containsTraceMemory(anomaly)) {
            return traceLabel(anomaly, "none");
        }
        for (Map<String, Object> tile : tiles) {
            Object value = tile.get("topFailureClass");
            if (containsTraceMemory(value)) {
                return traceLabel(value, "none");
            }
        }
        return "trace_memory.observed";
    }

    private static long evidenceOutputEvents(List<Map<String, Object>> tiles) {
        for (Map<String, Object> tile : tiles) {
            if ("EVIDENCE_OUTPUT".equals(stringValue(tile.get("tileName"), ""))) {
                return numberValue(tile.get("eventCount"));
            }
        }
        return 0L;
    }

    private static boolean hasChatHarmonyFailureClass(Map<String, Object> scorecard,
                                                      List<Map<String, Object>> tiles) {
        if (containsChatHarmony(scorecard.get("anomalyFailureClass"))) {
            return true;
        }
        for (Map<String, Object> tile : tiles) {
            if (containsChatHarmony(tile.get("topFailureClass"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTraceMemoryFailureClass(Map<String, Object> scorecard,
                                                      List<Map<String, Object>> tiles) {
        if (containsTraceMemory(scorecard.get("anomalyFailureClass"))) {
            return true;
        }
        for (Map<String, Object> tile : tiles) {
            if (containsTraceMemory(tile.get("topFailureClass"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsChatHarmony(Object value) {
        return value != null && String.valueOf(value).contains("chat_harmony");
    }

    private static boolean containsTraceMemory(Object value) {
        return value != null && String.valueOf(value).contains("trace_memory");
    }

    private static String statusFromCounts(Object warnEvents, Object errorEvents) {
        return numberValue(warnEvents) > 0L || numberValue(errorEvents) > 0L ? "WARN" : "OK";
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry != null && entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> mapListValue(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry != null && entry.getKey() != null) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                out.add(row);
            } else if (item instanceof DebugAiRawTile tile) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tileName", tile.tileName());
                row.put("eventCount", tile.eventCount());
                row.put("warnCount", tile.warnCount());
                row.put("errorCount", tile.errorCount());
                row.put("topFailureClass", tile.topFailureClass());
                row.put("status", tile.status());
                out.add(row);
            }
            if (out.size() >= 50) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<Object> listValue(Object value, int limit) {
        if (!(value instanceof Iterable<?> iterable) || limit <= 0) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        for (Object item : iterable) {
            out.add(item);
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static long numberValue(Object value) {
        if (value instanceof Number n) {
            return Math.max(0L, n.longValue());
        }
        return 0L;
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            return Double.isFinite(parsed) ? Math.max(0.0d, parsed) : 0.0d;
        }
        return 0.0d;
    }

    private static String stringValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static TraceSnapshotStore traceSnapshotStore(ObjectProvider<TraceSnapshotStore> provider) {
        try {
            return provider == null ? null : provider.getIfAvailable();
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("traceSnapshotStore", ex);
            return null;
        }
    }

    private static DebugEventStore debugEventStore(ObjectProvider<DebugEventStore> provider) {
        try {
            return provider == null ? null : provider.getIfAvailable();
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("debugEventStore", ex);
            return null;
        }
    }

    private static List<Map<String, Object>> traceSnapshotSummaries(TraceSnapshotStore store, int limit) {
        if (store == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> summaries = store.listSummaries(limit);
            return summaries == null ? List.of() : summaries;
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("traceSnapshotSummaries", ex);
            return List.of();
        }
    }

    private static Map<String, Object> traceSnapshotHealth(boolean available,
                                                           List<Map<String, Object>> summaries) {
        Map<String, Object> latest = summaries == null || summaries.isEmpty()
                ? Map.of()
                : summaries.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", available ? "OK" : "WARN");
        out.put("reason", available ? "available" : "trace_snapshot_store_missing");
        out.put("available", available);
        out.put("summaryCount", summaries == null ? 0 : summaries.size());
        out.put("latestTraceEntryCount", numberValue(latest.get("traceEntryCount")));
        out.put("latestEventCount", numberValue(latest.get("eventCount")));
        return out;
    }

    private static Map<String, Object> chatHarmony(TraceSnapshotStore store,
                                                   List<Map<String, Object>> summaries) {
        Map<String, Object> out = chatHarmonyUnavailable(store == null
                ? "trace_snapshot_store_missing"
                : "harmony_trace_missing");
        if (store == null || summaries == null || summaries.isEmpty()) {
            return out;
        }
        for (Map<String, Object> summary : summaries) {
            String id = stringValue(summary == null ? null : summary.get("id"), "");
            if (!hasText(id)) {
                continue;
            }
            Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isEmpty()) {
                continue;
            }
            Map<String, Object> trace = snapshot.get().trace();
            if (!hasHarmonyTrace(trace)) {
                continue;
            }
            out.put("status", "OK");
            out.put("reason", "latest_chat_harmony_trace");
            out.put("latestDecision", traceLabel(trace.get("chat.harmony.postprocess.decision"), "unknown"));
            out.put("latestReason", traceLabel(trace.get("chat.harmony.postprocess.reason"), "unknown"));
            out.put("latestDegraded", truthy(trace.get("chat.harmony.postprocess.degraded")));
            out.put("latestWeightedScore", doubleValue(trace.get("chat.harmony.postprocess.weightedScore")));
            out.put("latestEvidenceCount", numberValue(trace.get("chat.harmony.postprocess.evidenceCount")));
            out.put("latestAnswerLength", numberValue(trace.get("chat.harmony.postprocess.answerLength")));
            out.put("latestSentenceCount", numberValue(trace.get("chat.harmony.postprocess.sentenceCount")));
            out.put("latestAgentVisible", truthy(trace.get("chat.harmony.postprocess.agentVisible")));
            out.put("latestDebugAction", traceLabel(trace.get("debug.ai.metrics.nextAction"), "continue_observing_chat_harmony"));
            out.put("latestDebugReason", traceLabel(trace.get("debug.ai.metrics.nextReason"), "chat_harmony_observed"));
            out.put("latestTraceRoute", LATEST_HARMONY_TRACE_ROUTE);
            out.put("latestTraceIdHash", stringValue(SafeRedactor.hashValue(id), ""));
            out.put("latestTraceIdLength", id.length());
            out.put("latestTraceEntryCount", numberValue(summary.get("traceEntryCount")));
            out.put("latestEventCount", numberValue(summary.get("eventCount")));
            return out;
        }
        return out;
    }

    private static Map<String, Object> chatHarmonyUnavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "WARN");
        out.put("reason", reason);
        out.put("source", "traceSnapshotStore");
        out.put("latestDecision", "unknown");
        out.put("latestReason", "unknown");
        out.put("latestDegraded", false);
        out.put("latestWeightedScore", 0.0d);
        out.put("latestEvidenceCount", 0L);
        out.put("latestAnswerLength", 0L);
        out.put("latestSentenceCount", 0L);
        out.put("latestAgentVisible", false);
        out.put("latestDebugAction", "wait_for_chat_harmony_trace");
        out.put("latestDebugReason", traceLabel(reason, "harmony_trace_missing"));
        out.put("latestTraceRoute", LATEST_HARMONY_TRACE_ROUTE);
        out.put("latestTraceIdHash", "");
        out.put("latestTraceIdLength", 0);
        out.put("latestTraceEntryCount", 0L);
        out.put("latestEventCount", 0L);
        return out;
    }

    private static Map<String, Object> traceMemory(TraceSnapshotStore store,
                                                   List<Map<String, Object>> summaries) {
        Map<String, Object> out = traceMemoryUnavailable(store == null
                ? "trace_snapshot_store_missing"
                : "trace_memory_trace_missing");
        if (store == null || summaries == null || summaries.isEmpty()) {
            return out;
        }
        Optional<TraceSnapshotStore.TraceSnapshot> latest = latestTraceMemoryTrace(store, summaries);
        if (latest.isEmpty()) {
            return out;
        }
        TraceSnapshotStore.TraceSnapshot snapshot = latest.get();
        Map<String, Object> trace = snapshot.trace();
        Map<String, Object> summary = traceSnapshotSummary(summaries, snapshot.id());
        boolean triggered = truthy(trace.get("traceMemory.triggered"));
        boolean quarantine = truthy(trace.get("traceMemory.recovery.quarantine"));
        String risk = traceLabel(trace.get("traceMemory.errorBreak.risk"), "none");
        String triggerReason = traceLabel(trace.get("traceMemory.trigger.reason"), "none");
        String recoveryAction = traceLabel(trace.get("traceMemory.recovery.action"), "continue_trace_memory_checkpointing");
        String recoveryRoute = traceLabel(trace.get("traceMemory.recovery.route"), "continue_trace_memory_checkpointing");
        String recoveryRouteDecision = traceLabel(trace.get("traceMemory.recovery.routeDecision"), "continue_trace_memory_checkpointing");
        boolean suspectPayloadIsolated = suspectPayloadIsolated(trace, recoveryRoute);
        out.put("status", traceMemoryStatus(triggered, quarantine, risk));
        out.put("reason", "latest_trace_memory_trace");
        out.put("latestStage", traceLabel(trace.get("traceMemory.checkpoint.stage"), "unknown"));
        out.put("latestPhase", traceLabel(trace.get("traceMemory.checkpoint.phase"), "unknown"));
        out.put("latestVirtualCheckpointKey",
                traceLabel(trace.get("traceMemory.virtualCheckpoint.latestKey"), "unknown"));
        out.put("latestVirtualCheckpointStage",
                traceLabel(trace.get("traceMemory.virtualCheckpoint.latestStage"), "unknown"));
        out.put("latestVirtualCheckpointPhase",
                traceLabel(trace.get("traceMemory.virtualCheckpoint.latestPhase"), "unknown"));
        out.put("latestCheckpointHistorySize", numberValue(trace.get("traceMemory.checkpoint.historySize")));
        out.put("latestFingerprintHash", traceLabel(trace.get("traceMemory.fingerprint.current"), ""));
        out.put("latestFingerprintChanged", truthy(trace.get("traceMemory.delta.changed")));
        out.put("latestChangedCount", numberValue(trace.get("traceMemory.delta.changedCount")));
        out.put("latestDroppedBreadcrumbCount", numberValue(trace.get("traceMemory.delta.droppedBreadcrumbCount")));
        out.put("latestTriggered", triggered);
        out.put("latestReason", triggerReason);
        out.put("latestRecoveryAction", recoveryAction);
        out.put("latestRecoveryRoute", recoveryRoute);
        out.put("latestRecoveryRouteDecision", recoveryRouteDecision);
        out.put("latestRecoveryPolicyMaxRounds", numberValue(trace.get("traceMemory.recovery.policy.maxRounds")));
        out.put("latestRecoveryPolicyMinCitations", numberValue(trace.get("traceMemory.recovery.policy.minCitations")));
        out.put("latestFailureClass", traceLabel(trace.get("traceMemory.recovery.failureClass"), "none"));
        out.put("latestQuarantine", quarantine);
        out.put("suspectPayloadIsolated", suspectPayloadIsolated);
        out.put("latestRisk", risk);
        out.put("latestCfvmOffered", truthy(trace.get("traceMemory.cfvm.offered")));
        out.put("latestCfvmPatternId", numberValue(trace.get("traceMemory.cfvm.patternId")));
        out.put("latestSupabaseShadowCount", numberValue(trace.get("traceMemory.rawSnapshot.supabaseShadowCount")));
        out.put("latestDebugAction", traceMemoryDebugAction(triggered, quarantine, risk, recoveryAction));
        out.put("latestDebugReason", traceLabel(triggerReason, "trace_memory_checkpoint_observed"));
        out.put("latestTraceRoute", LATEST_TRACE_MEMORY_ROUTE);
        out.put("latestCheckpointJsonRoute", LATEST_TRACE_MEMORY_CHECKPOINTS_ROUTE);
        out.put("latestTraceIdHash", stringValue(SafeRedactor.hashValue(snapshot.id()), ""));
        out.put("latestTraceIdLength", snapshot.id() == null ? 0 : snapshot.id().length());
        out.put("latestTraceEntryCount", numberValue(summary.getOrDefault("traceEntryCount", snapshot.traceEntryCount())));
        out.put("latestEventCount", numberValue(summary.get("eventCount")));
        return out;
    }

    private static Optional<TraceSnapshotStore.TraceSnapshot> latestTraceMemoryTrace(TraceSnapshotStore store,
                                                                                    List<Map<String, Object>> summaries) {
        if (store == null || summaries == null) {
            return Optional.empty();
        }
        Optional<TraceSnapshotStore.TraceSnapshot> fallback = Optional.empty();
        for (Map<String, Object> summary : summaries) {
            String id = stringValue(summary == null ? null : summary.get("id"), "");
            if (!hasText(id)) {
                continue;
            }
            Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isPresent() && hasTraceMemoryTrace(snapshot.get().trace())) {
                if (hasTraceMemoryRecoveryTrace(snapshot.get().trace())) {
                    return snapshot;
                }
                if (fallback.isEmpty()) {
                    fallback = snapshot;
                }
            }
        }
        return fallback;
    }

    private static Map<String, Object> traceSnapshotSummary(List<Map<String, Object>> summaries, String id) {
        if (summaries == null || id == null) {
            return Map.of();
        }
        for (Map<String, Object> summary : summaries) {
            if (summary != null && id.equals(stringValue(summary.get("id"), ""))) {
                return summary;
            }
        }
        return Map.of();
    }

    private static Map<String, Object> traceMemoryUnavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "WARN");
        out.put("reason", reason);
        out.put("source", "traceSnapshotStore");
        out.put("latestStage", "unknown");
        out.put("latestPhase", "unknown");
        out.put("latestVirtualCheckpointKey", "unknown");
        out.put("latestVirtualCheckpointStage", "unknown");
        out.put("latestVirtualCheckpointPhase", "unknown");
        out.put("latestFingerprintHash", "");
        out.put("latestFingerprintChanged", false);
        out.put("latestChangedCount", 0L);
        out.put("latestDroppedBreadcrumbCount", 0L);
        out.put("latestTriggered", false);
        out.put("latestReason", "unknown");
        out.put("latestRecoveryAction", "wait_for_trace_memory_trace");
        out.put("latestRecoveryRoute", "wait_for_trace_memory_trace");
        out.put("latestRecoveryRouteDecision", "wait_for_trace_memory_trace");
        out.put("latestRecoveryPolicyMaxRounds", 0L);
        out.put("latestRecoveryPolicyMinCitations", 0L);
        out.put("latestFailureClass", "none");
        out.put("latestQuarantine", false);
        out.put("suspectPayloadIsolated", false);
        out.put("latestRisk", "none");
        out.put("latestCfvmOffered", false);
        out.put("latestCfvmPatternId", 0L);
        out.put("latestSupabaseShadowCount", 0L);
        out.put("latestDebugAction", "wait_for_trace_memory_trace");
        out.put("latestDebugReason", traceLabel(reason, "trace_memory_trace_missing"));
        out.put("latestTraceRoute", LATEST_TRACE_MEMORY_ROUTE);
        out.put("latestCheckpointJsonRoute", LATEST_TRACE_MEMORY_CHECKPOINTS_ROUTE);
        out.put("latestTraceIdHash", "");
        out.put("latestTraceIdLength", 0);
        out.put("latestTraceEntryCount", 0L);
        out.put("latestEventCount", 0L);
        return out;
    }

    private static boolean hasTraceMemoryTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("traceMemory.fingerprint.current")
                || trace.containsKey("traceMemory.triggered")
                || trace.containsKey("traceMemory.checkpoint.stage")
                || trace.containsKey("traceMemory.virtualCheckpoint.latestKey"));
    }

    private static boolean hasTraceMemoryRecoveryTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("traceMemory.recovery.route")
                || trace.containsKey("traceMemory.suspectPayload.isolated"));
    }

    private static boolean suspectPayloadIsolated(Map<String, Object> trace, String recoveryRoute) {
        if (trace == null) {
            return false;
        }
        if (truthy(trace.get("traceMemory.suspectPayload.isolated"))) {
            return true;
        }
        String suspectRoute = traceLabel(trace.get("traceMemory.suspectPayload.route"), "");
        return hasText(suspectRoute) && hasText(recoveryRoute) && suspectRoute.equals(recoveryRoute);
    }

    private static String traceMemoryStatus(boolean triggered, boolean quarantine, String risk) {
        String safeRisk = traceLabel(risk, "none");
        return triggered
                || quarantine
                || "BREAK".equalsIgnoreCase(safeRisk)
                || "WARN".equalsIgnoreCase(safeRisk)
                ? "WARN"
                : "OK";
    }

    private static String traceMemoryDebugAction(boolean triggered,
                                                 boolean quarantine,
                                                 String risk,
                                                 String recoveryAction) {
        if ("WARN".equals(traceMemoryStatus(triggered, quarantine, risk))) {
            return "inspect_trace_memory_trace";
        }
        return traceLabel(recoveryAction, "continue_trace_memory_checkpointing");
    }

    private static boolean hasHarmonyTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("chat.harmony.postprocess.decision")
                || trace.containsKey("chat.harmony.postprocess.agentVisible"));
    }

    private static Map<String, Object> queryRewrite(TraceSnapshotStore store,
                                                    List<Map<String, Object>> summaries) {
        Map<String, Object> out = queryRewriteUnavailable(store == null
                ? "trace_snapshot_store_missing"
                : "query_rewrite_trace_missing");
        if (store == null || summaries == null || summaries.isEmpty()) {
            return out;
        }
        for (Map<String, Object> summary : summaries) {
            String id = stringValue(summary.get("id"), "");
            if (!hasText(id)) {
                continue;
            }
            Optional<TraceSnapshotStore.TraceSnapshot> snapshot = store.get(id);
            if (snapshot.isEmpty()) {
                continue;
            }
            Map<String, Object> trace = snapshot.get().trace();
            if (!hasQueryRewriteTrace(trace)) {
                continue;
            }
            boolean enabled = truthy(trace.get("queryTransformer.subQueries.superTokens.enabled"));
            long branchCount = numberValue(trace.get("queryTransformer.subQueries.superTokens.branchCount"));
            long tokenCount = numberValue(trace.get("queryTransformer.subQueries.superTokens.tokenCount"));
            long subModelCount = numberValue(trace.get("queryTransformer.subQueries.superTokens.subModelCount"));
            long axisCount = numberValue(trace.get("queryTransformer.subQueries.superTokens.axisCount"));
            long verificationLaneCount = numberValue(trace.get("web.query.rewrite.verificationLaneCount"));
            long explorationLaneCount = numberValue(trace.get("web.query.rewrite.explorationLaneCount"));
            boolean coverageComplete = truthy(trace.get("queryTransformer.subQueries.superTokens.coverageComplete"));
            List<String> axes = traceLabelList(trace.get("queryTransformer.subQueries.superTokens.axes"), 8);
            List<String> laneLabels = traceLabelList(trace.get("web.query.rewrite.laneLabels"), 8);
            List<String> variantLaneTemperatureHints = variantLaneTemperatureHintList(firstTraceValue(trace,
                    "web.query.rewrite.variantLaneTemperatureHints",
                    "web.rewritePlan.variantLaneTemperatureHints"), 8);

            out.put("status", enabled || branchCount > 0 || verificationLaneCount > 0 || explorationLaneCount > 0
                    ? "OK"
                    : "WARN");
            out.put("reason", "latest_query_rewrite_trace");
            out.put("source", "traceSnapshotStore");
            out.put("enabled", enabled);
            out.put("branchCount", branchCount);
            out.put("tokenCount", tokenCount);
            out.put("subModelCount", subModelCount);
            out.put("axisCount", axisCount);
            out.put("axes", axes);
            out.put("axisSummary", String.join(",", axes));
            out.put("coverageComplete", coverageComplete);
            out.put("branchQueryCoverageComplete",
                    truthy(trace.get("queryTransformer.subQueries.superTokens.branchQueryCoverageComplete")));
            out.put("branchTitleCoverageComplete",
                    truthy(trace.get("queryTransformer.subQueries.superTokens.branchTitleCoverageComplete")));
            out.put("verificationLaneCount", verificationLaneCount);
            out.put("explorationLaneCount", explorationLaneCount);
            out.put("laneLabels", laneLabels);
            out.put("laneSummary", laneLabels.isEmpty()
                    ? traceLabel(firstTraceValue(trace,
                    "web.query.rewrite.laneSummary", "web.rewritePlan.laneSummary"), "none")
                    : String.join("|", laneLabels));
            out.put("variantLaneTemperatureHints", variantLaneTemperatureHints);
            out.put("temperatureProfile", traceLabel(firstTraceValue(trace,
                    "web.query.rewrite.temperatureProfile",
                    "web.query.rewrite.requestedTemperatureProfile"), "unknown"));
            out.put("validationTemperature", doubleValue(firstTraceValue(trace,
                    "web.query.rewrite.validationTemperature",
                    "web.query.rewrite.requestedValidationTemperature")));
            out.put("explorationTemperature", doubleValue(firstTraceValue(trace,
                    "web.query.rewrite.explorationTemperature",
                    "web.query.rewrite.requestedExplorationTemperature")));
            out.put("explorationRate", doubleValue(firstTraceValue(trace,
                    "web.query.rewrite.explorationRate",
                    "web.query.rewrite.requestedExplorationRate")));
            out.put("latestTraceIdHash", stringValue(SafeRedactor.hashValue(id), ""));
            out.put("latestTraceEntryCount", numberValue(summary.getOrDefault(
                    "traceEntryCount", snapshot.get().traceEntryCount())));
            out.put("latestEventCount", numberValue(summary.get("eventCount")));
            return out;
        }
        return out;
    }

    private static Map<String, Object> queryRewriteUnavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "WARN");
        out.put("reason", reason);
        out.put("source", "traceSnapshotStore");
        out.put("enabled", false);
        out.put("branchCount", 0L);
        out.put("tokenCount", 0L);
        out.put("subModelCount", 0L);
        out.put("axisCount", 0L);
        out.put("axes", List.of());
        out.put("axisSummary", "none");
        out.put("coverageComplete", false);
        out.put("branchQueryCoverageComplete", false);
        out.put("branchTitleCoverageComplete", false);
        out.put("verificationLaneCount", 0L);
        out.put("explorationLaneCount", 0L);
        out.put("laneLabels", List.of());
        out.put("laneSummary", "none");
        out.put("temperatureProfile", "unknown");
        out.put("validationTemperature", 0.0d);
        out.put("explorationTemperature", 0.0d);
        out.put("explorationRate", 0.0d);
        out.put("variantLaneTemperatureHints", List.of());
        out.put("latestTraceIdHash", "");
        out.put("latestTraceEntryCount", 0L);
        out.put("latestEventCount", 0L);
        return out;
    }

    private static boolean hasQueryRewriteTrace(Map<String, Object> trace) {
        return trace != null
                && (trace.containsKey("queryTransformer.subQueries.superTokens.enabled")
                || trace.containsKey("queryTransformer.subQueries.superTokens.branchCount")
                || trace.containsKey("web.query.rewrite.laneSummary")
                || trace.containsKey("web.query.rewrite.variantLaneTemperatureHints")
                || trace.containsKey("web.rewritePlan.laneSummary"));
    }

    private static List<String> traceLabelList(Object value, int limit) {
        if (!(value instanceof Iterable<?> iterable) || limit <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : iterable) {
            String label = traceLabel(item, "");
            if (hasText(label)) {
                out.add(label);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<String> variantLaneTemperatureHintList(Object value, int limit) {
        if (!(value instanceof Iterable<?> iterable) || limit <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : iterable) {
            String label = variantLaneTemperatureHintLabel(item);
            if (hasText(label)) {
                out.add(label);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String variantLaneTemperatureHintLabel(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.matches("\\d+:[A-Za-z0-9_.:-]+@[0-9]+(?:\\.[0-9]+)?#[A-Fa-f0-9]{12}")) {
            return text;
        }
        return traceLabel(value, "");
    }

    private static String traceLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text);
    }

    private static boolean beanPresent(ObjectProvider<?> provider) {
        try {
            return provider != null && provider.getIfAvailable() != null;
        } catch (RuntimeException ex) {
            traceHeartbeatSuppressed("beanPresent", ex);
            return false;
        }
    }

    private static boolean beanTypePresent(ObjectProvider<ApplicationContext> applicationContextProvider, String className) {
        return beanTypeCount(applicationContextProvider, className) > 0;
    }

    private static int beanTypeCount(ObjectProvider<ApplicationContext> applicationContextProvider, String className) {
        try {
            ApplicationContext applicationContext = applicationContextProvider == null ? null : applicationContextProvider.getIfAvailable();
            if (applicationContext == null) {
                return 0;
            }
            ClassLoader classLoader = ChatUiCoreHeartbeatProbe.class.getClassLoader();
            if (!ClassUtils.isPresent(className, classLoader)) {
                return 0;
            }
            Class<?> type = ClassUtils.resolveClassName(className, classLoader);
            return applicationContext.getBeanNamesForType(type, false, false).length;
        } catch (LinkageError | RuntimeException ex) {
            traceHeartbeatSuppressed("beanTypePresent", ex);
            return 0;
        }
    }

    private static void traceHeartbeatSuppressed(String stage, Throwable ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = ex == null ? "RuntimeException" : ex.getClass().getSimpleName();
        String errorHash = SafeRedactor.hashValue(ex == null ? null : ex.getMessage());
        TraceStore.put("chatUiHeartbeat.suppressed", true);
        TraceStore.put("chatUiHeartbeat.suppressed.stage", safeStage);
        TraceStore.put("chatUiHeartbeat.suppressed." + safeStage, true);
        TraceStore.put("chatUiHeartbeat.suppressed." + safeStage + ".errorType", errorType);
        TraceStore.put("chatUiHeartbeat.suppressed." + safeStage + ".errorHash", errorHash);
        log.debug("[AWX][chat-ui-heartbeat] suppressed stage={} errorType={}", safeStage, errorType);
    }

    private static Map<String, Object> overview(String name, String status, String reason, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("status", status);
        row.put("reason", reason);
        row.put("detail", detail);
        row.put("source", "chat-ui-core-heartbeat");
        return row;
    }

    private static Map<String, Object> lane(String name, boolean enabled, String disabledReason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("status", enabled ? "OK" : "DISABLED");
        row.put("enabled", enabled);
        row.put("disabledReason", enabled ? "" : disabledReason);
        row.put("source", "chat-ui-core-heartbeat");
        return row;
    }

    private static Map<String, Object> goalNextExternal() {
        Path summaryPath = Path.of(GOAL_NEXT_AUTO_SUMMARY_PATH);
        Map<String, Object> row = external("goal-next-auto", "goal_next_auto_summary_missing",
                "run_goal_next_auto", "local-goal-gate");
        if (!Files.isRegularFile(summaryPath)) {
            row.put("summaryFileStatus", "missing");
            return row;
        }
        row.put("summaryFileStatus", "present");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(summaryPath.toFile());
            boolean ok = root.path("ok").asBoolean(false);
            int secretHits = atInt(root, "/secretHits", 0)
                    + atInt(root, "/rawSecretPatternHits", 0);
            String decision = atText(root, "/decision", "evidence_needed");
            String firstAction = atText(root, "/firstAction", "none");
            row.put("status", ok && secretHits == 0 ? "OK" : "WARN");
            row.put("evidenceNeeded", ok ? null : decision);
            row.put("decision", decision);
            row.put("failureClassification", atText(root, "/failureClassification", "evidence_needed"));
            row.put("nextAction", firstAction);
            row.put("firstAction", firstAction);
            row.put("firstActionSource", atText(root, "/firstActionSource", "unknown"));
            row.put("topActions", topActions(root, 3));
            row.put("externalInputGateStatus", atText(root, "/externalInputGate/status", "unknown"));
            row.put("localPatchJustified", atBool(root, "/externalInputGate/localPatchJustified", true));
            row.put("localReady", atBool(root, "/desktopControlLoop/localReady", false));
            row.put("completionReady", atBool(root, "/desktopControlLoop/completionReady", false));
            row.put("sourceHealthExit", atInt(root, "/sourceHealthExit", -1));
            row.put("completionAuditExit", atInt(root, "/completionAuditExit", -1));
            row.put("supabaseEvidenceNeededCount", atInt(root, "/supabaseApply/evidenceNeededCount",
                    atInt(root, "/supabaseSmoke/evidenceNeededCount", 0)));
            row.put("supabaseRequiredEnvNames", stringList(root.at("/supabaseApply/requiredEnvNames"), 4));
            row.put("supabaseRequiredMcpTools", stringList(root.at("/supabaseApply/requiredMcpTools"), 4));
            row.put("browserEvidenceNeeded", atText(root, "/browserUse/evidenceNeeded", "none"));
            row.put("browserStale", atBool(root, "/browserUse/stale", false));
            row.put("computerStale", atBool(root, "/computerUse/stale", false));
            row.put("desktopFinalProof", atText(root, "/desktopControlLoop/desktopFinalProof", "unknown"));
            row.put("secretHits", secretHits);
        } catch (IOException | RuntimeException ex) {
            logFailSoft("goalNextExternal", ex);
            row.put("status", "WARN");
            row.put("summaryFileStatus", "unreadable");
            row.put("evidenceNeeded", "goal_next_auto_summary_unreadable");
            row.put("nextAction", "rerun_goal_next_auto");
        }
        return row;
    }

    private static Map<String, Object> browserUseExternal() {
        Path smokePath = Path.of(BROWSER_UI_SMOKE_PATH);
        Map<String, Object> row = external("browser", "browser_ui_smoke_missing",
                "run_browser_ui_smoke", "local-ui-proof");
        if (!Files.isRegularFile(smokePath)) {
            row.put("smokeFileStatus", "missing");
            return row;
        }
        row.put("smokeFileStatus", "present");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(smokePath.toFile());
            boolean reachable = atBool(root, "/reachable", false);
            boolean localhost = atBool(root, "/localhost", false);
            boolean publicDomain = atBool(root, "/publicDomain", false);
            boolean targetAccepted = atBool(root, "/targetAccepted", false);
            boolean targetContentVisible = atBool(root, "/targetContentVisible", false);
            boolean screenshotCaptured = atBool(root, "/screenshotCaptured", false);
            long ageMinutes = smokeAgeMinutes(root);
            int staleAfterMinutes = smokeStaleAfterMinutes(root);
            boolean stale = smokeStale(root);
            String evidenceNeeded = atText(root, "/evidenceNeeded", "none");
            int secretHits = atInt(root, "/secretHits", 0)
                    + atInt(root, "/rawSecretPatternHits", 0);
            boolean ok = reachable && targetAccepted && targetContentVisible
                    && !stale && secretHits == 0 && isNone(evidenceNeeded);
            String browserSurface = atText(root, "/browserSurface", "iab");

            row.put("status", ok ? "OK" : "WARN");
            row.put("evidenceScope", browserSurface);
            row.put("browserSurface", browserSurface);
            row.put("reachable", reachable);
            row.put("localhost", localhost);
            row.put("publicDomain", publicDomain);
            row.put("targetAccepted", targetAccepted);
            row.put("targetContentVisible", targetContentVisible);
            row.put("screenshotCaptured", screenshotCaptured);
            row.put("statusClass", atText(root, "/statusClass", "unknown"));
            row.put("stale", stale);
            row.put("ageMinutes", ageMinutes);
            row.put("staleAfterMinutes", staleAfterMinutes);
            row.put("evidenceNeeded", ok ? null : (stale
                    ? "browser_ui_smoke_stale"
                    : (!isNone(evidenceNeeded) ? evidenceNeeded : "browser_ui_smoke_not_ok")));
            row.put("nextAction", ok
                    ? "browser_ui_smoke_current"
                    : (stale ? "run_browser_local_ui_smoke" : atText(root, "/nextAction", "run_browser_ui_smoke")));
            row.put("secretHits", secretHits);
        } catch (IOException | RuntimeException ex) {
            logFailSoft("browserUseExternal", ex);
            row.put("status", "WARN");
            row.put("smokeFileStatus", "unreadable");
            row.put("evidenceNeeded", "browser_ui_smoke_unreadable");
            row.put("nextAction", "run_browser_ui_smoke");
        }
        return row;
    }

    private static Map<String, Object> supabaseExternal() {
        boolean projectRefEnvPresent = hasText(System.getenv("SUPABASE_PROJECT_REF"));
        boolean authEnvPresent = hasText(System.getenv("SUPABASE_ACCESS_TOKEN"));
        boolean mcpConfigured = Files.isRegularFile(Path.of(".mcp.json"));
        boolean probeFilePresent = Files.isRegularFile(Path.of("data", "db-gap-report", "supabase-context-probe.json"));
        String evidenceNeeded = projectRefEnvPresent && authEnvPresent && mcpConfigured
                ? "supabase_live_probe_unverified"
                : "supabase_project_scope_or_auth_unverified";
        Map<String, Object> row = external("supabase", evidenceNeeded,
                "run_readonly_supabase_context_probe", "read-only");
        row.put("projectRefEnvStatus", projectRefEnvPresent ? "present" : "missing");
        row.put("authEnvStatus", authEnvPresent ? "present" : "missing");
        row.put("mcpConfigStatus", mcpConfigured ? "configured" : "missing");
        row.put("probeFileStatus", probeFilePresent ? "present" : "missing");
        return row;
    }

    private static Map<String, Object> computerUseExternal() {
        Path smokePath = Path.of(COMPUTER_USE_SMOKE_PATH);
        Map<String, Object> row = external("computer-use", "computer_use_smoke_missing",
                "run_computer_use_lightweight_smoke", "gui-supporting-only");
        if (!Files.isRegularFile(smokePath)) {
            row.put("smokeFileStatus", "missing");
            return row;
        }
        row.put("smokeFileStatus", "present");
        try {
            JsonNode root = OBJECT_MAPPER.readTree(smokePath.toFile());
            boolean ok = root.path("ok").asBoolean(false);
            int appCount = Math.max(0, root.path("appCount").asInt(0));
            int runningCount = Math.max(0, root.path("runningCount").asInt(0));
            int targetableWindowCount = Math.max(0, firstInt(root, "targetableWindowCount", "windowCount"));
            boolean countEvidence = appCount > 0 || runningCount > 0 || targetableWindowCount > 0;
            boolean reachable = booleanField(root, "reachable", ok && countEvidence);
            boolean guiOnly = booleanField(root, "guiOnly", ok && countEvidence);
            boolean noTerminalAutomation = booleanField(root, "noTerminalAutomation", ok && countEvidence);
            boolean supportingOnly = booleanField(root, "supportingOnly", ok && countEvidence);
            long ageMinutes = smokeAgeMinutes(root);
            int staleAfterMinutes = smokeStaleAfterMinutes(root);
            boolean stale = smokeStale(root);
            boolean storesRawAppNames = root.path("storesRawAppNames").asBoolean(false);
            boolean storesAppNames = root.path("storesAppNames").asBoolean(storesRawAppNames);
            boolean storesWindowTitles = root.path("storesWindowTitles").asBoolean(false);
            boolean countOnly = !storesAppNames && !storesRawAppNames && !storesWindowTitles;
            int secretHits = countValue(root.path("secretHits"))
                    + countValue(root.path("rawSecretPatternHits"));
            String evidenceNeeded = computerUseEvidenceNeeded(ok, reachable, guiOnly, noTerminalAutomation,
                    supportingOnly, stale, countOnly, secretHits);
            row.put("status", evidenceNeeded == null ? "OK" : "WARN");
            row.put("reachable", reachable);
            row.put("guiOnly", guiOnly);
            row.put("noTerminalAutomation", noTerminalAutomation);
            row.put("supportingOnly", supportingOnly);
            row.put("stale", stale);
            row.put("ageMinutes", ageMinutes);
            row.put("staleAfterMinutes", staleAfterMinutes);
            row.put("countOnly", countOnly);
            row.put("secretHits", secretHits);
            row.put("appCount", appCount);
            row.put("runningCount", runningCount);
            row.put("targetableWindowCount", targetableWindowCount);
            row.put("evidenceNeeded", evidenceNeeded);
            row.put("nextAction", evidenceNeeded == null
                    ? "computer_use_supporting_evidence_current"
                    : "run_computer_use_lightweight_smoke");
        } catch (IOException | RuntimeException ex) {
            logFailSoft("computerUseExternal", ex);
            row.put("status", "WARN");
            row.put("smokeFileStatus", "unreadable");
            row.put("evidenceNeeded", "computer_use_smoke_unreadable");
            row.put("nextAction", "run_computer_use_lightweight_smoke");
        }
        return row;
    }

    static List<Map<String, Object>> topActions(JsonNode root, int limit) {
        JsonNode node = root == null ? null : root.at("/topActions");
        if (node == null || !node.isArray() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (out.size() >= limit) {
                break;
            }
            String action = atText(item, "/action", "");
            if (!hasText(action)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", atText(item, "/source", "unknown"));
            row.put("action", action);
            row.put("decision", atText(item, "/decision", "unknown"));
            out.add(row);
        }
        return out;
    }

    private static String computerUseEvidenceNeeded(boolean ok,
                                                    boolean reachable,
                                                    boolean guiOnly,
                                                    boolean noTerminalAutomation,
                                                    boolean supportingOnly,
                                                    boolean stale,
                                                    boolean countOnly,
                                                    int secretHits) {
        if (secretHits > 0) {
            return "computer_use_secret_pattern_hits";
        }
        if (!ok) {
            return "computer_use_smoke_not_ok";
        }
        if (!reachable) {
            return "computer_use_unreachable";
        }
        if (!guiOnly || !noTerminalAutomation || !supportingOnly) {
            return "computer_use_boundary_incomplete";
        }
        if (!countOnly) {
            return "computer_use_privacy_boundary_incomplete";
        }
        if (stale) {
            return "computer_use_smoke_stale";
        }
        return null;
    }

    static boolean smokeStale(JsonNode root) {
        return atBool(root, "/stale", false) || generatedAtStale(root);
    }

    static long smokeAgeMinutes(JsonNode root) {
        String generatedAt = atText(root, "/generatedAt", "");
        if (!hasText(generatedAt)) {
            return -1;
        }
        try {
            long minutes = Duration.between(Instant.parse(generatedAt), Instant.now()).toMinutes();
            return Math.max(0L, minutes);
        } catch (DateTimeParseException ex) {
            logFailSoft("smokeAgeMinutes", ex);
            return -1;
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][chat-ui][heartbeat] failSoft stage={} errorType={}", stage, errorType);
        }
    }

    private static boolean generatedAtStale(JsonNode root) {
        int staleAfterMinutes = smokeStaleAfterMinutes(root);
        long ageMinutes = smokeAgeMinutes(root);
        return staleAfterMinutes > 0 && ageMinutes >= staleAfterMinutes;
    }

    private static int smokeStaleAfterMinutes(JsonNode root) {
        int configured = Math.max(0, atInt(root, "/staleAfterMinutes", 0));
        if (configured > 0) {
            return configured;
        }
        return hasText(atText(root, "/generatedAt", ""))
                ? DEFAULT_EXTERNAL_SMOKE_STALE_AFTER_MINUTES
                : 0;
    }

    private static boolean booleanField(JsonNode root, String field, boolean fallback) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    private static int firstInt(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull()) {
                return node.asInt(0);
            }
        }
        return 0;
    }

    private static int countValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        return Math.max(0, node.asInt(0));
    }

    private static String atText(JsonNode root, String pointer, String fallback) {
        JsonNode node = root == null ? null : root.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText(fallback);
        return hasText(value) ? value : fallback;
    }

    private static int atInt(JsonNode root, String pointer, int fallback) {
        JsonNode node = root == null ? null : root.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    private static boolean atBool(JsonNode root, String pointer, boolean fallback) {
        JsonNode node = root == null ? null : root.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.asBoolean(fallback);
    }

    private static List<String> stringList(JsonNode node, int limit) {
        if (node == null || !node.isArray() || limit <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (out.size() >= limit) {
                break;
            }
            String value = item.asText("");
            if (hasText(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private static Map<String, Object> external(String service,
                                                String evidenceNeeded,
                                                String nextAction,
                                                String evidenceScope) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("status", "WARN");
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("evidenceScope", evidenceScope);
        row.put("evidenceNeeded", evidenceNeeded);
        row.put("nextAction", nextAction);
        return row;
    }

    private static String present(boolean value) {
        return value ? "present" : "missing";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isNone(String value) {
        if (!hasText(value)) {
            return true;
        }
        String normalized = value.trim();
        return "none".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized);
    }
}
