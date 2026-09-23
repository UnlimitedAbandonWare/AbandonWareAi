package com.example.lms.agent.context;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricSnapshot;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.debug.ai.DebugAiRawTile;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.transform.QueryTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

@RestController
@RequestMapping("/agent/db-context")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnBean(AgentDbContextProvider.class)
@ConditionalOnProperty(prefix = "agent.db-context", name = "enabled", havingValue = "true")
public class AgentPipelineHealthController {

    private static final String OK = "OK";
    private static final String WARN = "WARN";
    private static final String DISABLED = "DISABLED";
    private static final String DB_CONTEXT_UNAVAILABLE = "__DB_CONTEXT_UNAVAILABLE__";
    private static final String PUBLIC_BROWSER_HOST = "abandonwareai.kro.kr";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExternalEvidenceFreshnessPolicy EXTERNAL_EVIDENCE_FRESHNESS =
            new ExternalEvidenceFreshnessPolicy();
    private static final List<String> PROVIDER_RUNTIME_NAMES = List.of(
            "naver", "brave", "serpapi", "tavily", "hybrid", "analyze");
    private static final int MAX_PROVIDER_ATTEMPTS = 1_000;
    private static final long MAX_PROVIDER_LATENCY_MS = 600_000L;

    private final AgentDbContextProvider dbContextProvider;

    @Autowired(required = false)
    @Qualifier("analyzeWebSearchRetriever")
    private ContentRetriever webRetriever;
    @Autowired(required = false)
    @Qualifier("vectorRetriever")
    private ContentRetriever vectorRetriever;
    @Autowired(required = false)
    @Qualifier("knowledgeGraphHandler")
    private ContentRetriever kgRetriever;
    @Autowired(required = false)
    @Qualifier("onnxCrossEncoderReranker")
    private Object onnxReranker;
    @Autowired(required = false)
    private QueryTransformer queryTransformer;
    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;
    @Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;
    @Autowired(required = false)
    private AgentDbContextPromptInjector promptInjector;
    @Autowired(required = false)
    private DebugEventStore debugEventStore;
    @Autowired(required = false)
    private DebugAiMetricsService debugAiMetricsService;
    @Autowired(required = false)
    private LocalLlmSmokeHistoryDiagnosticsService localLlmSmokeHistoryDiagnosticsService;
    @Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    @Autowired(required = false)
    private ProviderCredentialResolver providerCredentialResolver;
    @Value("${gpt-search.brave.enabled:${search.brave.enabled:true}}")
    private boolean braveConfigEnabled = true;
    @Value("${gemini.gateway.enabled:true}")
    private boolean geminiGatewayEnabled = true;
    @Value("${gemini.gateway.purpose.search-expansion.enabled:true}")
    private boolean geminiSearchExpansionEnabled = true;
    @Value("${gemini.gateway.models.search-expansion:${gemini.gateway.models.default:gemini-2.5-flash}}")
    private String geminiSearchExpansionModel = "gemini-2.5-flash";
    @Value("${naver.keys:}")
    private String naverKeys;
    @Value("${naver.client-id:}")
    private String naverClientId;
    @Value("${naver.client-secret:}")
    private String naverClientSecret;
    @Value("${gpt-search.brave.api-key:${BRAVE_API_KEY:${GPT_SEARCH_BRAVE_API_KEY:}}}")
    private String braveKey;
    @Value("${gpt-search.serpapi.api-key:${search.serpapi.api-key:${GPT_SEARCH_SERPAPI_API_KEY:${SERPAPI_API_KEY:}}}}")
    private String serpApiKey;
    @Value("${tavily.api.key:${TAVILY_API_KEY:}}")
    private String tavilyKey;
    @Value("${onnx.model.path:}")
    private String onnxModelPath;
    @Value("${SUPABASE_PROJECT_REF:}")
    private String supabaseProjectRef;
    @Value("${SUPABASE_ACCESS_TOKEN:}")
    private String supabaseAccessToken;
    @Value("${awx.computer-use.smoke.path:var/codex-smoke/computer-use-smoke.json}")
    private String computerUseSmokePath;
    @Value("${awx.browser.smoke.path:var/codex-smoke/browser-ui-smoke.json}")
    private String browserSmokePath;
    @Value("${app.public-base-url:${APP_PUBLIC_BASE_URL:https://abandonwareai.kro.kr}}")
    private String appPublicBaseUrl;
    @Value("${awx.goal-next.summary.path:var/codex-smoke/goal-next-auto/goal-next-auto.summary.json}")
    private String goalNextAutoSummaryPath;
    @Value("${awx.noether.status.path:var/codex-smoke/noether-subagent-status.json}")
    private String noetherStatusPath;
    @Value("${awx.patchdrop.root:__patch_drop__}")
    private String patchDropRoot;

    @GetMapping("/pipeline-health")
    public Map<String, Object> pipelineHealth() {
        AgentDbContextProvider.MemorySnapshot memory = safeMemorySnapshot();
        AgentDbContextProvider.StrategySnapshot strategy = safeStrategySnapshot();
        AgentDbContextProvider.LedgerSnapshot ledger = safeLedgerSnapshot();
        Map<String, Object> memoryGate = memoryGate(memory);
        List<Map<String, Object>> laneStates = lanes();
        Map<String, Object> debugEventHealth = debugEventHealth();
        Map<String, Object> debugAiMetrics = debugAiMetrics();
        Map<String, Object> modelRuntime = modelRuntime();
        Map<String, Object> answerOutput = answerOutput();
        Map<String, Object> traceSnapshotHealth = traceSnapshotHealth();
        List<Map<String, Object>> providerStates = webProviders();
        List<Map<String, Object>> providerStatus = providerStatus();
        Map<String, Object> providerRuntime = providerRuntime();
        Map<String, Object> failSoftLadder = failSoftLadder();
        List<Map<String, Object>> externalEvidence = externalEvidence();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capturedAt", LocalDateTime.now().toString());
        out.put("memoryGate", memoryGate);
        out.put("debugOverview", debugOverview(memoryGate, laneStates, debugEventHealth, debugAiMetrics,
                modelRuntime, answerOutput, traceSnapshotHealth, providerStates, providerRuntime, failSoftLadder,
                externalEvidence));
        out.put("lanes", laneStates);
        out.put("debugEventHealth", debugEventHealth);
        out.put("debugAiMetrics", debugAiMetrics);
        out.put("modelRuntime", modelRuntime);
        out.put("answerOutput", answerOutput);
        out.put("traceSnapshotHealth", traceSnapshotHealth);
        out.put("webProviders", providerStates);
        out.put("providerStatus", providerStatus);
        out.put("providerRuntime", providerRuntime);
        out.put("failSoftLadder", failSoftLadder);
        out.put("externalEvidence", externalEvidence);
        out.put("strategyPerformances", strategy == null ? List.of() : strategy.performances);
        out.put("hotspotDistribution", ledger == null ? List.of() : ledger.hotspotDistribution);
        out.put("recentFailures", ledger == null ? List.of() : ledger.recentFailures);
        return out;
    }

    private Map<String, Object> memoryGate(AgentDbContextProvider.MemorySnapshot memory) {
        Map<String, Long> counts = memory == null || memory.statusCounts == null
                ? Map.of()
                : memory.statusCounts;
        if (counts.containsKey(DB_CONTEXT_UNAVAILABLE)) {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("status", DISABLED);
            gate.put("reason", "db_context_unavailable");
            gate.put("active", 0L);
            gate.put("pending", 0L);
            gate.put("quarantined", 0L);
            gate.put("stale", 0L);
            gate.put("total", 0L);
            gate.put("quarantineRatio", 0.0d);
            gate.put("statusCounts", Map.of());
            return gate;
        }
        long active = count(counts, "ACTIVE");
        long pending = count(counts, "PENDING");
        long quarantined = count(counts, "QUARANTINED");
        long stale = count(counts, "STALE");
        long total = counts.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> Math.max(0L, value))
                .sum();
        double quarantineRatio = total == 0L ? 0.0d : (double) quarantined / (double) total;

        String status = OK;
        String reason = "healthy";
        if (active == 0L) {
            status = DISABLED;
            reason = "no_active_memory";
        } else if (quarantineRatio > 0.3d) {
            status = WARN;
            reason = "quarantine_ratio_" + Math.round(quarantineRatio * 100.0d) + "_pct";
        } else if (pending > active) {
            status = WARN;
            reason = "pending_backlog";
        }

        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("status", status);
        gate.put("reason", reason);
        gate.put("active", active);
        gate.put("pending", pending);
        gate.put("quarantined", quarantined);
        gate.put("stale", stale);
        gate.put("total", total);
        gate.put("quarantineRatio", quarantineRatio);
        gate.put("statusCounts", new LinkedHashMap<>(counts));
        return gate;
    }

    private List<Map<String, Object>> lanes() {
        boolean webKeyPresent = effectiveCredentialEnabled(
                ProviderCredentialResolver.Provider.NAVER, !"missing".equals(naverKeySource()))
                || effectiveCredentialEnabled(
                        ProviderCredentialResolver.Provider.BRAVE, !isBlankOrPlaceholder(braveKey))
                || effectiveCredentialEnabled(
                        ProviderCredentialResolver.Provider.SERPAPI, !isBlankOrPlaceholder(serpApiKey))
                || effectiveCredentialEnabled(
                        ProviderCredentialResolver.Provider.TAVILY, !isBlankOrPlaceholder(tavilyKey));
        return List.of(
                webLane(webKeyPresent),
                lane("vectorSearch", vectorRetriever != null, "bean_missing"),
                lane("kgSearch", kgRetriever != null, "bean_missing"),
                onnxLane(),
                queryTransformerLane(),
                causalProbeLane(),
                lane("dbContextInjector", promptInjector != null, "agent.db-context.enabled=false"),
                circuitBreakerLane());
    }

    private List<Map<String, Object>> webProviders() {
        return List.of(
                providerState("naver", ProviderCredentialResolver.Provider.NAVER,
                        naverKeySource(), "missing_naver_key"),
                providerState("brave", ProviderCredentialResolver.Provider.BRAVE,
                        isBlankOrPlaceholder(braveKey) ? "missing" : "brave.api-key", "missing_brave_api_key"),
                providerState("serpapi", ProviderCredentialResolver.Provider.SERPAPI,
                        isBlankOrPlaceholder(serpApiKey) ? "missing" : "serpapi.api-key", "missing_serpapi_api_key"),
                providerState("tavily", ProviderCredentialResolver.Provider.TAVILY,
                        isBlankOrPlaceholder(tavilyKey) ? "missing" : "tavily.api-key", "missing_tavily_api_key"));
    }

    private List<Map<String, Object>> externalEvidence() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                supabaseEvidence(), computerUseEvidence(), browserEvidence(), patchDropEvidence(),
                ExternalAgentEvidenceReader.goalNextAuto(goalNextAutoSummaryPath),
                ExternalAgentEvidenceReader.noether(noetherStatusPath)));
        ExternalAgentEvidenceReader.mergeRequestedLanes(rows, TraceStore.getAll());
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> debugOverview(Map<String, Object> memoryGate,
                                                    List<Map<String, Object>> lanes,
                                                    Map<String, Object> debugEventHealth,
                                                    Map<String, Object> debugAiMetrics,
                                                    Map<String, Object> modelRuntime,
                                                    Map<String, Object> answerOutput,
                                                    Map<String, Object> traceSnapshotHealth,
                                                    List<Map<String, Object>> providers,
                                                    Map<String, Object> providerRuntime,
                                                    Map<String, Object> failSoftLadder,
                                                    List<Map<String, Object>> externalEvidence) {
        long laneWarn = countStatus(lanes, WARN);
        long laneDisabled = countStatus(lanes, DISABLED);
        String memoryStatus = statusValue(memoryGate.get("status"));
        String coreStatus = worstStatus(memoryStatus, laneWarn > 0 ? WARN : OK, laneDisabled > 0 ? WARN : OK);
        String coreReason = safeTraceLabel(stringValue(memoryGate.get("reason")));
        if (coreReason == null && laneWarn > 0) {
            coreReason = "lane_warn";
        } else if (coreReason == null && laneDisabled > 0) {
            coreReason = "lane_disabled";
        }

        long providerEnabled = countBoolean(providers, "hasKey", true);
        long providerDisabled = Math.max(0L, providers.size() - providerEnabled);
        String providerStatus = providerEnabled == 0L ? DISABLED : (providerDisabled > 0L ? WARN : OK);
        String providerReason = providerEnabled == 0L ? "all_providers_disabled" : (providerDisabled > 0L ? "provider_disabled" : "ready");

        List<Map<String, Object>> blockingExternalEvidence = blockingExternalEvidence(externalEvidence);
        String externalStatus = worstListStatus(blockingExternalEvidence);
        String externalReason = firstString(blockingExternalEvidence, "evidenceNeeded", externalStatus.equals(OK) ? "ready" : "evidence_needed");
        Map<String, Object> causalProbe = lanes.stream().filter(row -> "causalProbe".equals(row.get("name"))).findFirst().orElse(Map.of());
        String modelStatus = statusValue(modelRuntime.get("status"));
        String answerStatus = statusValue(answerOutput.get("status"));
        Map<String, Object> blocker = firstNonOkExternalEvidence(blockingExternalEvidence);
        String blockerReason = firstString(blocker, "evidenceNeeded", "ready");
        String blockerDetail = "service=" + firstNonBlank(safeTraceLabel(stringValue(blocker.get("service"))), "none") + " action=" + firstNonBlank(safeTraceLabel(stringValue(blocker.get("nextAction"))), "none");
        Map<String, Object> browserUi = externalEvidenceRow(externalEvidence, "browser");
        String uiStatus = statusValue(browserUi == null ? null : browserUi.get("status"));
        String uiRollupStatus = isSupportingEvidenceOnly(browserUi) ? OK : uiStatus;
        String uiDetailStatus = supportingEvidenceDetailStatus(browserUi, uiStatus);
        String rollupStatus = worstStatus(coreStatus, modelStatus, answerStatus, providerStatus, externalStatus, uiRollupStatus);
        String rollupReason = firstNonBlank(coreReason, firstString(modelRuntime, "reason", null), firstString(answerOutput, "reason", null), providerReason, externalReason, "browser_session_evidence_needed");
        String uiReason = firstNonBlank(firstString(browserUi, "evidenceNeeded", null), firstString(browserUi, "nextAction", "browser_local_ui_smoke_current"));
        return List.of(
                overviewRow("debugRollup", rollupStatus, rollupReason,
                        "core=" + coreStatus + " model=" + modelStatus + " answer=" + answerStatus
                                + " search=" + providerStatus + " external=" + externalStatus + " ui=" + uiDetailStatus,
                        "pipeline-health"),
                overviewRow("debugBlocker", externalStatus, blockerReason, blockerDetail, "external-evidence"),
                overviewRow("coreRuntime", coreStatus, coreReason == null ? "healthy" : coreReason,
                        "lanes=" + lanes.size() + " warn=" + laneWarn + " disabled=" + laneDisabled,
                        "pipeline-health"),
                overviewRow("debugEventHealth", statusValue(debugEventHealth.get("status")),
                        firstString(debugEventHealth, "reason", "healthy"),
                        "events=" + intOrZero(debugEventHealth.get("recentEventCount")) + " fingerprints=" + intOrZero(debugEventHealth.get("fingerprintCount")),
                        "/api/diagnostics/debug/fingerprints?limit=5"),
                overviewRow("debugAiMetrics", statusValue(debugAiMetrics.get("status")),
                        firstString(debugAiMetrics, "reason", "ready"),
                        "events=" + intOrZero(debugAiMetrics.get("totalEvents"))
                                + " warn=" + intOrZero(debugAiMetrics.get("warnEvents"))
                                + " error=" + intOrZero(debugAiMetrics.get("errorEvents")),
                        "/api/diagnostics/debug/ai/snapshot?limit=80&windowMs=60000"),
                overviewRow("modelRuntime", statusValue(modelRuntime.get("status")),
                        firstString(modelRuntime, "reason", "healthy"),
                        "source=" + firstString(modelRuntime, "source", "none")
                                + " delivery=" + firstString(modelRuntime, "deliveryState", "normal")
                                + " wait=" + firstString(modelRuntime, "defaultWaitCode", "none")
                                + " hits=" + intOrZero(modelRuntime.get("timeoutHits"))
                                + " guard=" + yesNo(modelRuntime.get("modelGuardTriggered"))
                                + " error=" + firstString(modelRuntime, "errorCode", "none"),
                        firstString(modelRuntime, "source", "none")),
                overviewRow("answerOutput", statusValue(answerOutput.get("status")),
                        firstString(answerOutput, "reason", "healthy"),
                        "mode=" + firstString(answerOutput, "answerMode", "none")
                                + " guard=" + yesNo(answerOutput.get("emptyAnswerGuardTriggered"))
                                + " fallback=" + firstString(answerOutput, "emptyAnswerFallback", "none")
                                + " trace=" + yesNo(answerOutput.get("evidenceListTraceInjected"))
                                + " evidenceDocs=" + intOrZero(answerOutput.get("evidenceDocs")),
                        firstString(answerOutput, "source", "none")),
                overviewRow("traceSnapshotHealth", statusValue(traceSnapshotHealth.get("status")),
                        firstString(traceSnapshotHealth, "reason", "unavailable"),
                        "summaries=" + intOrZero(traceSnapshotHealth.get("summaryCount"))
                                + " entries=" + intOrZero(traceSnapshotHealth.get("latestTraceEntryCount")),
                        "/admin/trace-snapshots"),
                overviewRow("webProviders", providerStatus, providerReason,
                        "enabled=" + providerEnabled + " disabled=" + providerDisabled,
                        "provider-credentials"),
                overviewRow("causalProbe", statusValue(causalProbe.get("status")),
                        firstString(causalProbe, "disabledReason", "not_observed"),
                        firstNonBlank(stringValue(causalProbe.get("detail")), "not_observed"),
                        firstNonBlank(stringValue(causalProbe.get("source")), "pipeline-health")),
                overviewRow("providerRuntime", statusValue(providerRuntime.get("status")),
                        firstString(providerRuntime, "reason", "healthy"),
                        "timeouts=" + intOrZero(providerRuntime.get("awaitTimeoutCount"))
                                + " cancels=" + intOrZero(providerRuntime.get("cancelSuppressedCount"))
                                + " cooldowns=" + intOrZero(providerRuntime.get("cooldownSkippedCount")),
                        firstString(providerRuntime, "source", "none")),
                overviewRow("failSoftLadder", statusValue(failSoftLadder.get("status")),
                        firstString(failSoftLadder, "reason", "healthy"),
                        "out=" + intOrZero(failSoftLadder.get("outCount"))
                                + " cache=" + intOrZero(failSoftLadder.get("cacheOnlyMergedCount"))
                                + " tracePool=" + intOrZero(failSoftLadder.get("tracePoolSize")),
                        firstString(failSoftLadder, "source", "none")),
                overviewRow("externalEvidence", externalStatus, externalReason,
                        externalEvidenceDetail(externalEvidence),
                        "external-evidence"),
                overviewRow("uiDebug", statusValue(browserUi.get("status")), uiReason,
                        "surface=" + firstString(browserUi, "browserSurface", "unknown") + " reachable=" + yesNo(browserUi.get("reachable"))
                                + " screenshot=" + yesNo(browserUi.get("screenshotCaptured")) + " target=" + yesNo(browserUi.get("targetContentVisible")),
                        firstString(browserUi, "evidenceScope", "local-ui-proof")));
    }

    private Map<String, Object> modelRuntime() {
        Map<String, Object> trace = TraceStore.getAll();
        String source = "current_trace";
        if (!hasModelRuntimeSignal(trace)) {
            trace = latestModelRuntimeTraceSnapshot();
            source = trace.isEmpty() ? "none" : "trace_snapshot";
        }

        boolean waiting = truthy(firstTraceValue(trace,
                "llm.defaultModel.waitStatus", "chat.stream.defaultModel.waitStatus"));
        boolean fastBailTimeout = truthy(traceValue(trace, "llm.fastBailTimeout"));
        boolean modelGuardTriggered = truthy(traceValue(trace, "llm.modelGuard.triggered"));
        String defaultWaitCode = safeTraceLabel(stringValue(firstTraceValue(trace,
                "llm.defaultModel.waitStatus.code", "chat.stream.defaultModel.waitStatus.code")));
        String route = safeTraceLabel(stringValue(traceValue(trace, "llm.defaultModel.route")));
        String errorCode = safeTraceLabel(stringValue(traceValue(trace, "llm.error.code")));
        String answerMode = safeTraceLabel(stringValue(traceValue(trace, "answer.mode")));
        String reason = modelRuntimeReason(fastBailTimeout, waiting, modelGuardTriggered,
                defaultWaitCode, errorCode, answerMode);
        String deliveryState = modelRuntimeDeliveryState(fastBailTimeout, waiting, answerMode);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "healthy".equals(reason) ? OK : WARN);
        row.put("reason", reason);
        row.put("source", source);
        row.put("waiting", waiting);
        row.put("deliveryState", deliveryState);
        row.put("finalDeliveryExpected", "awaiting_default_model".equals(deliveryState));
        row.put("defaultWaitCode", defaultWaitCode);
        row.put("route", route);
        row.put("fastBailTimeout", fastBailTimeout);
        row.put("timeoutHits", intValue(traceValue(trace, "llm.fastBailTimeout.timeoutHits")));
        row.put("attempt", intValue(traceValue(trace, "llm.fastBailTimeout.attempt")));
        row.put("maxAttempts", intValue(traceValue(trace, "llm.fastBailTimeout.maxAttempts")));
        row.put("modelGuardTriggered", modelGuardTriggered);
        row.put("errorCode", errorCode);
        row.put("answerMode", answerMode);

        String observedModel = stringValue(traceValue(trace, "llm.model"));
        if (observedModel != null && !observedModel.isBlank()) {
            row.put("modelHash", SafeRedactor.hashValue(observedModel));
            row.put("modelLength", observedModel.length());
        }
        Map<String, Object> localLlmOperatorAction = localLlmOperatorAction(trace);
        if (localLlmOperatorAction.isEmpty()) {
            Map<String, Object> smokeHistoryOperatorAction = localLlmSmokeHistoryOperatorAction();
            if (!smokeHistoryOperatorAction.isEmpty() && localModelRuntimeRecentlySucceeded()) {
                row.put("status", OK);
                row.put("reason", "recent_local_model_success");
                row.put("source", "modelRuntimeHealth");
                row.put("localLlmSmokeHistoryStale", true);
                row.put("localLlmSmokeHistorySuppressedReason", "recent_local_model_success");
                localLlmOperatorAction = localModelSuccessOperatorAction();
            } else {
                localLlmOperatorAction = smokeHistoryOperatorAction;
            }
        }
        if (!localLlmOperatorAction.isEmpty()) {
            if ("healthy".equals(reason) && !clearedLocalLlmOperatorAction(localLlmOperatorAction)) {
                row.put("status", WARN);
                row.put("reason", "local_llm_operator_action");
            }
            row.put("localLlmOperatorAction", localLlmOperatorAction);
        }
        return row;
    }

    private List<Map<String, Object>> providerStatus() {
        Map<String, Object> trace = new LinkedHashMap<>(latestProviderStatusTraceSnapshot());
        trace.putAll(TraceStore.getAll());

        ProviderCredentialResolver.Resolution brave = safeProviderResolution(
                ProviderCredentialResolver.Provider.BRAVE);
        ProviderCredentialResolver.Resolution naver = safeProviderResolution(
                ProviderCredentialResolver.Provider.NAVER);
        ProviderCredentialResolver.Resolution gemini = safeProviderResolution(
                ProviderCredentialResolver.Provider.GEMINI);

        boolean braveCredentialPresent = brave == null
                ? !isBlankOrPlaceholder(braveKey)
                : brave.credentialPresent();
        boolean naverCredentialPresent = naver == null
                ? !"missing".equals(naverKeySource())
                : naver.credentialPresent();
        boolean geminiCredentialPresent = gemini != null && gemini.credentialPresent();

        return List.of(
                providerStatusRow(trace, "brave", "search", "not_applicable",
                        braveConfigEnabled && (brave == null ? braveCredentialPresent : brave.enabled()),
                        braveCredentialPresent,
                        !braveConfigEnabled
                                ? "disabled_by_config"
                                : brave == null ? "missing-credential" : brave.disabledReason()),
                providerStatusRow(trace, "naver", "search", "not_applicable",
                        naver == null ? naverCredentialPresent : naver.enabled(),
                        naverCredentialPresent,
                        naver == null ? "missing-credential" : naver.disabledReason()),
                providerStatusRow(trace, "gemini", "search-expansion",
                        safeProviderLabel(geminiSearchExpansionModel, "unavailable"),
                        geminiGatewayEnabled && geminiSearchExpansionEnabled
                                && gemini != null && gemini.enabled(),
                        geminiCredentialPresent,
                        geminiFallbackReason(gemini)));
    }

    private Map<String, Object> providerStatusRow(Map<String, Object> trace,
                                                   String provider,
                                                   String configuredRoute,
                                                   String configuredModel,
                                                   boolean configuredEnabled,
                                                   boolean configuredCredentialPresent,
                                                   String configuredFallbackReason) {
        String exactPrefix = "provider.status." + provider + ".";
        String webPrefix = "web." + provider + ".";
        boolean enabled = booleanValue(traceValue(trace, exactPrefix + "enabled"), configuredEnabled)
                && !truthy(traceValue(trace, webPrefix + "providerDisabled"));
        boolean credentialPresent = booleanValue(
                traceValue(trace, exactPrefix + "credentialPresent"), configuredCredentialPresent);
        Object statusCode = providerStatusCode(firstTraceValue(trace,
                exactPrefix + "statusCode", webPrefix + "httpStatus"));
        long latencyMs = boundedProviderLong(firstTraceValue(trace,
                exactPrefix + "latencyMs", webPrefix + "tookMs"), MAX_PROVIDER_LATENCY_MS);
        int attemptCount = (int) boundedProviderLong(
                traceValue(trace, exactPrefix + "attemptCount"), MAX_PROVIDER_ATTEMPTS);
        boolean cacheHit = booleanValue(firstTraceValue(trace,
                exactPrefix + "cacheHit", webPrefix + "cacheOnly.hit"), false);
        String quotaDecision = safeProviderLabel(
                traceValue(trace, exactPrefix + "quotaDecision"), null);
        if (quotaDecision == null) {
            quotaDecision = truthy(traceValue(trace, webPrefix + "quota.exhausted"))
                    ? "exhausted"
                    : (trace.containsKey(webPrefix + "quota.remaining") ? "allowed" : "not_observed");
        }
        String fallbackReason = safeProviderLabel(firstTraceValue(trace,
                exactPrefix + "fallbackReason",
                webPrefix + "disabledReasonCanonical",
                webPrefix + "disabledReason",
                webPrefix + "failureReason",
                webPrefix + "skipped.reason"), null);
        if (fallbackReason == null || fallbackReason.isBlank()) {
            fallbackReason = !enabled
                    ? safeProviderLabel(configuredFallbackReason, "unavailable")
                    : "not_observed";
        }
        String errorClass = safeProviderLabel(firstTraceValue(trace,
                exactPrefix + "errorClass", webPrefix + "exceptionType"), "not_observed");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("route", safeProviderLabel(traceValue(trace, exactPrefix + "route"), configuredRoute));
        row.put("model", safeProviderLabel(traceValue(trace, exactPrefix + "model"), configuredModel));
        row.put("enabled", enabled);
        row.put("credentialPresent", credentialPresent);
        row.put("attemptCount", attemptCount);
        row.put("statusCode", statusCode);
        row.put("latencyMs", latencyMs);
        row.put("cacheHit", cacheHit);
        row.put("quotaDecision", quotaDecision);
        row.put("fallbackReason", fallbackReason);
        row.put("errorClass", errorClass);
        return row;
    }

    private ProviderCredentialResolver.Resolution safeProviderResolution(
            ProviderCredentialResolver.Provider provider) {
        if (providerCredentialResolver == null || provider == null) {
            return null;
        }
        try {
            return providerCredentialResolver.resolve(provider);
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("provider_credential_resolution", ex);
            return null;
        }
    }

    private boolean effectiveCredentialEnabled(
            ProviderCredentialResolver.Provider provider,
            boolean fallbackEnabled) {
        ProviderCredentialResolver.Resolution resolution = safeProviderResolution(provider);
        return resolution == null ? fallbackEnabled : resolution.enabled();
    }

    private String geminiFallbackReason(ProviderCredentialResolver.Resolution resolution) {
        if (!geminiGatewayEnabled) {
            return "gateway-disabled";
        }
        if (!geminiSearchExpansionEnabled) {
            return "purpose-disabled";
        }
        return resolution == null ? "missing-credential" : resolution.disabledReason();
    }

    private Map<String, Object> latestProviderStatusTraceSnapshot() {
        if (traceSnapshotStore == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isPresent() && hasProviderStatusSignal(snapshot.get().trace())) {
                    return snapshot.get().trace();
                }
            }
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("provider_status_snapshot", ex);
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

    private boolean localModelRuntimeRecentlySucceeded() {
        if (OllamaNativeChatModel.hasRecentNativeSuccess(Duration.ofMinutes(30))) {
            return true;
        }
        if (ModelRuntimeHealthTracker.hasRecentLocalSuccess(Duration.ofMinutes(30))) {
            return true;
        }
        if (modelRuntimeHealthTracker == null) {
            return false;
        }
        try {
            for (Map<String, Object> snapshot : modelRuntimeHealthTracker.redactedSnapshots()) {
                String provider = safeTraceLabel(stringValue(snapshot.get("provider")));
                if ("local".equals(provider)
                        && truthy(snapshot.get("lastSuccess"))
                        && intOrZero(snapshot.get("successCount")) > 0) {
                    return true;
                }
            }
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("model_runtime_health_snapshot", ex);
        }
        return false;
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
        return operatorAction != null
                && Boolean.FALSE.equals(operatorAction.get("triggered"))
                && "none".equals(safeTraceLabel(stringValue(operatorAction.get("failureClass"))))
                && "none".equals(safeActionLabel(stringValue(operatorAction.get("nextAction"))));
    }

    private Map<String, Object> localLlmSmokeHistoryOperatorAction() {
        if (localLlmSmokeHistoryDiagnosticsService == null) {
            return Map.of();
        }
        try {
            Map<String, Object> snapshot = localLlmSmokeHistoryDiagnosticsService.snapshot(1);
            Map<String, Object> latest = mapValue(snapshot.get("latest"));
            return localLlmOperatorActionFromMap(mapValue(latest.get("operatorAction")));
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("local_llm_smoke_history", ex);
            return Map.of();
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
        putOperatorAction(out, "nextAction", trace,
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
        putOperatorAction(out, "nextAction", source, "nextAction");
        putOperatorInt(out, "actionScore", source, "actionScore");
        putOperatorInt(out, "scoreDelta", source, "scoreDelta");
        putOperatorInt(out, "negativeSignalCount", source, "negativeSignalCount");
        return out.isEmpty() ? Map.of() : out;
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
        String value = safeTraceLabel(stringValue(firstTraceValue(trace, traceKeys)));
        if (value != null) {
            out.put(outputKey, value);
        }
    }

    private static void putOperatorAction(Map<String, Object> out,
                                          String outputKey,
                                          Map<String, Object> trace,
                                          String... traceKeys) {
        String value = safeActionLabel(stringValue(firstTraceValue(trace, traceKeys)));
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
            out.put(outputKey, intOrZero(value));
        }
    }

    private Map<String, Object> debugEventHealth() {
        Map<String, Object> row = new LinkedHashMap<>();
        if (debugEventStore == null) {
            row.put("status", DISABLED);
            row.put("reason", "debug_event_store_unavailable");
            row.put("available", false);
            row.put("recentEventCount", 0);
            row.put("fingerprintCount", 0);
            return row;
        }
        try {
            List<DebugEvent> events = debugEventStore.list(20);
            List<Map<String, Object>> fingerprints = debugEventStore.listFingerprints(10);
            DebugEvent latest = events == null || events.isEmpty() ? null : events.get(0);
            int recentWarnOrError = 0;
            if (events != null) {
                for (DebugEvent event : events) {
                    if (event != null && isWarnOrError(event.level())) {
                        recentWarnOrError++;
                    }
                }
            }
            int maxWindowCount = 0;
            int totalSuppressed = 0;
            if (fingerprints != null) {
                for (Map<String, Object> fingerprint : fingerprints) {
                    maxWindowCount = Math.max(maxWindowCount, intOrZero(fingerprint.get("windowCount")));
                    totalSuppressed += intOrZero(fingerprint.get("totalSuppressed"));
                }
            }
            String reason = debugEventReason(events, recentWarnOrError, totalSuppressed);
            row.put("status", "recent_warn_or_error".equals(reason) || "suppressed_debug_events".equals(reason) ? WARN : OK);
            row.put("reason", reason);
            row.put("available", true);
            row.put("recentEventCount", events == null ? 0 : events.size());
            row.put("fingerprintCount", fingerprints == null ? 0 : fingerprints.size());
            row.put("recentWarnOrErrorCount", recentWarnOrError);
            row.put("maxWindowCount", maxWindowCount);
            row.put("totalSuppressed", totalSuppressed);
            row.put("latestLevel", latest == null ? null : safeTraceLabel(latest.level().name()));
            row.put("latestProbe", latest == null ? null : safeTraceLabel(latest.probe().name()));
            row.put("latestFingerprintHash", latest == null ? null : SafeRedactor.hashValue(latest.fingerprint()));
            row.put("latestFingerprintLength", latest == null || latest.fingerprint() == null ? 0 : latest.fingerprint().length());
            return row;
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("debug_event_health", ex);
            row.put("status", WARN);
            row.put("reason", "debug_event_lookup_failed");
            row.put("available", true);
            row.put("recentEventCount", 0);
            row.put("fingerprintCount", 0);
            row.put("errorType", safeTraceLabel(ex.getClass().getSimpleName()));
            return row;
        }
    }

    private Map<String, Object> debugAiMetrics() {
        Map<String, Object> row = new LinkedHashMap<>();
        if (debugAiMetricsService == null) {
            row.put("status", DISABLED);
            row.put("reason", "debug_ai_metrics_unavailable");
            row.put("available", false);
            row.put("totalEvents", 0);
            row.put("warnEvents", 0);
            row.put("errorEvents", 0);
            row.put("tileCount", 0);
            return row;
        }
        try {
            DebugAiMetricSnapshot snapshot = debugAiMetricsService.snapshot(80, 60_000L);
            List<DebugAiRawTile> tiles = snapshot == null || snapshot.tiles() == null
                    ? List.of()
                    : snapshot.tiles();
            DebugAiRawTile topTile = topDebugAiTile(tiles);
            String reason = debugAiMetricsReason(snapshot);
            Map<String, Object> scorecard = snapshot == null || snapshot.scorecard() == null
                    ? Map.of()
                    : snapshot.scorecard();
            row.put("status", "ready".equals(reason) || "empty_window".equals(reason) ? OK : WARN);
            row.put("reason", reason);
            row.put("available", true);
            row.put("totalEvents", snapshot == null ? 0 : boundedInt(snapshot.totalEvents()));
            row.put("warnEvents", snapshot == null ? 0 : boundedInt(snapshot.warnEvents()));
            row.put("errorEvents", snapshot == null ? 0 : boundedInt(snapshot.errorEvents()));
            row.put("tileCount", tiles.size());
            row.put("hotspotCount", snapshot == null || snapshot.fingerprintHotspots() == null
                    ? 0
                    : snapshot.fingerprintHotspots().size());
            row.put("recommendationCount", snapshot == null || snapshot.recommendations() == null
                    ? 0
                    : snapshot.recommendations().size());
            row.put("queryRewriteSubModelCount", intOrZero(scorecard.get("queryRewriteSubModelCount")));
            row.put("queryRewriteBranchTitleCount", intOrZero(scorecard.get("queryRewriteBranchTitleCount")));
            row.put("queryRewriteBranchTitleHashCount", intOrZero(scorecard.get("queryRewriteBranchTitleHashCount")));
            row.put("queryRewriteBranchAxisCount", intOrZero(scorecard.get("queryRewriteBranchAxisCount")));
            row.put("queryRewritePaddedCount", intOrZero(scorecard.get("queryRewritePaddedCount")));
            if (scorecard.get("chatUsage") instanceof Map<?, ?> chatUsage) {
                row.put("chatUsage", new LinkedHashMap<>(chatUsage));
            }
            row.put("topTile", topTile == null ? null : safeTraceLabel(topTile.tileName()));
            row.put("topTileStatus", topTile == null ? null : safeTraceLabel(topTile.status()));
            row.put("topFailureClass", topTile == null ? null : safeTraceLabel(topTile.topFailureClass()));
            return row;
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("debug_ai_metrics", ex);
            row.put("status", WARN);
            row.put("reason", "debug_ai_metrics_lookup_failed");
            row.put("available", true);
            row.put("totalEvents", 0);
            row.put("warnEvents", 0);
            row.put("errorEvents", 0);
            row.put("tileCount", 0);
            row.put("errorType", safeTraceLabel(ex.getClass().getSimpleName()));
            return row;
        }
    }

    private Map<String, Object> providerRuntime() {
        Map<String, Object> trace = TraceStore.getAll();
        String source = "current_trace";
        if (!hasProviderRuntimeSignal(trace)) {
            trace = latestProviderRuntimeTraceSnapshot();
            source = trace.isEmpty() ? "none" : "trace_snapshot";
        }

        int awaitTimeoutCount = intOrZero(traceValue(trace, "web.await.events.timeout.count"));
        int cancelSuppressedCount = intOrZero(traceValue(trace, "web.await.cancelSuppressed"));
        int cooldownSkippedCount = intOrZero(traceValue(trace, "web.failsoft.rateLimitBackoff.skipped.cooldown.count"));
        int maxBackoffDelayMs = intOrZero(traceValue(trace, "web.failsoft.rateLimitBackoff.max.delayMs"));
        int maxBackoffRemainingMs = intOrZero(traceValue(trace, "web.failsoft.rateLimitBackoff.max.remainingMs"));
        List<String> cancelledProviders = new ArrayList<>();
        List<String> cooldownProviders = new ArrayList<>();

        for (String provider : PROVIDER_RUNTIME_NAMES) {
            String prefix = "web.failsoft.rateLimitBackoff." + provider;
            boolean cancelled = truthy(traceValue(trace, "web." + provider + ".cancelled"));
            String lastKind = safeTraceLabel(stringValue(traceValue(trace, prefix + ".last.kind")));
            int delayMs = intOrZero(traceValue(trace, prefix + ".last.delayMs"));
            int remainingMs = Math.max(
                    intOrZero(traceValue(trace, prefix + ".remainingMs")),
                    intOrZero(traceValue(trace, prefix + ".last.remainingMs")));
            if (cancelled) {
                cancelledProviders.add(provider);
            }
            if (lastKind != null || delayMs > 0 || remainingMs > 0 || truthy(traceValue(trace, prefix + ".awaitTimeoutApplied"))) {
                cooldownProviders.add(provider);
            }
            maxBackoffDelayMs = Math.max(maxBackoffDelayMs, delayMs);
            maxBackoffRemainingMs = Math.max(maxBackoffRemainingMs, remainingMs);
        }

        cooldownSkippedCount = Math.max(cooldownSkippedCount, cooldownProviders.size());
        String reason = providerRuntimeReason(awaitTimeoutCount, cancelSuppressedCount,
                cancelledProviders, cooldownSkippedCount, maxBackoffRemainingMs);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "healthy".equals(reason) ? OK : WARN);
        row.put("reason", reason);
        row.put("source", source);
        row.put("awaitTimeoutCount", awaitTimeoutCount);
        row.put("cancelSuppressedCount", cancelSuppressedCount);
        row.put("cooldownSkippedCount", cooldownSkippedCount);
        row.put("cancelledProviders", cancelledProviders);
        row.put("cooldownProviders", cooldownProviders);
        row.put("maxBackoffDelayMs", maxBackoffDelayMs);
        row.put("maxBackoffRemainingMs", maxBackoffRemainingMs);
        return row;
    }

    private Map<String, Object> failSoftLadder() {
        Map<String, Object> trace = TraceStore.getAll();
        String source = "current_trace";
        if (!hasFailSoftLadderSignal(trace)) {
            trace = latestFailSoftLadderTraceSnapshot();
            source = trace.isEmpty() ? "none" : "trace_snapshot";
        }

        int outCount = intOrZero(firstTraceValue(trace, "outCount", "web.outCount", "web.failsoft.outCount"));
        int stageSelectedKeyCount = stageSelectedKeyCount(firstTraceValue(trace,
                "stageCountsSelectedFromOut", "web.failsoft.stageCountsSelectedFromOut"));
        int cacheOnlyMergedCount = intOrZero(firstTraceValue(trace,
                "cacheOnly.merged.count", "web.failsoft.cacheOnly.merged.count",
                "web.failsoft.hybridEmptyFallback.cacheOnly.merged.count"));
        int tracePoolSize = intOrZero(firstTraceValue(trace,
                "tracePool.size", "web.failsoft.tracePool.size",
                "web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size"));
        boolean rescueMergeUsed = truthy(firstTraceValue(trace,
                "rescueMerge.used", "web.failsoft.rescueMerge.used",
                "web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used"));
        boolean poolSafeEmpty = truthy(firstTraceValue(trace,
                "poolSafeEmpty", "starvationFallback.poolSafeEmpty",
                "web.failsoft.starvationFallback.poolSafeEmpty"));
        String trigger = safeTraceLabel(stringValue(firstTraceValue(trace,
                "starvationFallback.trigger", "web.failsoft.starvationFallback.trigger")));
        boolean vectorFallbackUsed = truthy(firstTraceValue(trace,
                "vectorFallback.used", "retrieval.vectorFallback.used"));
        String vectorFallbackReason = safeTraceLabel(stringValue(firstTraceValue(trace,
                "vectorFallback.reason", "retrieval.vectorFallback.reason")));
        int vectorFallbackEffectiveTopK = intOrZero(firstTraceValue(trace,
                "vectorFallback.effectiveTopK", "retrieval.vectorFallback.effectiveTopK"));
        int starvationFallbackCount = intOrZero(firstTraceValue(trace,
                "starvationFallback.count", "web.failsoft.starvationFallback.count"));
        int starvationFallbackAdded = intOrZero(firstTraceValue(trace,
                "starvationFallback.added", "web.failsoft.starvationFallback.added"));
        int starvationSafePoolSize = intOrZero(firstTraceValue(trace,
                "starvationFallback.pool.safe.size", "web.failsoft.starvationFallback.pool.safe.size"));
        int starvationDevPoolSize = intOrZero(firstTraceValue(trace,
                "starvationFallback.pool.dev.size", "web.failsoft.starvationFallback.pool.dev.size"));
        String reason = failSoftLadderReason(trigger, rescueMergeUsed, poolSafeEmpty,
                vectorFallbackUsed,
                outCount, cacheOnlyMergedCount, tracePoolSize, stageSelectedKeyCount);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "healthy".equals(reason) ? OK : WARN);
        row.put("reason", reason);
        row.put("source", source);
        row.put("outCount", outCount);
        row.put("stageSelectedKeyCount", stageSelectedKeyCount);
        row.put("cacheOnlyMergedCount", cacheOnlyMergedCount);
        row.put("tracePoolSize", tracePoolSize);
        row.put("rescueMergeUsed", rescueMergeUsed);
        row.put("poolSafeEmpty", poolSafeEmpty);
        row.put("starvationFallbackTrigger", trigger);
        row.put("vectorFallbackUsed", vectorFallbackUsed);
        row.put("vectorFallbackReason", vectorFallbackReason);
        row.put("vectorFallbackEffectiveTopK", vectorFallbackEffectiveTopK);
        row.put("starvationFallbackCount", starvationFallbackCount);
        row.put("starvationFallbackAdded", starvationFallbackAdded);
        row.put("starvationSafePoolSize", starvationSafePoolSize);
        row.put("starvationDevPoolSize", starvationDevPoolSize);
        return row;
    }

    private Map<String, Object> answerOutput() {
        Map<String, Object> trace = TraceStore.getAll();
        String source = "current_trace";
        if (!hasAnswerOutputSignal(trace)) {
            trace = latestAnswerOutputTraceSnapshot();
            source = trace.isEmpty() ? "none" : "trace_snapshot";
        }

        String answerMode = safeTraceLabel(stringValue(traceValue(trace, "answer.mode")));
        boolean emptyAnswerGuardTriggered = truthy(traceValue(trace, "chat.emptyAnswerGuard.triggered"));
        String emptyAnswerFallback = safeTraceLabel(stringValue(traceValue(trace, "chat.emptyAnswerGuard.fallback")));
        int evidenceDocs = intOrZero(traceValue(trace, "chat.emptyAnswerGuard.evidenceDocs"));
        boolean evidenceListTraceInjected = truthy(traceValue(trace, "orch.evidenceList.traceInjected"));
        boolean blankBaseFallback = truthy(traceValue(trace, "orch.evidenceList.traceInjected.blankBaseFallback"));
        int derivedTitleCount = intOrZero(traceValue(trace, "guard.evidenceList.derivedTitle.count"));
        int derivedSnippetCount = intOrZero(traceValue(trace, "guard.evidenceList.derivedSnippet.count"));
        String reason = answerOutputReason(answerMode, emptyAnswerGuardTriggered,
                emptyAnswerFallback, evidenceListTraceInjected, blankBaseFallback);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", "healthy".equals(reason) || "trace_injected".equals(reason) ? OK : WARN);
        row.put("reason", reason);
        row.put("source", source);
        row.put("answerMode", answerMode);
        row.put("emptyAnswerGuardTriggered", emptyAnswerGuardTriggered);
        row.put("emptyAnswerFallback", emptyAnswerFallback);
        row.put("evidenceDocs", evidenceDocs);
        row.put("evidenceListTraceInjected", evidenceListTraceInjected);
        row.put("blankBaseFallback", blankBaseFallback);
        row.put("derivedTitleCount", derivedTitleCount);
        row.put("derivedSnippetCount", derivedSnippetCount);
        return row;
    }

    private Map<String, Object> traceSnapshotHealth() {
        Map<String, Object> row = new LinkedHashMap<>();
        if (traceSnapshotStore == null) {
            row.put("status", DISABLED);
            row.put("reason", "snapshot_store_unavailable");
            row.put("available", false);
            row.put("summaryCount", 0);
            return row;
        }
        try {
            List<Map<String, Object>> summaries = traceSnapshotStore.listSummaries(5);
            int summaryCount = summaries == null ? 0 : summaries.size();
            Map<String, Object> latest = summaryCount == 0 ? Map.of() : summaries.get(0);
            row.put("status", summaryCount == 0 ? WARN : OK);
            row.put("reason", summaryCount == 0 ? "empty_ring" : "ready");
            row.put("available", true);
            row.put("summaryCount", summaryCount);
            row.put("latestReason", safeTraceLabel(stringValue(latest.get("reason"))));
            row.put("latestStatusCode", intValue(latest.get("status")));
            row.put("latestPathHash", safeHashValue(latest.get("pathHash")));
            row.put("latestPathLength", intOrZero(latest.get("pathLength")));
            row.put("latestHasHtml", truthy(latest.get("hasHtml")));
            row.put("latestHtmlTruncated", truthy(latest.get("htmlTruncated")));
            row.put("latestErrorPresent", latest.get("error") != null);
            row.put("latestTraceEntryCount", intOrZero(latest.get("traceEntryCount")));
            row.put("latestEventCount", intOrZero(latest.get("eventCount")));
            row.put("latestControlCount", intOrZero(latest.get("controlCount")));
            return row;
        } catch (RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("trace_snapshot_health", ex);
            row.put("status", WARN);
            row.put("reason", "snapshot_lookup_failed");
            row.put("available", true);
            row.put("summaryCount", 0);
            row.put("errorType", safeTraceLabel(ex.getClass().getSimpleName()));
            return row;
        }
    }

    private Map<String, Object> latestAnswerOutputTraceSnapshot() {
        if (traceSnapshotStore == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isPresent()) {
                    Map<String, Object> trace = snapshot.get().trace();
                    if (hasAnswerOutputSignal(trace)) {
                        return trace;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("[AWX][agent][pipeline] answer output snapshot lookup skipped errorType={}",
                    ex.getClass().getSimpleName());
        }
        return Map.of();
    }

    private static boolean hasAnswerOutputSignal(Map<String, Object> trace) {
        return trace != null && (trace.containsKey("answer.mode")
                || trace.containsKey("chat.emptyAnswerGuard.triggered")
                || trace.containsKey("chat.emptyAnswerGuard.fallback")
                || trace.containsKey("chat.emptyAnswerGuard.evidenceDocs")
                || trace.containsKey("orch.evidenceList.traceInjected")
                || trace.containsKey("orch.evidenceList.traceInjected.blankBaseFallback")
                || trace.containsKey("guard.evidenceList.derivedTitle.count")
                || trace.containsKey("guard.evidenceList.derivedSnippet.count"));
    }

    private Map<String, Object> latestProviderRuntimeTraceSnapshot() {
        if (traceSnapshotStore == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isPresent()) {
                    Map<String, Object> trace = snapshot.get().trace();
                    if (hasProviderRuntimeSignal(trace)) {
                        return trace;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("[AWX][agent][pipeline] provider runtime snapshot lookup skipped errorType={}",
                    ex.getClass().getSimpleName());
        }
        return Map.of();
    }

    private static boolean hasProviderRuntimeSignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        if (trace.containsKey("web.await.events.timeout.count")
                || trace.containsKey("web.await.cancelSuppressed")
                || trace.containsKey("web.failsoft.rateLimitBackoff.skipped.cooldown.count")
                || trace.containsKey("web.failsoft.rateLimitBackoff.max.delayMs")
                || trace.containsKey("web.failsoft.rateLimitBackoff.max.remainingMs")) {
            return true;
        }
        for (String provider : PROVIDER_RUNTIME_NAMES) {
            String prefix = "web.failsoft.rateLimitBackoff." + provider;
            if (trace.containsKey("web." + provider + ".cancelled")
                    || trace.containsKey(prefix + ".last.kind")
                    || trace.containsKey(prefix + ".last.delayMs")
                    || trace.containsKey(prefix + ".remainingMs")
                    || trace.containsKey(prefix + ".last.remainingMs")
                    || trace.containsKey(prefix + ".awaitTimeoutApplied")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> latestFailSoftLadderTraceSnapshot() {
        if (traceSnapshotStore == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isPresent()) {
                    Map<String, Object> trace = snapshot.get().trace();
                    if (hasFailSoftLadderSignal(trace)) {
                        return trace;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("[AWX][agent][pipeline] fail-soft ladder snapshot lookup skipped errorType={}",
                    ex.getClass().getSimpleName());
        }
        return Map.of();
    }

    private static boolean hasFailSoftLadderSignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        return trace.containsKey("outCount")
                || trace.containsKey("web.outCount")
                || trace.containsKey("web.failsoft.outCount")
                || trace.containsKey("stageCountsSelectedFromOut")
                || trace.containsKey("web.failsoft.stageCountsSelectedFromOut")
                || trace.containsKey("cacheOnly.merged.count")
                || trace.containsKey("web.failsoft.cacheOnly.merged.count")
                || trace.containsKey("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count")
                || trace.containsKey("tracePool.size")
                || trace.containsKey("web.failsoft.tracePool.size")
                || trace.containsKey("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size")
                || trace.containsKey("rescueMerge.used")
                || trace.containsKey("web.failsoft.rescueMerge.used")
                || trace.containsKey("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used")
                || trace.containsKey("vectorFallback.used")
                || trace.containsKey("retrieval.vectorFallback.used")
                || trace.containsKey("vectorFallback.reason")
                || trace.containsKey("retrieval.vectorFallback.reason")
                || trace.containsKey("vectorFallback.effectiveTopK")
                || trace.containsKey("retrieval.vectorFallback.effectiveTopK")
                || trace.containsKey("starvationFallback.trigger")
                || trace.containsKey("web.failsoft.starvationFallback.trigger")
                || trace.containsKey("web.failsoft.starvationFallback")
                || trace.containsKey("starvationFallback.count")
                || trace.containsKey("web.failsoft.starvationFallback.count")
                || trace.containsKey("starvationFallback.added")
                || trace.containsKey("web.failsoft.starvationFallback.added")
                || trace.containsKey("starvationFallback.pool.safe.size")
                || trace.containsKey("web.failsoft.starvationFallback.pool.safe.size")
                || trace.containsKey("starvationFallback.pool.dev.size")
                || trace.containsKey("web.failsoft.starvationFallback.pool.dev.size")
                || trace.containsKey("poolSafeEmpty")
                || trace.containsKey("starvationFallback.poolSafeEmpty")
                || trace.containsKey("web.failsoft.starvationFallback.poolSafeEmpty");
    }

    private Map<String, Object> latestModelRuntimeTraceSnapshot() {
        if (traceSnapshotStore == null) {
            return Map.of();
        }
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(5)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isPresent()) {
                    Map<String, Object> trace = snapshot.get().trace();
                    if (hasModelRuntimeSignal(trace)) {
                        return trace;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.debug("[AWX][agent][pipeline] model runtime snapshot lookup skipped errorType={}",
                    ex.getClass().getSimpleName());
        }
        return Map.of();
    }

    private static boolean hasModelRuntimeSignal(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        return trace.containsKey("llm.defaultModel.waitStatus")
                || trace.containsKey("chat.stream.defaultModel.waitStatus")
                || trace.containsKey("llm.fastBailTimeout")
                || trace.containsKey("llm.modelGuard.triggered")
                || trace.containsKey("llm.model.policy.blocked")
                || trace.containsKey("llm.error.code")
                || trace.containsKey("llm.model")
                || trace.containsKey("answer.mode")
                || trace.containsKey("llm.localSmoke.operatorAction.failureClass")
                || trace.containsKey("llm.localSmoke.operatorAction.nextAction")
                || trace.containsKey("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass")
                || trace.containsKey("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction")
                || trace.containsKey("debug.ai.agentDebugEvidence.localLlm.operatorAction.failureClass")
                || trace.containsKey("debug.ai.agentDebugEvidence.localLlm.operatorAction.nextAction");
    }

    private AgentDbContextProvider.MemorySnapshot safeMemorySnapshot() {
        try {
            return dbContextProvider.memorySnapshot();
        } catch (RuntimeException ex) {
            traceSuppressedDbContextFailSoft("memory", ex);
            AgentDbContextProvider.MemorySnapshot snapshot = new AgentDbContextProvider.MemorySnapshot();
            snapshot.statusCounts.put(DB_CONTEXT_UNAVAILABLE, 1L);
            return snapshot;
        }
    }

    private AgentDbContextProvider.StrategySnapshot safeStrategySnapshot() {
        try {
            return dbContextProvider.strategySnapshot();
        } catch (RuntimeException ex) {
            traceSuppressedDbContextFailSoft("strategy", ex);
            return new AgentDbContextProvider.StrategySnapshot();
        }
    }

    private AgentDbContextProvider.LedgerSnapshot safeLedgerSnapshot() {
        try {
            return dbContextProvider.ledgerSnapshot();
        } catch (RuntimeException ex) {
            traceSuppressedDbContextFailSoft("ledger", ex);
            return new AgentDbContextProvider.LedgerSnapshot();
        }
    }

    private static void traceSuppressedDbContextFailSoft(String endpoint, RuntimeException ex) {
        String safeEndpoint = safeTraceLabel(endpoint);
        try {
            TraceStore.put("agent.dbContext." + safeEndpoint + ".failSoft", true);
            TraceStore.put("agent.dbContext." + safeEndpoint + ".reason", "db_context_unavailable");
            TraceStore.put("agent.dbContext." + safeEndpoint + ".errorType",
                    ex == null ? "unknown" : ex.getClass().getSimpleName());
        } catch (Throwable traceFailure) {
            log.debug("[AWX][agent][db-context] pipeline trace failed endpoint={} errorType={}",
                    safeEndpoint,
                    traceFailure == null ? "unknown" : traceFailure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> webLane(boolean webKeyPresent) {
        if (webRetriever == null) {
            return lane("webSearch", false, "bean_missing");
        }
        if (!webKeyPresent) {
            return lane("webSearch", false, "web_provider_no_key");
        }
        return lane("webSearch", true, null);
    }

    private String naverKeySource() {
        if (!isBlankOrPlaceholder(naverKeys)) {
            return "naver.keys";
        }
        if (!isBlankOrPlaceholder(naverClientId) && !isBlankOrPlaceholder(naverClientSecret)) {
            return "naver.client-pair";
        }
        return "missing";
    }

    private Map<String, Object> providerState(
            String provider,
            ProviderCredentialResolver.Provider credentialProvider,
            String fallbackKeySource,
            String missingReason) {
        ProviderCredentialResolver.Resolution resolution = safeProviderResolution(credentialProvider);
        boolean hasKey = resolution == null
                ? !"missing".equals(fallbackKeySource)
                : resolution.enabled();
        String keySource = resolution == null
                ? fallbackKeySource
                : safeProviderLabel(resolution.sourceName(), hasKey ? "unknown" : "none");
        String disabledReason = null;
        if (!hasKey) {
            String resolvedReason = resolution == null ? null : resolution.disabledReason();
            disabledReason = resolvedReason == null || resolvedReason.isBlank()
                    || "missing-credential".equals(resolvedReason)
                            ? missingReason
                            : safeProviderLabel(resolvedReason, missingReason);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("status", hasKey ? OK : DISABLED);
        row.put("hasKey", hasKey);
        row.put("keySource", keySource);
        row.put("disabledReason", disabledReason);
        return row;
    }

    private Map<String, Object> supabaseEvidence() {
        boolean projectScoped = !isBlankOrPlaceholder(supabaseProjectRef);
        boolean authPresent = !isBlankOrPlaceholder(supabaseAccessToken);
        String evidenceNeeded = projectScoped
                ? (authPresent ? null : "auth_missing")
                : "project_ref_missing";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", "supabase");
        row.put("status", evidenceNeeded == null ? OK : WARN);
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("projectScoped", projectScoped);
        row.put("authPresent", authPresent);
        row.put("projectScopeStatus", projectScoped ? "project_ref_present" : "project_ref_missing");
        row.put("authStatus", authPresent ? "auth_present" : "auth_missing");
        row.put("mcpReadOnly", true);
        row.put("mcpFeatureGroups", "database,debugging,docs");
        row.put("mcpEndpointHost", "mcp.supabase.com");
        row.putAll(ExternalAgentEvidenceReader.supabaseDetails(goalNextAutoSummaryPath));
        row.put("evidenceNeeded", evidenceNeeded);
        row.put("nextAction", evidenceNeeded == null
                ? "run_supabase_context_probe"
                : ("project_ref_missing".equals(evidenceNeeded)
                ? "set_SUPABASE_PROJECT_REF"
                : "authenticate_supabase_mcp_or_cli"));
        return row;
    }

    private Map<String, Object> computerUseEvidence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", "computer-use");
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("evidenceScope", "gui-supporting-only");
        Path smokePath = Path.of(firstNonBlank(computerUseSmokePath, "var/codex-smoke/computer-use-smoke.json"));
        if (!Files.isRegularFile(smokePath)) {
            row.put("status", WARN);
            row.put("evidenceNeeded", "computer_use_smoke_missing");
            row.put("nextAction", "run_computer_use_lightweight_smoke");
            return row;
        }
        try {
            JsonNode root = readJson(smokePath);
            boolean ok = root.path("ok").asBoolean(false);
            int appCount = Math.max(0, root.path("appCount").asInt(0));
            int runningCount = Math.max(0, root.path("runningCount").asInt(0));
            int targetableWindowCount = Math.max(0, firstInt(root,
                    "targetableWindowCount", "windowCount"));
            boolean countEvidence = appCount > 0 || runningCount > 0 || targetableWindowCount > 0;
            boolean reachable = booleanField(root, "reachable", ok && countEvidence);
            boolean guiOnly = booleanField(root, "guiOnly", ok && countEvidence);
            boolean noTerminalAutomation = booleanField(root, "noTerminalAutomation", ok && countEvidence);
            boolean supportingOnly = booleanField(root, "supportingOnly", ok && countEvidence);
            ExternalEvidenceFreshnessPolicy.Freshness freshness = EXTERNAL_EVIDENCE_FRESHNESS.evaluate(root);
            int ageMinutes = freshness.ageMinutes();
            int staleAfterMinutes = freshness.staleAfterMinutes();
            boolean stale = freshness.stale();
            boolean storesRawAppNames = root.path("storesRawAppNames").asBoolean(false);
            boolean storesAppNames = root.path("storesAppNames").asBoolean(storesRawAppNames);
            boolean storesWindowTitles = root.path("storesWindowTitles").asBoolean(false);
            String decision = firstNonBlank(safeTraceLabel(root.path("decision").asText("")), "unknown");
            String nextAction = firstNonBlank(safeActionLabel(root.path("nextAction").asText("")), null);
            boolean countOnly = !storesAppNames && !storesRawAppNames && !storesWindowTitles;
            int secretHits = countValue(root.path("secretHits"))
                    + countValue(root.path("rawSecretPatternHits"));
            String evidenceNeeded = computerUseEvidenceNeeded(ok, reachable, guiOnly, noTerminalAutomation,
                    supportingOnly, stale, countOnly, secretHits);
            row.put("status", evidenceNeeded == null ? OK : WARN);
            row.put("reachable", reachable);
            row.put("guiOnly", guiOnly);
            row.put("noTerminalAutomation", noTerminalAutomation);
            row.put("supportingOnly", supportingOnly);
            row.put("stale", stale);
            row.put("decision", decision);
            row.put("generatedAt", generatedAtOrCheckedAt(root));
            row.put("ageMinutes", ageMinutes);
            row.put("staleAfterMinutes", staleAfterMinutes);
            row.put("countOnly", countOnly);
            row.put("storesAppNames", storesAppNames);
            row.put("storesRawAppNames", storesRawAppNames);
            row.put("storesWindowTitles", storesWindowTitles);
            row.put("secretHits", secretHits);
            row.put("appCount", appCount);
            row.put("runningCount", runningCount);
            row.put("targetableWindowCount", targetableWindowCount);
            row.put("evidenceNeeded", evidenceNeeded);
            row.put("nextAction", firstNonBlank(nextAction, evidenceNeeded == null
                    ? "computer_use_supporting_evidence_current"
                    : "run_computer_use_lightweight_smoke"));
            return row;
        } catch (IOException | RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("computer_use_evidence", ex);
            row.put("status", WARN);
            row.put("evidenceNeeded", "computer_use_smoke_unreadable");
            row.put("nextAction", "run_computer_use_lightweight_smoke");
            return row;
        }
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

    private Map<String, Object> browserEvidence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", "browser");
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        row.put("evidenceScope", "local-ui-proof");
        Path smokePath = Path.of(firstNonBlank(browserSmokePath, "var/codex-smoke/browser-ui-smoke.json"));
        if (!Files.isRegularFile(smokePath)) {
            String configuredTargetHost = configuredBrowserTargetHost();
            boolean configuredPublicDomain = PUBLIC_BROWSER_HOST.equals(configuredTargetHost);
            row.put("evidenceScope", browserEvidenceScope(configuredPublicDomain, false));
            row.put("status", WARN);
            row.put("reachable", false);
            row.put("localhost", false);
            row.put("publicDomain", configuredPublicDomain);
            row.put("targetAccepted", configuredPublicDomain);
            row.put("targetHost", configuredTargetHost);
            row.put("screenshotCaptured", false);
            row.put("evidenceNeeded", "browser_ui_smoke_missing");
            row.put("nextAction", browserRunAction(configuredPublicDomain, false));
            return row;
        }
        try {
            JsonNode root = readJson(smokePath);
            boolean proofStripReady = proofStripReady(root);
            boolean ok = booleanField(root, "ok", proofStripReady);
            boolean reachable = booleanField(root, "reachable", proofStripReady);
            boolean localhost = booleanField(root, "localhost", proofStripReady);
            String targetHost = browserTargetHost(root);
            boolean publicDomain = PUBLIC_BROWSER_HOST.equals(targetHost)
                    || booleanField(root, "publicDomain", false)
                    || booleanField(root, "targetPublicDomain", false);
            boolean targetAccepted = localhost || publicDomain;
            boolean screenshotCaptured = booleanField(root, "screenshotCaptured", proofStripReady);
            boolean targetContentVisible = booleanField(root, "targetContentVisible", proofStripReady);
            ExternalEvidenceFreshnessPolicy.Freshness freshness = EXTERNAL_EVIDENCE_FRESHNESS.evaluate(root);
            int ageMinutes = freshness.ageMinutes();
            int staleAfterMinutes = freshness.staleAfterMinutes();
            boolean stale = freshness.stale();
            String statusClass = firstNonBlank(safeTraceLabel(root.path("statusClass").asText("")),
                    proofStripReady ? "proof_strip_visible" : "unknown");
            String errorClass = firstNonBlank(safeTraceLabel(root.path("errorClass").asText("")), "none");
            String browserSurface = firstNonBlank(safeTraceLabel(root.path("browserSurface").asText("")),
                    proofStripReady ? "iab" : "unknown");
            String nextAction = firstNonBlank(safeActionLabel(root.path("nextAction").asText("")), null);
            int secretHits = countValue(root.path("secretHits"))
                    + countValue(root.path("rawSecretPatternHits"));
            boolean publicListenerUnreachable = browserPublicListenerUnreachable(publicDomain, localhost, reachable,
                    statusClass, root.path("evidenceNeeded").asText(""));
            String evidenceNeeded = browserEvidenceNeeded(ok, reachable, targetAccepted, screenshotCaptured, stale,
                    secretHits, root.path("evidenceNeeded").asText(""), publicListenerUnreachable);
            row.put("evidenceScope", browserEvidenceScope(publicDomain, localhost));
            row.put("status", evidenceNeeded == null ? OK : WARN);
            row.put("reachable", reachable);
            row.put("localhost", localhost);
            row.put("publicDomain", publicDomain);
            row.put("targetAccepted", targetAccepted);
            row.put("targetHost", targetHost);
            row.put("screenshotCaptured", screenshotCaptured);
            row.put("statusClass", statusClass);
            row.put("errorClass", errorClass);
            row.put("targetContentVisible", targetContentVisible);
            row.put("browserSurface", browserSurface);
            row.put("generatedAt", generatedAtOrCheckedAt(root));
            row.put("ageMinutes", ageMinutes);
            row.put("staleAfterMinutes", staleAfterMinutes);
            row.put("stale", stale);
            row.put("secretHits", secretHits);
            row.put("evidenceNeeded", evidenceNeeded);
            String recommendedNextAction = evidenceNeeded == null
                    ? browserCurrentAction(publicDomain, localhost)
                    : browserRerunAction(publicDomain, localhost, publicListenerUnreachable);
            row.put("nextAction", publicListenerUnreachable ? recommendedNextAction
                    : firstNonBlank(nextAction, recommendedNextAction));
            return row;
        } catch (IOException | RuntimeException ex) {
            AgentPipelineHealthTrace.traceSuppressed("browser_evidence", ex);
            String configuredTargetHost = configuredBrowserTargetHost();
            boolean configuredPublicDomain = PUBLIC_BROWSER_HOST.equals(configuredTargetHost);
            row.put("evidenceScope", browserEvidenceScope(configuredPublicDomain, false));
            row.put("status", WARN);
            row.put("reachable", false);
            row.put("localhost", false);
            row.put("publicDomain", configuredPublicDomain);
            row.put("targetAccepted", configuredPublicDomain);
            row.put("targetHost", configuredTargetHost);
            row.put("screenshotCaptured", false);
            row.put("evidenceNeeded", "browser_ui_smoke_unreadable");
            row.put("nextAction", browserRerunAction(configuredPublicDomain, false));
            return row;
        }
    }

    private static String browserEvidenceNeeded(boolean ok,
                                                boolean reachable,
                                                boolean targetAccepted,
                                                boolean screenshotCaptured,
                                                boolean stale,
                                                int secretHits,
                                                String reportedEvidenceNeeded,
                                                boolean publicListenerUnreachable) {
        if (secretHits > 0) {
            return "browser_smoke_secret_pattern_hits";
        }
        if (publicListenerUnreachable) {
            return "public-listener-unreachable";
        }
        if (!ok) {
            return firstNonBlank(safeTraceLabel(reportedEvidenceNeeded), "browser_session_evidence_needed");
        }
        if (stale) {
            return "browser_ui_smoke_stale";
        }
        if (!reachable) {
            return "browser_ui_unreachable";
        }
        if (!targetAccepted) {
            return "browser_target_evidence_missing";
        }
        if (!screenshotCaptured) {
            return "browser_screenshot_evidence_missing";
        }
        return null;
    }

    private static String browserCurrentAction(boolean publicDomain, boolean localhost) {
        if (publicDomain && !localhost) {
            return "browser_public_domain_ui_smoke_current";
        }
        return "browser_local_ui_smoke_current";
    }

    private static String browserEvidenceScope(boolean publicDomain, boolean localhost) {
        if (publicDomain && !localhost) {
            return "public-domain-ui-proof";
        }
        return "local-ui-proof";
    }

    private static String browserRunAction(boolean publicDomain, boolean localhost) {
        if (publicDomain && !localhost) {
            return "run_browser_public_domain_ui_smoke";
        }
        return "run_browser_local_ui_smoke";
    }

    private static String browserRerunAction(boolean publicDomain, boolean localhost) {
        return browserRerunAction(publicDomain, localhost, false);
    }

    private static String browserRerunAction(boolean publicDomain, boolean localhost, boolean publicListenerUnreachable) {
        if (publicDomain && !localhost) {
            if (publicListenerUnreachable) {
                return "open_public_80_443_then_rerun_browser_public_domain_ui_smoke";
            }
            return "rerun_browser_public_domain_ui_smoke";
        }
        return "run_browser_local_ui_smoke";
    }

    private static boolean browserPublicListenerUnreachable(boolean publicDomain,
                                                           boolean localhost,
                                                           boolean reachable,
                                                           String statusClass,
                                                           String reportedEvidenceNeeded) {
        if (!publicDomain || localhost || reachable) {
            return false;
        }
        String status = safeTraceLabel(statusClass);
        String evidence = safeTraceLabel(reportedEvidenceNeeded);
        return "public-listener-unreachable".equals(evidence)
                || "connection_refused".equals(status)
                || "tcp_unreachable".equals(status);
    }

    private String configuredBrowserTargetHost() {
        return safeBrowserTargetHost(appPublicBaseUrl);
    }

    private static String browserTargetHost(JsonNode root) {
        if (root == null) {
            return "unknown";
        }
        String directHost = safeBrowserHost(firstNonBlank(
                root.path("targetHost").asText(""),
                root.path("host").asText("")));
        if (!"unknown".equals(directHost)) {
            return directHost;
        }
        String rawUrl = firstNonBlank(
                root.path("targetUrl").asText(""),
                root.path("url").asText(""),
                root.path("rawUrl").asText(""));
        if (rawUrl.isBlank()) {
            return "unknown";
        }
        try {
            return safeBrowserHost(URI.create(rawUrl).getHost());
        } catch (IllegalArgumentException ex) {
            AgentPipelineHealthTrace.traceSuppressed("browser_target_host", ex);
            return "unknown";
        }
    }

    private static String safeBrowserTargetHost(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() != null) {
                return safeBrowserHost(uri.getHost());
            }
        } catch (IllegalArgumentException ex) {
            AgentPipelineHealthTrace.traceSuppressed("browser_configured_host", ex);
        }
        return safeBrowserHost(trimmed);
    }

    private static String safeBrowserHost(String host) {
        if (host == null || host.isBlank()) {
            return "unknown";
        }
        String normalized = host.trim().toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized)) {
            return "localhost";
        }
        if (PUBLIC_BROWSER_HOST.equals(normalized)) {
            return PUBLIC_BROWSER_HOST;
        }
        return "unknown";
    }

    private Map<String, Object> patchDropEvidence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", "patchdrop");
        row.put("readOnly", true);
        row.put("mutationAllowed", false);
        Path root = Path.of(firstNonBlank(patchDropRoot, "__patch_drop__"));
        if (!Files.isDirectory(root)) {
            row.put("status", WARN);
            row.put("activeTopLevelPatchCount", 0);
            row.put("nestedProducerPatchCount", 0);
            row.put("reportOnlyPendingCount", 0);
            row.put("appliedPatchCount", 0);
            row.put("evidenceNeeded", "patchdrop_root_missing");
            row.put("nextAction", "run_patchdrop_janitor_inventory");
            return row;
        }

        int activeTopLevel = countDirectFiles(root, path -> hasFileNameSuffix(path, ".patch"));
        int nestedProducer = countWalkedFiles(root, path -> isNestedPatchDropProducerPatch(root, path));
        int reportOnly = countWalkedFiles(root, path -> isPatchDropReportOnlyManifest(root, path));
        int applied = countDirectFiles(root.resolve("applied"), path -> hasFileNameSuffix(path, ".patch"));
        Map<String, Object> pendingProducer = firstPendingPatchDropProducer(root);

        row.put("status", activeTopLevel > 0 || reportOnly > 0 || nestedProducer > 0 ? WARN : OK);
        row.put("activeTopLevelPatchCount", activeTopLevel);
        row.put("nestedProducerPatchCount", nestedProducer);
        row.put("reportOnlyPendingCount", reportOnly);
        row.put("appliedPatchCount", applied);
        row.putAll(pendingProducer);
        row.put("evidenceNeeded", patchDropEvidenceNeeded(activeTopLevel, nestedProducer, reportOnly));
        row.put("nextAction", patchDropNextAction(activeTopLevel, nestedProducer, reportOnly));
        return row;
    }

    private Map<String, Object> onnxLane() {
        if (onnxReranker == null) {
            return lane("onnx", false, "bean_missing");
        }
        if (isBlankOrPlaceholder(onnxModelPath) || onnxModelPath.contains("your-cross-encoder.onnx")) {
            return lane("onnx", false, "placeholder_or_too_small_model");
        }
        return lane("onnx", true, null);
    }

    private Map<String, Object> queryTransformerLane() {
        if (queryTransformer == null) {
            return lane("queryTransformer", false, "bean_missing");
        }
        if (Boolean.TRUE.equals(TraceStore.get("queryTransformer.bypassed"))) {
            return lane("queryTransformer", WARN, true, "bypassed");
        }
        return lane("queryTransformer", true, null);
    }

    private Map<String, Object> causalProbeLane() {
        Map<String, Object> trace = TraceStore.getAll();
        String source = "current_trace";
        if (!hasProbeRoundSignal(trace)) {
            trace = latestProbeRoundTraceSnapshot(stringValue(traceValue(trace, "sessionId")));
            source = trace.isEmpty() ? "none" : "trace_snapshot";
        }
        if (!hasProbeRoundSignal(trace)) {
            return lane("causalProbe", false, "not_observed");
        }
        boolean evidenceReady = truthy(traceValue(trace, "causalProbe.evidenceReady"));
        String reason = firstNonBlank(safeTraceLabel(stringValue(traceValue(trace, "causalProbe.triggerReason"))),
                evidenceReady ? "axis_agreement" : "observe_only");
        String dominant = firstNonBlank(
                safeTraceLabel(stringValue(firstTraceValue(trace,
                        "orch.probeRound.hypothesis", "causalProbe.dominantFailure"))),
                "none");
        String action = firstNonBlank(safeTraceLabel(stringValue(traceValue(trace, "causalProbe.action"))), "observe_only");
        String where = firstNonBlank(safeActionLabel(stringValue(traceValue(trace, "causalProbe.where"))), "unknown")
                .toLowerCase(java.util.Locale.ROOT);
        double confidence = boundedDouble(firstTraceValue(trace,
                "orch.probeRound.causalConfidence", "causalProbe.confidence"));
        int axisCount = intOrZero(traceValue(trace, "causalProbe.axisCount"));
        String phase = firstNonBlank(
                safeTraceLabel(stringValue(traceValue(trace, "orch.probeRound.phase"))),
                evidenceReady ? "causal_only" : "observed").toUpperCase(java.util.Locale.ROOT);
        String patchCandidate = firstNonBlank(
                safeTraceLabel(stringValue(firstTraceValue(trace,
                        "orch.probeRound.patchCandidate", "causalProbe.patchCandidate"))),
                "none");
        int counterEvidenceCount = intOrZero(firstTraceValue(trace,
                "orch.probeRound.counterEvidenceCount", "counterEvidence.acceptedDocumentCount"));
        double retrievalConfidence = boundedDouble(firstTraceValue(trace,
                "orch.probeRound.retrievalConfidence", "counterEvidence.retrievalConfidenceScore"));
        String coherenceStatus = firstNonBlank(
                safeTraceLabel(stringValue(firstTraceValue(trace,
                        "orch.probeRound.coherenceStatus", "evidenceCoherence.coherenceStatus"))),
                "pending");
        String releaseStatus = firstNonBlank(
                safeTraceLabel(stringValue(firstTraceValue(trace,
                        "orch.probeRound.releaseStatus", "evidenceCoherence.releaseStatus"))),
                "hold");
        String confidenceRange = firstNonBlank(
                safeTraceLabel(stringValue(firstTraceValue(trace,
                        "orch.probeRound.confidenceRange", "evidenceCoherence.confidenceRange"))),
                "pending");
        boolean verificationGatePassed = truthy(firstTraceValue(trace,
                "orch.probeRound.verificationGatePassed", "evidenceCoherence.verificationGatePassed"));
        Map<String, Object> row = lane("causalProbe",
                verificationGatePassed ? OK : (evidenceReady || phaseNotTerminal(phase) ? WARN : OK),
                true, reason);
        row.put("dominantFailure", dominant); row.put("action", action); row.put("confidence", confidence);
        row.put("axisCount", axisCount); row.put("where", where);
        row.put("phase", phase); row.put("patchCandidate", patchCandidate);
        row.put("counterEvidenceCount", counterEvidenceCount);
        row.put("retrievalConfidence", retrievalConfidence);
        row.put("coherenceStatus", coherenceStatus); row.put("releaseStatus", releaseStatus);
        row.put("confidenceRange", confidenceRange);
        row.put("verificationGatePassed", verificationGatePassed);
        row.put("detail", "dominant=" + dominant + " action=" + action + " confidence=" + confidence + " axes=" + axisCount);
        row.put("roundDetail", "phase=" + phase + " hypothesis=" + dominant
                + " evidence=" + counterEvidenceCount + " confidence=" + confidence + "->" + confidenceRange
                + " retrieval=" + retrievalConfidence + " coherence=" + coherenceStatus
                + " release=" + releaseStatus + " gate=" + (verificationGatePassed ? "pass" : "hold"));
        row.put("source", "trace_snapshot".equals(source) ? "trace:probeRound" : "trace:causalProbe");
        return row;
    }

    private Map<String, Object> latestProbeRoundTraceSnapshot(String sessionHash) {
        if (traceSnapshotStore == null || sessionHash == null || sessionHash.isBlank()) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        String targetRoundRef = null;
        try {
            for (Map<String, Object> summary : traceSnapshotStore.listSummaries(50)) {
                Object id = summary == null ? null : summary.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                java.util.Optional<TraceSnapshotStore.TraceSnapshot> snapshot =
                        traceSnapshotStore.get(String.valueOf(id));
                if (snapshot.isEmpty() || !sessionHash.equals(snapshot.get().sessionId())) {
                    continue;
                }
                Map<String, Object> candidate = snapshot.get().trace();
                if (!hasProbeRoundSignal(candidate)) {
                    continue;
                }
                String candidateRoundRef = safeProbeRoundRef(
                        stringValue(traceValue(candidate, "orch.probeRound.roundRef")));
                if (candidateRoundRef == null) {
                    candidate.forEach((key, value) -> {
                        if (isProbeRoundTraceKey(key)) {
                            merged.putIfAbsent(key, value);
                        }
                    });
                    return merged;
                }
                if (targetRoundRef == null) {
                    targetRoundRef = candidateRoundRef;
                }
                if (!targetRoundRef.equals(candidateRoundRef)) {
                    continue;
                }
                candidate.forEach((key, value) -> {
                    if (isProbeRoundTraceKey(key)) {
                        merged.putIfAbsent(key, value);
                    }
                });
            }
        } catch (RuntimeException ex) {
            log.debug("[AWX][agent][pipeline] probe round snapshot lookup skipped errorType={}",
                    ex.getClass().getSimpleName());
            return Map.of();
        }
        return merged;
    }

    private static String safeProbeRoundRef(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("hash:[0-9a-f]{12}") ? normalized : null;
    }

    private static boolean hasProbeRoundSignal(Map<String, Object> trace) {
        return trace != null && trace.keySet().stream().anyMatch(AgentPipelineHealthController::isProbeRoundTraceKey);
    }

    private static boolean isProbeRoundTraceKey(String key) {
        return key != null && (key.startsWith("orch.probeRound.")
                || key.startsWith("causalProbe.")
                || key.startsWith("counterEvidence.")
                || key.startsWith("evidenceCoherence."));
    }

    private static boolean phaseNotTerminal(String phase) {
        return !"VERIFIED".equals(phase) && !"OBSERVED".equals(phase);
    }

    private Map<String, Object> circuitBreakerLane() {
        if (nightmareBreaker == null) {
            Map<String, Object> row = lane("circuitBreaker", OK, true, null);
            row.put("note", "bean_missing_optional");
            return row;
        }
        boolean open = nightmareBreaker.isAnyOpen(
                NightmareKeys.WEBSEARCH_HYBRID,
                NightmareKeys.WEBSEARCH_NAVER,
                NightmareKeys.WEBSEARCH_BRAVE,
                NightmareKeys.WEBSEARCH_SERPAPI,
                NightmareKeys.WEBSEARCH_TAVILY,
                NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                NightmareKeys.DISAMBIGUATION_CLARIFY,
                NightmareKeys.RERANK_ONNX,
                NightmareKeys.RETRIEVAL_VECTOR_POISON)
                || nightmareBreaker.isAnyOpenPrefix(NightmareKeys.CHAT_DRAFT);
        if (open) {
            return lane("circuitBreaker", WARN, true, "breaker_open");
        }
        return lane("circuitBreaker", true, null);
    }

    private static Map<String, Object> lane(String name, boolean ok, String reason) {
        return lane(name, ok ? OK : DISABLED, ok, reason);
    }

    private static Map<String, Object> lane(String name, String status, boolean enabled, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("status", status);
        row.put("enabled", enabled);
        row.put("disabledReason", reason);
        return row;
    }

    private static Map<String, Object> overviewRow(String name,
                                                   String status,
                                                   String reason,
                                                   String detail,
                                                   String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("status", statusValue(status));
        row.put("reason", safeTraceLabel(reason));
        row.put("detail", detail);
        row.put("source", source);
        return row;
    }

    private static long countStatus(List<Map<String, Object>> rows, String status) {
        return rows == null ? 0L : rows.stream()
                .filter(row -> status.equals(statusValue(row == null ? null : row.get("status"))))
                .count();
    }

    private static long countBoolean(List<Map<String, Object>> rows, String key, boolean expected) {
        return rows == null ? 0L : rows.stream()
                .filter(row -> row != null && Boolean.valueOf(expected).equals(row.get(key)))
                .count();
    }

    private static String externalEvidenceDetail(List<Map<String, Object>> rows) {
        int serviceCount = rows == null ? 0 : rows.size();
        return "services=" + serviceCount
                + " warn=" + countStatus(rows, WARN)
                + " supabase=" + externalEvidenceLabel(rows, "supabase", "projectScopeStatus", "evidenceNeeded")
                + " computer=" + externalEvidenceLabel(rows, "computer-use", "evidenceNeeded")
                + " browser=" + externalEvidenceLabel(rows, "browser", "statusClass", "evidenceNeeded")
                + " patchdrop=" + externalEvidenceLabel(rows, "patchdrop", "evidenceNeeded")
                + " goalNext=" + externalEvidenceLabel(rows, "goal-next-auto", "evidenceNeeded", "decision")
                + " noether=" + externalEvidenceLabel(rows, "noether", "evidenceNeeded", "lastMessageKind");
    }

    private static String externalEvidenceLabel(List<Map<String, Object>> rows,
                                                String service,
                                                String... preferredKeys) {
        Map<String, Object> row = externalEvidenceRow(rows, service);
        if (row == null) {
            return "missing";
        }
        if (preferredKeys != null) {
            for (String key : preferredKeys) {
                String value = safeTraceLabel(stringValue(row.get(key)));
                if (value != null) {
                    return value;
                }
            }
        }
        return statusValue(row.get("status")).toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> externalEvidenceRow(List<Map<String, Object>> rows, String service) {
        if (rows == null || service == null) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            if (row != null && service.equals(stringValue(row.get("service")))) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> firstNonOkExternalEvidence(List<Map<String, Object>> rows) {
        return rows == null ? Map.of() : rows.stream()
                .filter(row -> row != null && !OK.equals(statusValue(row.get("status")))).findFirst().orElse(Map.of());
    }

    private static List<Map<String, Object>> blockingExternalEvidence(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row != null && !isSupportingEvidenceOnly(row))
                .toList();
    }

    private static boolean isSupportingEvidenceOnly(Map<String, Object> row) {
        if (row == null) {
            return false;
        }
        String service = stringValue(row.get("service"));
        String scope = safeTraceLabel(stringValue(row.get("evidenceScope")));
        String evidenceNeeded = safeTraceLabel(stringValue(row.get("evidenceNeeded")));
        if ("supabase".equals(service)) {
            return safeTraceLabel(stringValue(row.get("lanePolicy"))) == null
                    && Boolean.TRUE.equals(row.get("readOnly"))
                    && Boolean.FALSE.equals(row.get("mutationAllowed"))
                    && ("project_ref_missing".equals(evidenceNeeded) || "auth_missing".equals(evidenceNeeded));
        }
        if ("patchdrop".equals(service)) {
            return intOrZero(row.get("activeTopLevelPatchCount")) == 0
                    && ("patchdrop_report_only_pending".equals(evidenceNeeded)
                        || "nested_patchdrop_reference_not_apply_candidate".equals(evidenceNeeded));
        }
        if ("computer-use".equals(service)) {
            return true;
        }
        return "browser".equals(service)
                && ("local-ui-proof".equals(scope) || "public-domain-ui-proof".equals(scope));
    }

    private static String supportingEvidenceDetailStatus(Map<String, Object> row, String status) {
        if (isSupportingEvidenceOnly(row) && WARN.equals(statusValue(status))) {
            return "SUPPORTING_EVIDENCE_MISSING";
        }
        return statusValue(status);
    }

    private static String worstListStatus(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return DISABLED;
        }
        String worst = OK;
        for (Map<String, Object> row : rows) {
            worst = worstStatus(worst, statusValue(row == null ? null : row.get("status")));
        }
        return worst;
    }

    private static String worstStatus(String... statuses) {
        String worst = OK;
        if (statuses == null) {
            return worst;
        }
        for (String status : statuses) {
            String normalized = statusValue(status);
            if (DISABLED.equals(normalized)) {
                return DISABLED;
            }
            if (WARN.equals(normalized)) {
                worst = WARN;
            }
        }
        return worst;
    }

    private static String statusValue(Object value) {
        String status = value == null ? "" : String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT);
        return OK.equals(status) || WARN.equals(status) || DISABLED.equals(status) ? status : DISABLED;
    }

    private static String firstString(List<Map<String, Object>> rows, String key, String fallback) {
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                String value = safeTraceLabel(stringValue(row.get(key)));
                if (value != null) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static String firstString(Map<String, Object> row, String key, String fallback) {
        String value = safeTraceLabel(stringValue(row == null ? null : row.get(key)));
        return value == null ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static JsonNode readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return JSON.readTree(text.startsWith("\uFEFF") ? text.substring(1) : text);
    }

    private static int firstInt(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return 0;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.path(fieldName);
            if (value.isNumber()) {
                return value.asInt(0);
            }
        }
        return 0;
    }

    private static boolean booleanField(JsonNode root, String fieldName, boolean fallback) {
        if (root == null || fieldName == null) {
            return fallback;
        }
        JsonNode value = root.get(fieldName);
        return value == null || value.isNull() ? fallback : value.asBoolean(false);
    }

    private static int countValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isArray()) {
            return Math.max(0, node.size());
        }
        if (node.isNumber()) {
            return Math.max(0, node.asInt(0));
        }
        try {
            return Math.max(0, Integer.parseInt(node.asText("").trim()));
        } catch (NumberFormatException ex) {
            return AgentPipelineHealthTrace.traceZero("count_value_parse", ex);
        }
    }

    private static String generatedAtOrCheckedAt(JsonNode root) {
        if (root == null) {
            return "unknown";
        }
        return firstNonBlank(
                safeActionLabel(root.path("generatedAt").asText("")),
                safeActionLabel(root.path("checkedAt").asText("")),
                "unknown");
    }

    private static boolean proofStripReady(JsonNode root) {
        String names = root == null ? "" : firstNonBlank(safeJsonLabelList(root.path("proofNames")),
                root.path("proofNames").asText(""));
        String normalized = "," + names.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "") + ",";
        return root != null
                && root.path("proofRootPresent").asBoolean(false)
                && Math.max(0, root.path("proofCellCount").asInt(0)) >= 6
                && normalized.contains(",local,")
                && normalized.contains(",browser,")
                && normalized.contains(",computer,")
                && normalized.contains(",supabase,")
                && normalized.contains(",producer,")
                && normalized.contains(",action,");
    }

    private static int countDirectFiles(Path dir, Predicate<Path> predicate) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(predicate)
                    .count();
        } catch (IOException | RuntimeException ex) {
            return AgentPipelineHealthTrace.traceZero("patchdrop_direct_file_count", ex);
        }
    }

    private static int countWalkedFiles(Path root, Predicate<Path> predicate) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(predicate)
                    .count();
        } catch (IOException | RuntimeException ex) {
            return AgentPipelineHealthTrace.traceZero("patchdrop_walk_file_count", ex);
        }
    }

    private static boolean isNestedPatchDropProducerPatch(Path root, Path path) {
        return isNestedPatchDropArtifact(root, path)
                && !isPatchDropArchivePath(root, path)
                && hasFileNameSuffix(path, "-v3.patch");
    }

    private static Map<String, Object> firstPendingPatchDropProducer(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Map.of();
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isNestedPatchDropProducerManifest(root, path))
                    .sorted((left, right) -> left.toString().compareTo(right.toString()))
                    .map(AgentPipelineHealthController::readPatchDropProducerManifest)
                    .filter(row -> !row.isEmpty())
                    .findFirst()
                    .orElseGet(Map::of);
        } catch (IOException | RuntimeException ex) {
            return AgentPipelineHealthTrace.traceEmptyMap("patchdrop_pending_producer_lookup", ex);
        }
    }

    private static Map<String, Object> readPatchDropProducerManifest(Path manifestPath) {
        try {
            JsonNode manifest = JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            Map<String, Object> row = new LinkedHashMap<>();
            String manifestName = manifestPath.getFileName().toString();
            String baseName = manifestName.substring(0, manifestName.length() - ".manifest.json".length());
            boolean reportPresent = Files.isRegularFile(manifestPath.resolveSibling(baseName + ".report.md"));
            boolean verifyLogPresent = Files.isRegularFile(manifestPath.resolveSibling(baseName + ".verify.log"));
            boolean shaPresent = Files.isRegularFile(manifestPath.resolveSibling(baseName + ".sha256.txt"));
            String topic = safeTraceLabel(manifest.path("slug").asText(""));
            String node = safeTraceLabel(manifest.path("node").asText(""));
            String status = safeActionLabel(manifest.path("status").asText(""));
            String patchName = safeActionLabel(manifest.path("activePatch").asText(""));
            JsonNode sourceIsolation = manifest.path("sourceIsolation");
            String sourceIsolationGuard = safeActionLabel(sourceIsolation.path("guard").asText(""));
            String sourceRootKind = safeActionLabel(sourceIsolation.path("sourceRootKind").asText(""));
            boolean sharedSourceRoot = sourceIsolation.path("sharedSourceRoot").asBoolean(false);
            boolean directCanonicalSourceEdit = sourceIsolation.path("directCanonicalSourceEdit").asBoolean(false);
            boolean sourceIsolationOk = "PASS".equals(sourceIsolationGuard)
                    && "local-worktree".equals(sourceRootKind)
                    && !sharedSourceRoot
                    && !directCanonicalSourceEdit;
            if (topic != null) {
                row.put("pendingProducerTopic", topic);
            }
            if (node != null) {
                row.put("pendingProducerNode", node);
            }
            if (status != null) {
                row.put("pendingProducerStatus", status);
            }
            if (patchName != null) {
                row.put("pendingProducerPatchName", patchName);
            }
            row.put("pendingProducerReportPresent", reportPresent);
            row.put("pendingProducerVerifyLogPresent", verifyLogPresent);
            row.put("pendingProducerShaPresent", shaPresent);
            row.put("pendingProducerBundleComplete", reportPresent && verifyLogPresent && shaPresent);
            if (sourceIsolationGuard != null) {
                row.put("pendingProducerSourceIsolationGuard", sourceIsolationGuard);
            }
            if (sourceRootKind != null) {
                row.put("pendingProducerSourceRootKind", sourceRootKind);
            }
            row.put("pendingProducerSharedSourceRoot", sharedSourceRoot);
            row.put("pendingProducerDirectCanonicalSourceEdit", directCanonicalSourceEdit);
            row.put("pendingProducerSourceIsolationOk", sourceIsolationOk);
            return row;
        } catch (IOException | RuntimeException ex) {
            return AgentPipelineHealthTrace.traceEmptyMap("patchdrop_producer_manifest_read", ex);
        }
    }

    private static boolean isNestedPatchDropProducerManifest(Path root, Path path) {
        if (!isNestedPatchDropArtifact(root, path)
                || isPatchDropArchivePath(root, path)
                || !hasFileNameSuffix(path, ".manifest.json")) {
            return false;
        }
        String name = path.getFileName().toString();
        Path siblingPatch = path.resolveSibling(name.substring(0, name.length() - ".manifest.json".length()) + ".patch");
        return hasFileNameSuffix(siblingPatch, "-v3.patch") && Files.isRegularFile(siblingPatch);
    }

    private static boolean isPatchDropReportOnlyManifest(Path root, Path path) {
        if (!isNestedPatchDropArtifact(root, path)
                || isPatchDropArchivePath(root, path)
                || !hasFileNameSuffix(path, ".manifest.json")) {
            return false;
        }
        String name = path.getFileName().toString();
        Path siblingPatch = path.resolveSibling(name.substring(0, name.length() - ".manifest.json".length()) + ".patch");
        return !Files.isRegularFile(siblingPatch);
    }

    private static boolean isNestedPatchDropArtifact(Path root, Path path) {
        try {
            return root.relativize(path).getNameCount() > 1;
        } catch (IllegalArgumentException ex) {
            return AgentPipelineHealthTrace.traceFalse("patchdrop_nested_artifact_path", ex);
        }
    }

    private static boolean isPatchDropArchivePath(Path root, Path path) {
        try {
            Path relative = root.relativize(path);
            if (relative.getNameCount() == 0) {
                return false;
            }
            String first = relative.getName(0).toString();
            return "applied".equals(first)
                    || "rejected".equals(first)
                    || "superseded".equals(first)
                    || "orphan".equals(first)
                    || "rollback".equals(first)
                    || "__pycache__".equals(first);
        } catch (IllegalArgumentException ex) {
            return AgentPipelineHealthTrace.traceTrue("patchdrop_archive_path", ex);
        }
    }

    private static boolean hasFileNameSuffix(Path path, String suffix) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().endsWith(suffix);
    }

    private static String patchDropEvidenceNeeded(int activeTopLevel, int nestedProducer, int reportOnly) {
        if (activeTopLevel > 0) {
            return "patchdrop_apply_candidate_pending";
        }
        if (reportOnly > 0) {
            return "patchdrop_report_only_pending";
        }
        if (nestedProducer > 0) {
            return "nested_patchdrop_reference_not_apply_candidate";
        }
        return null;
    }

    private static String patchDropNextAction(int activeTopLevel, int nestedProducer, int reportOnly) {
        if (activeTopLevel > 0) {
            return "run_patchdrop_janitor_inventory";
        }
        if (reportOnly > 0) {
            return "classify_patchdrop_report_only_artifacts";
        }
        if (nestedProducer > 0) {
            return "promote_manifest_pinned_v3_if_current_goal_relevant";
        }
        return "patchdrop_queue_clear";
    }

    private static String safeJsonLabelList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode item : node) {
            String label = safeTraceLabel(item.asText(""));
            if (label != null) {
                labels.add(label);
            }
            if (labels.size() >= 8) {
                break;
            }
        }
        return String.join(",", labels);
    }

    private static String safeJsonActionLabelList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode item : node) {
            String label = safeActionLabel(item.asText(""));
            if (label != null) {
                labels.add(label);
            }
            if (labels.size() >= 8) {
                break;
            }
        }
        return String.join(",", labels);
    }

    private static long count(Map<String, Long> counts, String key) {
        Long value = counts == null ? null : counts.get(key);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static boolean isBlankOrPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || trimmed.startsWith("${")
                || trimmed.startsWith("<")
                || "__MISSING__".equalsIgnoreCase(trimmed)
                || "test".equalsIgnoreCase(trimmed)
                || "changeme".equalsIgnoreCase(trimmed)
                || "change-me".equalsIgnoreCase(trimmed);
    }

    private static String modelRuntimeReason(boolean fastBailTimeout,
                                             boolean waiting,
                                             boolean modelGuardTriggered,
                                             String defaultWaitCode,
                                             String errorCode,
                                             String answerMode) {
        if (fastBailTimeout) {
            return "timeout_fast_bail";
        }
        if (waiting) {
            return defaultWaitCode == null ? "waiting_for_default_model" : defaultWaitCode;
        }
        if (modelGuardTriggered) {
            return "model_guard";
        }
        if (errorCode != null) {
            return errorCode;
        }
        if (answerMode != null && answerMode.contains("fallback")) {
            return "fallback";
        }
        return "healthy";
    }

    private static String modelRuntimeDeliveryState(boolean fastBailTimeout,
                                                    boolean waiting,
                                                    String answerMode) {
        if (fastBailTimeout) {
            return "fallback_sent_model_timed_out";
        }
        if (waiting) {
            return "awaiting_default_model";
        }
        if (answerMode != null && answerMode.contains("fallback")) {
            return "fallback_sent";
        }
        return "normal";
    }

    private static String providerRuntimeReason(int awaitTimeoutCount,
                                                int cancelSuppressedCount,
                                                List<String> cancelledProviders,
                                                int cooldownSkippedCount,
                                                int maxBackoffRemainingMs) {
        if (awaitTimeoutCount > 0) {
            return "await_timeout";
        }
        if (cancelSuppressedCount > 0) {
            return "cancel_suppressed";
        }
        if (cancelledProviders != null && !cancelledProviders.isEmpty()) {
            return "provider_cancelled";
        }
        if (cooldownSkippedCount > 0 || maxBackoffRemainingMs > 0) {
            return "cooldown_active";
        }
        return "healthy";
    }

    private static String failSoftLadderReason(String trigger,
                                               boolean rescueMergeUsed,
                                               boolean poolSafeEmpty,
                                               boolean vectorFallbackUsed,
                                               int outCount,
                                               int cacheOnlyMergedCount,
                                               int tracePoolSize,
                                               int stageSelectedKeyCount) {
        if (trigger != null || rescueMergeUsed) {
            return "starvation_fallback";
        }
        if (vectorFallbackUsed) {
            return "vector_fallback";
        }
        if (poolSafeEmpty) {
            return "pool_safe_empty";
        }
        if (outCount == 0 && (cacheOnlyMergedCount > 0 || tracePoolSize > 0 || stageSelectedKeyCount > 0)) {
            return "rescue_available";
        }
        return "healthy";
    }

    private static String answerOutputReason(String answerMode,
                                             boolean emptyAnswerGuardTriggered,
                                             String emptyAnswerFallback,
                                             boolean evidenceListTraceInjected,
                                             boolean blankBaseFallback) {
        if (emptyAnswerGuardTriggered) {
            return "empty_answer_guard";
        }
        if (blankBaseFallback) {
            return "blank_base_fallback";
        }
        if (emptyAnswerFallback != null) {
            return emptyAnswerFallback;
        }
        if (answerMode != null && (answerMode.contains("fallback") || answerMode.contains("evidence_only"))) {
            return "answer_mode_fallback";
        }
        if (evidenceListTraceInjected) {
            return "trace_injected";
        }
        return "healthy";
    }

    private static String debugEventReason(List<DebugEvent> events, int recentWarnOrError, int totalSuppressed) {
        if (events == null || events.isEmpty()) {
            return "no_recent_events";
        }
        if (recentWarnOrError > 0) {
            return "recent_warn_or_error";
        }
        if (totalSuppressed > 0) {
            return "suppressed_debug_events";
        }
        return "healthy";
    }

    private static String debugAiMetricsReason(DebugAiMetricSnapshot snapshot) {
        if (snapshot == null || snapshot.totalEvents() <= 0L) {
            return "empty_window";
        }
        if (snapshot.errorEvents() > 0L) {
            return "error_events";
        }
        if (snapshot.warnEvents() > 0L) {
            return "warn_events";
        }
        return "ready";
    }

    private static DebugAiRawTile topDebugAiTile(List<DebugAiRawTile> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return null;
        }
        DebugAiRawTile best = null;
        for (DebugAiRawTile tile : tiles) {
            if (tile == null || tile.eventCount() <= 0L) {
                continue;
            }
            if (best == null
                    || tile.errorCount() > best.errorCount()
                    || (tile.errorCount() == best.errorCount() && tile.warnCount() > best.warnCount())
                    || (tile.errorCount() == best.errorCount()
                    && tile.warnCount() == best.warnCount()
                    && tile.eventCount() > best.eventCount())) {
                best = tile;
            }
        }
        return best;
    }
    private static boolean isWarnOrError(DebugEventLevel level) {
        return level == DebugEventLevel.WARN || level == DebugEventLevel.ERROR;
    }
    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }
    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return AgentPipelineHealthTrace.traceNullInteger("int_value_parse", ex);
        }
    }
    private static int intOrZero(Object value) {
        Integer parsed = intValue(value);
        return parsed == null ? 0 : Math.max(0, parsed);
    }
    private static double boundedDouble(Object value) {
        if (value == null) {
            return 0.0d;
        }
        try {
            double parsed = value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? Math.max(0.0d, Math.min(1.0d, parsed)) : 0.0d;
        } catch (NumberFormatException ex) {
            AgentPipelineHealthTrace.traceSuppressed("bounded_double_parse", ex);
            return 0.0d;
        }
    }
    private static String yesNo(Object value) {
        return truthy(value) ? "yes" : "no";
    }
    private static int boundedInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object traceValue(Map<String, Object> trace, String key) {
        return trace == null ? null : trace.get(key);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static int stageSelectedKeyCount(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) {
                count++;
            }
            return count;
        }
        return value == null ? 0 : 1;
    }

    private static String safeHashValue(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.matches("hash:[A-Za-z0-9_-]{1,80}")) {
            return trimmed;
        }
        return SafeRedactor.hashValue(trimmed);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
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

    private static Object providerStatusCode(Object value) {
        long code = boundedProviderLong(value, 999L);
        return code >= 100L && code <= 599L ? (int) code : "not_observed";
    }

    private static long boundedProviderLong(Object value, long maximum) {
        if (value == null) {
            return 0L;
        }
        try {
            long parsed = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value).trim());
            return Math.max(0L, Math.min(Math.max(0L, maximum), parsed));
        } catch (NumberFormatException ex) {
            AgentPipelineHealthTrace.traceSuppressed("provider_status_number", ex);
            return 0L;
        }
    }

    private static String safeProviderLabel(Object value, String fallback) {
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

    private static String safeTraceLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,64}") ? normalized : "unknown";
    }

    private static String safeActionLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9_.:-]{1,96}") ? trimmed : "unknown";
    }
}
