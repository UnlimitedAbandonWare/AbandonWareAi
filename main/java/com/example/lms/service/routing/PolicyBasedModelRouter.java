package com.example.lms.service.routing;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.RequestedModelTimeoutPolicy;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.AblationContributionTracker;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Real ModelRouter implementation.
 *
 * <p>
 * Replaces the pass-through router from {@link ModelRouterAutoConfig} by
 * registering
 * a primary {@link ModelRouter} bean.
 *
 * <p>
 * Key behaviors:
 * <ul>
 * <li>Utility/REWRITE intents -> fast model</li>
 * <li>Promotion to high model is decided by {@link RouterPolicy}</li>
 * <li>Fail-soft when optional models are missing</li>
 * </ul>
 */
@Service
@Primary
public class PolicyBasedModelRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(PolicyBasedModelRouter.class);

    private final ChatModel defaultModel;
    private final ChatModel fastModel;
    private final ChatModel highModel;
    private final RouterPolicy policy;

    private final DynamicChatModelFactory factory;

    @Value("${llm.timeout-seconds:12}")
    private int timeoutSeconds;
    @Value("${llm.fast.timeout-seconds:3}")
    private int fastTimeoutSeconds;
    @Value("${llm.high.timeout-seconds:30}")
    private int highTimeoutSeconds;
    @Value("${llm.requested-model.timeout-seconds:180}")
    private int requestedModelTimeoutSeconds;

    @Value("${llm.chat.temperature:0.3}")
    private double defaultTemperature;
    @Value("${llm.fast.temperature:0.0}")
    private double fastTemperature;
    @Value("${llm.high.temperature:0.3}")
    private double highTemperature;
    @Value("${llm.chat-model:${llm.model:}}")
    private String defaultConfiguredModel;
    @Value("${llm.fast.model:}")
    private String fastConfiguredModel;
    @Value("${llm.high.model:}")
    private String highConfiguredModel;

    private final ConcurrentHashMap<String, ChatModel> requestedCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    public PolicyBasedModelRouter(
            ChatModel defaultModel,
            @Qualifier("fastChatModel") ObjectProvider<ChatModel> fastProvider,
            @Qualifier("highModel") ObjectProvider<ChatModel> highProvider,
            RouterPolicy policy,
            DynamicChatModelFactory factory) {
        this.defaultModel = defaultModel;
        this.fastModel = (fastProvider != null) ? fastProvider.getIfAvailable(() -> defaultModel) : defaultModel;
        this.highModel = (highProvider != null) ? highProvider.getIfAvailable(() -> defaultModel) : defaultModel;
        this.policy = policy;
        this.factory = factory;
    }

    @Override
    public ChatModel route(RouteSignal sig) {
        if (sig == null) {
            return defaultModel;
        }
        String safeSignalReason = SafeRedactor.traceLabelOrFallback(sig.reason(), "unknown");

        // REWRITE/유틸 작업은 fast 모델로
        if (sig.intent() == RouteSignal.Intent.REWRITE) {
            if (log.isDebugEnabled()) {
                log.debug("[Router] intent=REWRITE -> fastModel");
            }
            try {
                String selectedName = resolveModelName(fastModel);
                TraceStore.put("ml.router.intent", "REWRITE");
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
                TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
                TraceStore.put("ml.router.reason", "intent=REWRITE");
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("event", "rewrite");
                ev.put("intent", "REWRITE");
                putModelFingerprint(ev, "selected", selectedName);
                ev.put("reason", "intent=REWRITE");
                emitRouterPipelineEvent("rewrite", ev, "ok");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.rewriteTrace", true); ModelRouterTraceSuppressions.trace("rewrite.trace", ignore); }
            return fastModel;
        }

        boolean promote = false;
        try {
            promote = (policy != null) && policy.shouldPromote(sig);
        } catch (Exception e) {
            TraceStore.put("ml.router.suppressed.policyPromote", true);
            ModelRouterTraceSuppressions.trace("policy.promote", e);
            promote = false;
        }

        if (promote) {
            String highName = resolveModelName(highModel);
            if (factory != null && !factory.canServe(highName)) {
                log.warn("[Router] promote blocked: highModelHash={} highModelLength={} reason=missing_credentials action=fastModel",
                        SafeRedactor.hashValue(highName), lengthOf(highName));

                // (E) Fix: never silently promote/fallback without leaving a breadcrumb.
                try {
                    TraceStore.put("ml.router.promote.blocked", true);
                    TraceStore.put("ml.router.promote.blocked.highModelHash", SafeRedactor.hashValue(highName));
                    TraceStore.put("ml.router.promote.blocked.highModelLength", lengthOf(highName));
                    TraceStore.put("ml.router.promote.blocked.reason", "missing_credentials");
                    java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                    ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                    ev.put("ts", java.time.Instant.now().toString());
                    ev.put("event", "promote.blocked");
                    putModelFingerprint(ev, "highModel", highName);
                    ev.put("intent", String.valueOf(sig.intent()));
                    if (!safeSignalReason.isBlank())
                        ev.put("reason", safeSignalReason);
                    TraceStore.append("ml.router.events", ev);
                    TraceStore.inc("ml.router.events.count");
                    emitRouterPipelineEvent("promote.blocked", ev, "fallback");
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteBlockedTrace", true); ModelRouterTraceSuppressions.trace("promoteBlocked.trace", ignore); }
                try {
                    AblationContributionTracker.recordPenaltyOnce(
                            "ablation.router.promote.blocked",
                            "router",
                            "promote_blocked",
                            0.15,
                            "missing_credentials");
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteBlockedAblation", true); ModelRouterTraceSuppressions.trace("promoteBlocked.ablation", ignore); }
                if (debugEventStore != null) {
                    try {
                        java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                        putModelFingerprint(dd, "highModel", highName);
                        putModelFingerprint(dd, "defaultModel", resolveModelName(defaultModel));
                        putModelFingerprint(dd, "fastModel", resolveModelName(fastModel));
                        dd.put("intent", String.valueOf(sig.intent()));
                        dd.put("reason", safeSignalReason);
                        debugEventStore.emit(
                                DebugProbeType.MODEL_GUARD,
                                DebugEventLevel.WARN,
                                "router.promote.blocked",
                                "Promotion requested but highModel is not serveable (missing credentials).",
                                "PolicyBasedModelRouter.route",
                                dd,
                                null);
                    } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteBlockedDebugEvent", true); ModelRouterTraceSuppressions.trace("promoteBlocked.debugEvent", ignore); }
                }

                if (fastModel != null) {
                    String fastName = resolveModelName(fastModel);
                    if (factory.canServe(fastName)) {
                        try {
                            TraceStore.put("ml.router.promote.fallback", "fastModel");
                            TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(fastName));
                            TraceStore.put("ml.router.selectedLength", lengthOf(fastName));
                        } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteFallbackFastTrace", true); ModelRouterTraceSuppressions.trace("promoteFallback.fastTrace", ignore); }
                        return fastModel;
                    }
                }
                try {
                    TraceStore.put("ml.router.promote.fallback", "defaultModel");
                    String selectedName = resolveModelName(defaultModel);
                    TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
                    TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteFallbackDefaultTrace", true); ModelRouterTraceSuppressions.trace("promoteFallback.defaultTrace", ignore); }
                return defaultModel;
            }

            if (log.isDebugEnabled()) {
                log.debug("[Router] promote -> highModel (reason={})", safeSignalReason);
            }
            try {
                TraceStore.put("ml.router.promote", true);
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(highName));
                TraceStore.put("ml.router.selectedLength", lengthOf(highName));
                TraceStore.put("ml.router.reason", safeSignalReason);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("event", "promote");
                putModelFingerprint(ev, "selected", highName);
                ev.put("reason", safeSignalReason);
                ev.put("intent", String.valueOf(sig.intent()));
                emitRouterPipelineEvent("promote", ev, "ok");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.promoteTrace", true); ModelRouterTraceSuppressions.trace("promote.trace", ignore); }
            return highModel;
        }

        try {
            String selectedName = resolveModelName(defaultModel);
            TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
            TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
            TraceStore.put("ml.router.reason", "default");
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "default");
            putModelFingerprint(ev, "selected", selectedName);
            ev.put("reason", "default");
            emitRouterPipelineEvent("default", ev, "ok");
        } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.defaultTrace", true); ModelRouterTraceSuppressions.trace("default.trace", ignore); }
        return defaultModel;
    }

    @Override
    public ChatModel route(String intent, String riskLevel, String verbosityHint, Integer targetMaxTokens) {

        RouteSignal.Intent i = parseIntent(intent);
        RouteSignal.Verbosity v = parseVerbosity(verbosityHint);
        int maxTok = (targetMaxTokens != null && targetMaxTokens > 0) ? targetMaxTokens : 1024;

        boolean highRisk = riskLevel != null && "HIGH".equalsIgnoreCase(riskLevel.trim());

        // Conservative heuristics: RouterPolicy holds the real thresholds.
        double uncertainty = highRisk ? 0.85 : 0.25;
        double complexity = switch (i) {
            case CODE -> 0.70;
            case SEARCH_HEAVY, LATEST_TECH, FACT -> 0.65;
            case REWRITE -> 0.20;
            default -> 0.40;
        };

        if (v == RouteSignal.Verbosity.VERBOSE || maxTok >= 1800) {
            complexity = Math.max(complexity, 0.70);
        }

        RouteSignal.Preference pref = (highRisk || i == RouteSignal.Intent.LATEST_TECH
                || v == RouteSignal.Verbosity.VERBOSE)
                        ? RouteSignal.Preference.QUALITY
                        : RouteSignal.Preference.BALANCED;

        RouteSignal sig = new RouteSignal(
                complexity,
                0.0,
                uncertainty,
                0.0,
                i,
                v,
                maxTok,
                pref,
                "mapped");

        return route(sig);
    }

    @Override
    public ChatModel route(String intent,
            String riskLevel,
            String verbosityHint,
            Integer targetMaxTokens,
            String requestedModel) {
        if (com.example.lms.llm.RequestedModelSelection.matches(requestedModel)) {
            return exactRequestedModel(requestedModel, targetMaxTokens);
        }
        ChatModel base = route(intent, riskLevel, verbosityHint, targetMaxTokens);

        String req = (requestedModel == null) ? null : requestedModel.trim();
        if (req == null || req.isBlank()) {
            return base;
        }

        // 채팅 부적합 모델(embedding/legacy)이면 기본 모델로 폴백
        if (isDisallowedChatModel(req)) {
            String baseName = resolveModelName(base);
            log.warn("[Router] requestedModelHash={} requestedModelLength={} non_chat=true baseModelHash={} baseModelLength={} action=use_base_model",
                    SafeRedactor.hashValue(req), lengthOf(req), SafeRedactor.hashValue(baseName), lengthOf(baseName));
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "disallowed_non_chat");
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(baseName));
                TraceStore.put("ml.router.selectedLength", lengthOf(baseName));
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.ignored");
                putModelFingerprint(ev, "requestedModel", req);
                ev.put("reason", "disallowed_non_chat");
                putModelFingerprint(ev, "selected", baseName);
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
                emitRouterPipelineEvent("requested.ignored", ev, "fallback");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedDisallowedTrace", true); ModelRouterTraceSuppressions.trace("requestedDisallowed.trace", ignore); }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    putModelFingerprint(dd, "requestedModel", req);
                    putModelFingerprint(dd, "selected", baseName);
                    dd.put("reason", "disallowed_non_chat");
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.disallowed",
                            "Requested model is not suitable for chat; falling back to base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedDisallowedDebugEvent", true); ModelRouterTraceSuppressions.trace("requestedDisallowed.debugEvent", ignore); }
            }
            return base;
        }
        // Ignore wrapper labels or legacy values like
        // "OpenAiChatModel:fallback:evidence".
        if (looksLikeWrapperLabel(req)) {
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "wrapper_label");
                String selectedName = resolveModelName(base);
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
                TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedWrapperLabelTrace", true); ModelRouterTraceSuppressions.trace("requestedWrapperLabel.trace", ignore); }
            return base;
        }
        if (factory == null) {
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "factory_unavailable");
                String selectedName = resolveModelName(base);
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
                TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedFactoryUnavailableTrace", true); ModelRouterTraceSuppressions.trace("requestedFactoryUnavailable.trace", ignore); }
            return base;
        }

        // RequestedModelGate: provider 미구성(OpenAI 키 없음 등)이면 요청 모델 무시
        if (!factory.canServe(req)) {
            String baseName = resolveModelName(base);
            log.warn("[Router] requestedModelHash={} requestedModelLength={} ignored_reason=provider_not_configured baseModelHash={} baseModelLength={} action=use_base_model",
                    SafeRedactor.hashValue(req), lengthOf(req), SafeRedactor.hashValue(baseName), lengthOf(baseName));
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "provider_not_configured");
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(baseName));
                TraceStore.put("ml.router.selectedLength", lengthOf(baseName));
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.ignored");
                putModelFingerprint(ev, "requestedModel", req);
                ev.put("reason", "provider_not_configured");
                putModelFingerprint(ev, "selected", baseName);
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
                emitRouterPipelineEvent("requested.ignored", ev, "fallback");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedProviderNotConfiguredTrace", true); ModelRouterTraceSuppressions.trace("requestedProviderNotConfigured.trace", ignore); }
            try {
                AblationContributionTracker.recordPenaltyOnce(
                        "ablation.router.requested.ignored",
                        "router",
                        "requested_ignored",
                        0.10,
                        "provider_not_configured");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedProviderNotConfiguredAblation", true); ModelRouterTraceSuppressions.trace("requestedProviderNotConfigured.ablation", ignore); }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    putModelFingerprint(dd, "requestedModel", req);
                    putModelFingerprint(dd, "selected", baseName);
                    dd.put("reason", "provider_not_configured");
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.ignored",
                            "Requested model ignored because provider is not configured; using base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedProviderNotConfiguredDebugEvent", true); ModelRouterTraceSuppressions.trace("requestedProviderNotConfigured.debugEvent", ignore); }
            }
            return base;
        }

        Tier tier = (base == fastModel) ? Tier.FAST : (base == highModel) ? Tier.HIGH : Tier.DEFAULT;
        int maxTok = (targetMaxTokens != null && targetMaxTokens > 0) ? targetMaxTokens : 1024;
        int tierTimeout = switch (tier) {
            case FAST -> fastTimeoutSeconds;
            case HIGH -> highTimeoutSeconds;
            default -> timeoutSeconds;
        };
        int timeout = RequestedModelTimeoutPolicy.timeoutSeconds(req, req, tierTimeout, requestedModelTimeoutSeconds);
        double temp = switch (tier) {
            case FAST -> fastTemperature;
            case HIGH -> highTemperature;
            default -> defaultTemperature;
        };

        String key = String.format(java.util.Locale.ROOT, "%s|%s|%d|%d|%.3f", req, tier.name(), maxTok, timeout, temp);
        ChatModel cached = requestedCache.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            ChatModel built = factory.lcWithTimeout(req, temp, null, maxTok, timeout);
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.applied", true);
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.selectedLength", lengthOf(req));
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedAppliedTrace", true); ModelRouterTraceSuppressions.trace("requestedApplied.trace", ignore); }

            ChatModel prev = requestedCache.putIfAbsent(key, built);
            return (prev != null) ? prev : built;
        } catch (Exception e) {
            log.warn("[Router] failed to build requested modelHash={} modelLength={} tier={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(req), lengthOf(req), tier,
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            String buildFailureLabel = "requested_model_build_failed";
            try {
                TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(req));
                TraceStore.put("ml.router.requestedModelLength", lengthOf(req));
                TraceStore.put("ml.router.requestedModel.buildFailed", true);
                TraceStore.put("ml.router.requestedModel.buildFailed.tier", String.valueOf(tier));
                TraceStore.put("ml.router.requestedModel.buildFailed.error", buildFailureLabel);
                String selectedName = resolveModelName(base);
                TraceStore.put("ml.router.selectedHash", SafeRedactor.hashValue(selectedName));
                TraceStore.put("ml.router.selectedLength", lengthOf(selectedName));
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.buildFailed");
                putModelFingerprint(ev, "requestedModel", req);
                ev.put("tier", String.valueOf(tier));
                ev.put("error", buildFailureLabel);
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
                emitRouterPipelineEvent("requested.buildFailed", ev, "failed");
            } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedBuildFailedTrace", true); ModelRouterTraceSuppressions.trace("requestedBuildFailed.trace", ignore); }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    putModelFingerprint(dd, "requestedModel", req);
                    dd.put("tier", String.valueOf(tier));
                    dd.put("error", buildFailureLabel);
                    dd.put("message", SafeRedactor.traceLabelOrFallback(e.getMessage(), ""));
                    putModelFingerprint(dd, "selected", resolveModelName(base));
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.buildFailed",
                            "Failed to build requested model; falling back to base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) { TraceStore.put("ml.router.suppressed.requestedBuildFailedDebugEvent", true); ModelRouterTraceSuppressions.trace("requestedBuildFailed.debugEvent", ignore); }
            }
            return base;
        }
    }

    private ChatModel exactRequestedModel(String requested, Integer maxTokens) {
        if (isDisallowedChatModel(requested) || looksLikeWrapperLabel(requested)) {
            throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
        }
        // Logical cloud IDs were already resolved against the server catalog in ChatWorkflow.
        if (factory == null || (!requested.startsWith("llmrouter.") && !factory.canServe(requested))) {
            throw new com.example.lms.llm.ModelSelectionException("provider_not_configured");
        }
        try {
            int timeout = RequestedModelTimeoutPolicy.timeoutSeconds(
                    requested, requested, timeoutSeconds, requestedModelTimeoutSeconds);
            ChatModel selected = factory.lcWithTimeout(requested, defaultTemperature, null, null, null,
                    maxTokens == null || maxTokens <= 0 ? 1024 : maxTokens, timeout, 0);
            if (selected == null) throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
            TraceStore.put("ml.router.requestedModel.applied", true);
            TraceStore.put("ml.router.requestedModelHash", SafeRedactor.hashValue(requested));
            return selected;
        } catch (RuntimeException failure) {
            throw com.example.lms.llm.ModelSelectionException.failure(failure);
        }
    }

    private enum Tier {
        FAST, DEFAULT, HIGH
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

    private static void emitRouterPipelineEvent(String step, Map<String, Object> event, String status) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("mode", "model_router");
            input.put("planId", safeEventValue(event, "event"));

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("selectedCount", 1);
            output.put("stageMs", 0);

            Map<String, Object> failure = new LinkedHashMap<>();
            String normalizedStep = step == null ? "" : step.trim().toLowerCase(Locale.ROOT).replace('.', '_');
            Object reason = event == null ? null : event.get("reason");
            Object error = event == null ? null : event.get("error");
            boolean failed = "failed".equalsIgnoreCase(status)
                    || "fallback".equalsIgnoreCase(status)
                    || normalizedStep.contains("blocked")
                    || normalizedStep.contains("ignored")
                    || normalizedStep.contains("fail")
                    || error != null;
            if (failed) {
                String reasonCode = (reason == null || String.valueOf(reason).isBlank())
                        ? (normalizedStep.isBlank() ? "model_route_fallback" : normalizedStep)
                        : String.valueOf(reason);
                failure.put("reasonCode", reasonCode);
                failure.put("failureClass", reasonCode);
                if (error != null) {
                    failure.put("exceptionType", safeExceptionType(error));
                }
            }

            Map<String, Object> control = new LinkedHashMap<>();
            if (!failure.isEmpty()) {
                control.put("action", "fail_soft_fallback");
                control.put("applied", true);
                control.put("reasonCode", failure.get("reasonCode"));
            }

            OrchEventEmitter.ragEvent(
                    "model.routing",
                    "model_route",
                    "model_router",
                    step == null || step.isBlank() ? "route" : step,
                    "PolicyBasedModelRouter",
                    status == null || status.isBlank() ? "ok" : status,
                    input,
                    output,
                    failure,
                    control);
        } catch (Throwable ignore) {
            TraceStore.put("ml.router.suppressed.ragEvent", true);
            ModelRouterTraceSuppressions.trace("rag.event", ignore);
        }
    }

    private static String safeEventValue(Map<String, Object> event, String key) {
        if (event == null || key == null) {
            return "";
        }
        Object value = event.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeExceptionType(Object error) {
        if (error == null) {
            return "";
        }
        if (error instanceof Throwable throwable) {
            return throwable.getClass().getSimpleName();
        }
        if (error instanceof Class<?> cls) {
            return cls.getSimpleName();
        }
        String value = SafeRedactor.safeMessage(String.valueOf(error), 96);
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void putModelFingerprint(Map<String, Object> target, String prefix, String model) {
        if (target == null || prefix == null || prefix.isBlank()) {
            return;
        }
        target.put(prefix + "Hash", SafeRedactor.hashValue(model));
        target.put(prefix + "Length", lengthOf(model));
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean looksLikeWrapperLabel(String v) {
        if (v == null)
            return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank())
            return false;

        String base = s;
        int colon = base.indexOf(':');
        if (colon > 0) {
            base = base.substring(0, colon);
        }
        if ("lc".equals(base) && s.contains(":")) {
            String rest = s.substring(s.indexOf(':') + 1);
            int colon2 = rest.indexOf(':');
            base = (colon2 > 0) ? rest.substring(0, colon2) : rest;
        }
        return base.endsWith("chatmodel");
    }

    private static boolean isDisallowedChatModel(String modelId) {
        if (modelId == null)
            return false;
        String s = modelId.trim().toLowerCase(Locale.ROOT);

        // 오케스트레이션 태그 제거 (:fallback:evidence 등). 로컬 모델 태그(name:tag)는 보존.
        int colon = s.indexOf(':');
        if (colon > 0) {
            String after = s.substring(colon + 1);
            String firstSeg = after.contains(":") ? after.substring(0, after.indexOf(':')) : after;
            if ("fallback".equals(firstSeg) || "evidence".equals(firstSeg) || "aux".equals(firstSeg)) {
                s = s.substring(0, colon);
            }
        }

        if (s.isBlank())
            return true;
        if (s.contains("embedding") || s.startsWith("text-embedding"))
            return true;
        return "babbage-002".equals(s) || "davinci-002".equals(s);
    }

    @Override
    public ChatModel escalate(RouteSignal sig) {
        return highModel;
    }

    @Override
    public String resolveModelName(ChatModel model) {
        if (model == null) {
            return "unknown";
        }

        String configured = configuredModelName(model);
        if (configured != null && looksLikeModelId(configured)) {
            return configured.trim();
        }

        String fromString = firstModelIdToken(String.valueOf(model));
        return (fromString != null) ? fromString : model.getClass().getSimpleName();
    }

    private String configuredModelName(ChatModel model) {
        if (model == fastModel) {
            return fastConfiguredModel;
        }
        if (model == highModel) {
            return highConfiguredModel;
        }
        if (model == defaultModel) {
            return defaultConfiguredModel;
        }
        return null;
    }

    private static boolean looksLikeModelId(String s) {
        if (s == null)
            return false;
        String t = s.trim();
        if (t.isEmpty())
            return false;
        if (t.length() > 96)
            return false;
        if (t.contains("http://") || t.contains("https://"))
            return false;
        if (t.chars().anyMatch(Character::isWhitespace))
            return false;

        // allow tokens like "gpt-5.5", "qwen2.5-7b-instruct", "gemma3:27b"
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '/')) {
                return false;
            }
        }
        return true;
    }

    private static String firstModelIdToken(String s) {
        if (s == null)
            return null;
        for (String part : s.split("[\\s,;()]+")) {
            if (looksLikeModelId(part)) {
                return part.trim();
            }
        }
        return null;
    }

    private static RouteSignal.Intent parseIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return RouteSignal.Intent.GENERAL;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return RouteSignal.Intent.valueOf(t);
        } catch (IllegalArgumentException ignore) {
            TraceStore.put("ml.router.suppressed.intentParse", true);
            ModelRouterTraceSuppressions.trace("intentParse", ignore);
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.contains("rewrite") || s.contains("disamb") || s.contains("transform"))
                return RouteSignal.Intent.REWRITE;
            if (s.contains("code"))
                return RouteSignal.Intent.CODE;
            if (s.contains("search"))
                return RouteSignal.Intent.SEARCH_HEAVY;
            if (s.contains("latest") || s.contains("tech"))
                return RouteSignal.Intent.LATEST_TECH;
            if (s.contains("fact"))
                return RouteSignal.Intent.FACT;
            return RouteSignal.Intent.GENERAL;
        }
    }

    private static RouteSignal.Verbosity parseVerbosity(String hint) {
        if (hint == null || hint.isBlank()) {
            return RouteSignal.Verbosity.NORMAL;
        }
        String v = hint.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "brief", "terse", "short" -> RouteSignal.Verbosity.TERSE;
            case "deep", "ultra", "verbose", "long" -> RouteSignal.Verbosity.VERBOSE;
            default -> RouteSignal.Verbosity.NORMAL;
        };
    }
}
