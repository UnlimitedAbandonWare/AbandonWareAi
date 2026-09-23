package com.example.lms.web;

import com.example.lms.agent.context.AgentPipelineHealthController;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.llm.LocalLlmSmokeHistoryDiagnosticsService;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.transform.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatUiHeartbeatController {

    private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

    private final ObjectProvider<AgentPipelineHealthController> pipelineHealthControllerProvider;
    private final ObjectProvider<ApplicationContext> applicationContextProvider;
    private final ObjectProvider<PromptBuilder> promptBuilderProvider;
    private final ObjectProvider<QueryTransformer> queryTransformerProvider;
    private final ObjectProvider<HybridWebSearchProvider> hybridWebSearchProvider;
    private final ObjectProvider<NightmareBreaker> nightmareBreakerProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<DebugAiMetricsService> debugAiMetricsServiceProvider;
    private final ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider;
    private final ObjectProvider<LocalLlmSmokeHistoryDiagnosticsService> localLlmSmokeHistoryDiagnosticsServiceProvider;
    private final ChatUiHeartbeatCache cache;
    @Autowired(required = false)
    private com.example.lms.config.LocalLlmProcessManager localLlmProcessManager;
    @Autowired(required = false)
    private com.example.lms.service.diagnostic.RuntimeDiagnosticsService runtimeDiagnosticsService;

    @Autowired
    public ChatUiHeartbeatController(
            ObjectProvider<AgentPipelineHealthController> pipelineHealthControllerProvider,
            ObjectProvider<ApplicationContext> applicationContextProvider,
            ObjectProvider<PromptBuilder> promptBuilderProvider,
            ObjectProvider<QueryTransformer> queryTransformerProvider,
            ObjectProvider<HybridWebSearchProvider> hybridWebSearchProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<DebugAiMetricsService> debugAiMetricsServiceProvider,
            ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider,
            ObjectProvider<LocalLlmSmokeHistoryDiagnosticsService> localLlmSmokeHistoryDiagnosticsServiceProvider
    ) {
        this(
                pipelineHealthControllerProvider,
                applicationContextProvider,
                promptBuilderProvider,
                queryTransformerProvider,
                hybridWebSearchProvider,
                nightmareBreakerProvider,
                debugEventStoreProvider,
                debugAiMetricsServiceProvider,
                traceSnapshotStoreProvider,
                localLlmSmokeHistoryDiagnosticsServiceProvider,
                System::nanoTime,
                DEFAULT_CACHE_TTL);
    }

    ChatUiHeartbeatController(
            ObjectProvider<AgentPipelineHealthController> pipelineHealthControllerProvider,
            ObjectProvider<ApplicationContext> applicationContextProvider,
            ObjectProvider<PromptBuilder> promptBuilderProvider,
            ObjectProvider<QueryTransformer> queryTransformerProvider,
            ObjectProvider<HybridWebSearchProvider> hybridWebSearchProvider,
            ObjectProvider<NightmareBreaker> nightmareBreakerProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider,
            ObjectProvider<DebugAiMetricsService> debugAiMetricsServiceProvider,
            ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider,
            ObjectProvider<LocalLlmSmokeHistoryDiagnosticsService> localLlmSmokeHistoryDiagnosticsServiceProvider,
            LongSupplier monotonicNanos,
            Duration cacheTtl
    ) {
        this.pipelineHealthControllerProvider = Objects.requireNonNull(pipelineHealthControllerProvider);
        this.applicationContextProvider = Objects.requireNonNull(applicationContextProvider);
        this.promptBuilderProvider = Objects.requireNonNull(promptBuilderProvider);
        this.queryTransformerProvider = Objects.requireNonNull(queryTransformerProvider);
        this.hybridWebSearchProvider = Objects.requireNonNull(hybridWebSearchProvider);
        this.nightmareBreakerProvider = Objects.requireNonNull(nightmareBreakerProvider);
        this.debugEventStoreProvider = Objects.requireNonNull(debugEventStoreProvider);
        this.debugAiMetricsServiceProvider = Objects.requireNonNull(debugAiMetricsServiceProvider);
        this.traceSnapshotStoreProvider = Objects.requireNonNull(traceSnapshotStoreProvider);
        this.localLlmSmokeHistoryDiagnosticsServiceProvider =
                Objects.requireNonNull(localLlmSmokeHistoryDiagnosticsServiceProvider);
        this.cache = new ChatUiHeartbeatCache(monotonicNanos, cacheTtl);
    }

    @GetMapping("/ui-heartbeat")
    public Map<String, Object> uiHeartbeat() {
        Map<String, Object> cached = cache.getOrRefresh(this::refreshPublicPayload);
        if (localLlmProcessManager == null && runtimeDiagnosticsService == null) return cached;
        Map<String, Object> current = new java.util.LinkedHashMap<>(cached);
        if (localLlmProcessManager != null) current.put("localLlmRecovery", localLlmProcessManager.diagnostics());
        if (runtimeDiagnosticsService != null) current.put("runtimeToolkit", runtimeDiagnosticsService.toolkitSnapshot());
        return java.util.Collections.unmodifiableMap(current);
    }

    private Map<String, Object> refreshPublicPayload() {
        try {
            AgentPipelineHealthController pipelineHealthController =
                    pipelineHealthControllerProvider.getIfAvailable();
            if (pipelineHealthController == null) {
                return ChatUiHeartbeatPayload.from(ChatUiCoreHeartbeatProbe.snapshot(
                        "agent_db_context_disabled",
                        applicationContextProvider,
                        promptBuilderProvider,
                        queryTransformerProvider,
                        hybridWebSearchProvider,
                        nightmareBreakerProvider,
                        debugEventStoreProvider,
                        debugAiMetricsServiceProvider,
                        traceSnapshotStoreProvider,
                        localLlmSmokeHistoryDiagnosticsServiceProvider.getIfAvailable()));
            }
            return ChatUiHeartbeatPayload.from(pipelineHealthController.pipelineHealth());
        } catch (RuntimeException ex) {
            log.warn(
                    "[AWX][chat-ui] heartbeat degraded reason={} errorType={}",
                    "heartbeat_unavailable",
                    ex.getClass().getSimpleName());
            return ChatUiHeartbeatPayload.disabled("heartbeat_unavailable");
        }
    }

}
