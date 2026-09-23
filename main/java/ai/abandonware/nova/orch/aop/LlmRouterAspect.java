package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.NovaModelGuardProperties;
import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import ai.abandonware.nova.orch.llm.ModelGuardSupport;
import ai.abandonware.nova.orch.llm.OpenAiResponsesChatModel;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OllamaNativeChatModel;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OpenAiTokenParamCompat;
import com.example.lms.llm.gateway.FallbackAwareChatModel;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.LlmGatewayBreadcrumbPublisher;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.RoutingEligibility;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestration glue for UAW's llmrouter.* logical model IDs.
 *
 * <p>
 * Intercepts DynamicChatModelFactory.lcWithTimeout(..) and resolves:
 * - llmrouter.<key> -> llmrouter.models.<key> mapping
 * - llmrouter.auto / llmrouter -> automatic selection via
 * {@link LlmRouterBandit}
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class LlmRouterAspect {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterAspect.class);

    private final Environment env;
    private final LlmRouterProperties props;
    private final LlmRouterBandit bandit;

    private final NovaModelGuardProperties modelGuardProps;
    private final ObjectProvider<KeyResolver> keyResolverProvider;
    private final HybridLlmGatewayProbeService gatewayProbeService;
    private final LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher;
    private final LlmGatewayFailureClassifier gatewayFailureClassifier;
    private final ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    private final GeminiGateway geminiGateway;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.config.LocalLlmProcessManager localLlmProcessManager;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider) {
        this(env, props, bandit, modelGuardProps, keyResolverProvider, null, null, null, null, null);
    }

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider,
            HybridLlmGatewayProbeService gatewayProbeService,
            LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher,
            LlmGatewayFailureClassifier gatewayFailureClassifier) {
        this(env, props, bandit, modelGuardProps, keyResolverProvider, gatewayProbeService,
                gatewayBreadcrumbPublisher, gatewayFailureClassifier, null, null);
    }

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider,
            HybridLlmGatewayProbeService gatewayProbeService,
            LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher,
            LlmGatewayFailureClassifier gatewayFailureClassifier,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        this(env, props, bandit, modelGuardProps, keyResolverProvider, gatewayProbeService,
                gatewayBreadcrumbPublisher, gatewayFailureClassifier, modelRuntimeHealthTracker, null);
    }

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit,
            NovaModelGuardProperties modelGuardProps,
            ObjectProvider<KeyResolver> keyResolverProvider,
            HybridLlmGatewayProbeService gatewayProbeService,
            LlmGatewayBreadcrumbPublisher gatewayBreadcrumbPublisher,
            LlmGatewayFailureClassifier gatewayFailureClassifier,
            ModelRuntimeHealthTracker modelRuntimeHealthTracker,
            GeminiGateway geminiGateway) {
        this.env = env;
        this.props = props;
        this.bandit = bandit;
        this.modelGuardProps = modelGuardProps;
        this.keyResolverProvider = keyResolverProvider;
        this.gatewayProbeService = gatewayProbeService;
        this.gatewayBreadcrumbPublisher = gatewayBreadcrumbPublisher;
        this.gatewayFailureClassifier = gatewayFailureClassifier;
        this.modelRuntimeHealthTracker = modelRuntimeHealthTracker;
        this.geminiGateway = geminiGateway;
    }

    @Around("execution(* com.example.lms.llm.DynamicChatModelFactory.lcWithTimeout(..))")
    public Object aroundLcWithTimeout(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEnabled() || bandit == null) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();

        // Optional alias mapping for legacy/default model ids.
        boolean argsRewritten = false;
        if (args != null && args.length > 0 && args[0] instanceof String modelId
                && !com.example.lms.llm.RequestedModelSelection.matches(modelId)) {
            String aliased = resolveAlias(modelId);
            if (aliased != null && !aliased.isBlank() && !aliased.equals(modelId)) {
                args = args.clone();
                args[0] = aliased;
                argsRewritten = true;
            }
        }

        CallArgs ca = CallArgs.parse(args);
        if (ca == null) {
            return argsRewritten ? pjp.proceed(args) : pjp.proceed();
        }

        TraceStore.put("llm.gateway.preselectionFallbackCount", 0L);
        // A manual choice is an exact registered route, never an auto/alias request.
        if (com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId)) {
            if (!ca.requestedModelId.startsWith("llmrouter.")) return pjp.proceed();
            String key = ca.requestedModelId.substring("llmrouter.".length());
            var cfg = props.getModels().get(key);
            if (cfg == null || !cfg.isEnabled())
                throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
            try { return routeWithGateway(new LlmRouterBandit.Selected(key, cfg), ca); }
            catch (RuntimeException failure) { throw com.example.lms.llm.ModelSelectionException.failure(failure); }
        }
        // 1) Resolve llmrouter.* directly.
        LlmRouterBandit.Selected sel = bandit.pick(
                ca.requestedModelId, gatewayFilter(ca), "route:primary", 0L);
        if (sel != null) {
            return routeWithGateway(sel, ca);
        }
        ChatModel allLocalOpenFallback = routeWhenAllAutoLocalEndpointsOpen(ca);
        if (allLocalOpenFallback != null) {
            return allLocalOpenFallback;
        }

        // 2) Otherwise proceed; if OpenAI key is missing, optionally fall back to
        // llmrouter.auto.
        try {
            return argsRewritten ? pjp.proceed(args) : pjp.proceed();
        } catch (IllegalStateException ise) {
            if (props.isFallbackWhenOpenAiMissing() && looksLikeMissingOpenAiKey(ise)) {
                LlmRouterBandit.Selected autoSel = bandit.pick(
                        "llmrouter.auto", gatewayFilter(ca), "route:fallback", 1L);
                if (autoSel != null) {
                    log.warn("[llmrouter] OpenAI key missing; falling back to local auto route key={}", autoSel.key());
                    return routeWithGateway(autoSel, ca);
                }
            }
            throw ise;
        }
    }

    private LlmRouterBandit.RouteEligibilityFilter gatewayFilter(CallArgs ca) {
        if (gatewayProbeService == null || !gatewayProbeService.isEnforce()) {
            return LlmRouterBandit.RouteEligibilityFilter.always();
        }
        return (key, cfg) -> {
            if (cfg != null && cfg.isFallbackOnly()) {
                return false;
            }
            RoutingEligibility eligibility = gatewayProbeService.evaluate(key, cfg, stage(ca, cfg));
            if (gatewayBreadcrumbPublisher != null) {
                gatewayBreadcrumbPublisher.publishEligibility(eligibility);
            }
            return eligibility == null || eligibility.eligible();
        };
    }

    /** One API attempt for bounded callers that own their fallback budget. Never enters local/auto fallback. */
    public ChatModel apiAttempt(String routeKey, int timeoutMs, int maxTokens) {
        return apiAttempt(routeKey, timeoutMs, maxTokens, null);
    }

    /** Same bounded attempt carrying a native structured-output schema; providers without a verified
        structured-output contract keep the existing JSON-object mode. */
    public ChatModel apiAttempt(String routeKey, int timeoutMs, int maxTokens,
            dev.langchain4j.model.chat.request.json.JsonSchema cueJsonSchema) {
        var cfg = props.getModels().get(routeKey);
        if (cfg == null || !cfg.isEnabled() || !StringUtils.hasText(cfg.getProvider())
                || isLocalEligibilityProvider(cfg.getProvider())) {
            throw new IllegalArgumentException("api_route_required");
        }
        if (gatewayProbeService == null
                || !gatewayProbeService.evaluate(routeKey, cfg, "chat").eligible()) {
            throw new IllegalStateException("api_route_unavailable");
        }
        var args = new CallArgs("llmrouter." + routeKey, 0.0, null, null, null,
                Math.max(64, Math.min(1024, maxTokens)), Math.max(1, (timeoutMs + 999) / 1000), 0);
        args.cueJson = true;
        args.cueJsonSchema = cueJsonSchema;
        args.cueTimeoutMs = Math.max(1, timeoutMs);
        return recordOutcomes(buildRoutedModel(new LlmRouterBandit.Selected(routeKey, cfg), args,
                false, ignored -> {}, "primary"), routeKey);
    }

    private ChatModel routeWithGateway(LlmRouterBandit.Selected sel, CallArgs ca) {
        return routeWithGateway(sel, ca, new java.util.LinkedHashSet<>(), null, sel, null);
    }

    /** Retains an existing structured native adapter while sharing registered API selection and budgets. */
    public ChatModel routeLocalInference(ChatModel primary, String baseUrl, String modelName, int timeoutMs) {
        return routeLocalInference(primary, baseUrl, modelName, timeoutMs, 0.0, null, null, null, 512, "ollama_native");
    }

    public ChatModel routeLocalInference(ChatModel primary, String baseUrl, String modelName, int timeoutMs,
            Double temperature, Double topP, Double frequencyPenalty, Double presencePenalty,
            Integer maxTokens, String protocol) {
        if (props == null || !props.isEnabled() || !preferCloudOnLocalFailure())
            return gatewayProbeService == null ? primary : gatewayProbeService.guardLocalModel(primary, baseUrl, modelName);
        var cfg = new LlmRouterProperties.ModelConfig();
        cfg.setEnabled(true); cfg.setProvider("local"); cfg.setStage("chat"); cfg.setName(modelName); cfg.setBaseUrl(baseUrl);
        String identity = endpointModelIdentity(cfg);
        LlmRouterBandit.Selected selected = new LlmRouterBandit.Selected("conversate-local", cfg);
        for (String key : props.getModels().keySet().stream().sorted().toList()) {
            var registered = props.getModels().get(key);
            if (registered != null && registered.isEnabled() && isExplicitLocalProvider(registered.getProvider())
                    && identity.equals(endpointModelIdentity(registered))) {
                selected = new LlmRouterBandit.Selected(key, registered); break;
            }
        }
        // 제작: strict 선택이 이 로컬 모델과 정확히 일치하면 합성 CallArgs에 원래 선택 ID를 유지해
        // routeWithGateway 내부 matches() 가드가 lazy failover(FallbackAwareChatModel)를 차단하게 한다.
        String requestedIdentity = com.example.lms.llm.RequestedModelSelection.matches(modelName)
                ? modelName
                : "llmrouter." + selected.key();
        var ca = new CallArgs(requestedIdentity, temperature, topP, frequencyPenalty, presencePenalty, maxTokens,
                Math.max(1, (timeoutMs + 999) / 1000), 0);
        ChatModel boundedPrimary = new ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                return invokeBounded(request.messages(), request);
            }
            private dev.langchain4j.model.chat.response.ChatResponse invokeBounded(
                    java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                TimeBudget prior = TimeBudgetContext.get();
                try {
                    if (prior != null) TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, prior.remainingMillis() / 3))));
                    return invokeDelegate(primary, messages, request);
                } finally { if (prior == null) TimeBudgetContext.clear(); else TimeBudgetContext.set(prior); }
            }
        };
        final var original = selected;
        return new ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                return invokeRouted(request.messages(), request);
            }
            private dev.langchain4j.model.chat.response.ChatResponse invokeRouted(
                    java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                TimeBudget prior = TimeBudgetContext.get();
                long allowed = prior == null ? timeoutMs : Math.min(timeoutMs, prior.remainingMillis());
                try {
                    TraceStore.put("llm.gateway.preselectionFallbackCount", 0L);
                    TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, allowed))));
                    return invokeDelegate(
                            routeWithGateway(original, ca, new java.util.LinkedHashSet<>(), boundedPrimary, original, protocol),
                            messages, request);
                } finally { if (prior == null) TimeBudgetContext.clear(); else TimeBudgetContext.set(prior); }
            }
        };
    }

    private ChatModel routeWithGateway(LlmRouterBandit.Selected sel, CallArgs ca, java.util.Set<String> visited,
            ChatModel providedPrimary, LlmRouterBandit.Selected original, String providedProtocol) {
        if (visited.size() >= 64 || !visited.add(sel.key())) failRoute(sel.key(), null, "route_cycle");
        if (sel.cfg() == null) {
            failRoute(sel.key(), null, "missing_route_config");
        }
        if (!sel.cfg().isEnabled()) {
            failRoute(sel.key(), sel.cfg().getBaseUrl(), "route_disabled");
        }
        if (!sel.key().equals(original.key()) && gatewayProbeService != null && hasCapabilityRequirements(original.cfg())) {
            RoutingEligibility inherited = gatewayProbeService.evaluate(sel.key(), sel.cfg(), stage(ca, sel.cfg()));
            if (inherited == null || !inherited.eligible() || !preservesRequirements(original.cfg(), sel.cfg(), inherited))
                failRoute(sel.key(), sel.cfg().getBaseUrl(), "fallback_capability_unverified");
        }
        if (localLlmProcessManager != null && sel.cfg() != null
                && !localLlmProcessManager.isAvailable(sel.cfg().getBaseUrl())) {
            if (com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId))
                throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
            LlmRouterBandit.Selected fallback = preferCloudOnLocalFailure()
                    ? nextEligibleCloudSelection(original, ca, visited) : runtimeDeviceFallbackSelection(sel, ca);
            if (fallback == null || !localLlmProcessManager.isAvailable(fallback.cfg().getBaseUrl())) {
                fallback = eligibleFallbackSelection(sel, ca);
            }
            if (fallback != null && localLlmProcessManager.isAvailable(fallback.cfg().getBaseUrl())) {
                localLlmProcessManager.recordFallback("LOCAL_PROVIDER_UNAVAILABLE");
                recordRouteFailure(sel.key(), "LOCAL_PROVIDER_UNAVAILABLE");
                reportMaskedFallback(sel.key(), fallback.key());
                recordPreselectionFallback(sel.key(), fallback.key(), "local_provider_unavailable");
                return routeWithGateway(fallback, ca, visited, null, original, null);
            }
            recordRouteFailure(sel.key(), "LOCAL_PROVIDER_UNAVAILABLE");
            failRoute(sel.key(), sel.cfg().getBaseUrl(), "LOCAL_PROVIDER_UNAVAILABLE");
        }
        RoutingEligibility eligibility = null;
        if (gatewayProbeService != null) {
            eligibility = gatewayProbeService.evaluate(sel.key(), sel.cfg(), stage(ca, sel.cfg()));
            if (gatewayBreadcrumbPublisher != null) {
                gatewayBreadcrumbPublisher.publishEligibility(eligibility);
            }
            if (com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId)
                    && (eligibility == null || !eligibility.eligible()))
                throw new com.example.lms.llm.ModelSelectionException("model_unavailable");
            if (gatewayProbeService.isEnforce() && eligibility != null && !eligibility.eligible()) {
                LlmRouterBandit.Selected fallback = preferCloudOnLocalFailure()
                        ? nextEligibleCloudSelection(original, ca, visited) : deviceFallbackSelection(sel, ca, eligibility);
                if (fallback == null) {
                    fallback = eligibleFallbackSelection(sel, ca);
                }
                if (fallback != null) {
                    if (gatewayBreadcrumbPublisher != null) {
                        gatewayBreadcrumbPublisher.publishFallback(sel.key(), fallback.key(), eligibility.primaryFailure(),
                                "enforce_ineligible");
                    }
                    if (eligibility.primaryFailure() != null && eligibility.primaryFailure() != LlmFailureClass.NONE) {
                        recordRouteFailure(sel.key(), eligibility.primaryFailure().name());
                        reportMaskedFallback(sel.key(), fallback.key());
                    }
                    recordPreselectionFallback(sel.key(), fallback.key(), "enforce_ineligible");
                    return routeWithGateway(fallback, ca, visited, null, original, null);
                }
                if (eligibility.primaryFailure() == LlmFailureClass.GPU_DEVICE_LOST) {
                    failGpuDeviceLost(sel.key(), sel.cfg() == null ? null : sel.cfg().getBaseUrl());
                }
                failRoute(sel.key(), sel.cfg() == null ? null : sel.cfg().getBaseUrl(),
                        "gateway_ineligible_" + eligibility.primaryFailure().name().toLowerCase(Locale.ROOT));
            }
        }

        LlmRouterBandit.Selected cloudFallback = fallbackSelection(sel);
        boolean deviceFallbackConfigured = hasConfiguredDeviceFallback(sel);
        boolean lazyFallbackPossible = !com.example.lms.llm.RequestedModelSelection.matches(ca.requestedModelId)
                && gatewayFailureClassifier != null
                && (deviceFallbackConfigured || cloudFallback != null
                || (gatewayProbeService != null && gatewayProbeService.cloudFallbackEnabled()));
        AtomicReference<ModelRuntimeHealthTracker.RequestAttemptRoute> primaryRoute = new AtomicReference<>();
        ChatModel primary;
        if (providedPrimary == null) primary = recordOutcomes(
                buildRoutedModel(sel, ca, lazyFallbackPossible, primaryRoute::set, "primary"), sel.key());
        else {
            publishSelectedRoute(sel.key(), sel.cfg().getName(), sel.cfg().getBaseUrl(), providedProtocol,
                    sel.cfg(), lazyFallbackPossible, primaryRoute::set);
            primary = recordOutcomes(decorateRoutedAttempt(providedPrimary, "primary", sel.key(), sel.cfg().getName(),
                    sel.cfg().getBaseUrl(), providedProtocol, ca, sel.cfg()), sel.key());
        }
        if (!lazyFallbackPossible) {
            return primary;
        }
        if (primary instanceof ExpectedFailureChatModel) {
            return primary;
        }
        String requestTimelineId = capturedRequestTimelineId();
        AtomicReference<ModelRuntimeHealthTracker.RequestAttemptRoute> fallbackRoute = new AtomicReference<>();
        return new FallbackAwareChatModel(primary,
                (failureClass, usedRoutes) -> {
                    var considered = new java.util.LinkedHashSet<>(usedRoutes);
                    for (int selectionAttempt = 0; selectionAttempt < Math.min(64, props.getModels().size()); selectionAttempt++) {
                    TimeBudget remainingBudget = TimeBudgetContext.get();
                    if (remainingBudget != null && remainingBudget.remainingMillis() <= 0) return null;
                    LlmRouterBandit.Selected candidate = null;
                    boolean usesDeviceFallback = false;
                    if (preferCloudOnLocalFailure()) candidate = nextEligibleCloudSelection(original, ca, considered);
                    if (candidate == null && usedRoutes.size() <= 1
                            && (failureClass == LlmFailureClass.GPU_DEVICE_LOST
                            || failureClass == LlmFailureClass.VRAM_OOM)) {
                        candidate = runtimeDeviceFallbackSelection(sel, ca);
                        if (candidate != null && considered.contains(candidate.key())) candidate = null;
                        usesDeviceFallback = candidate != null;
                    }
                    if (candidate == null) {
                        candidate = nextEligibleCloudSelection(original, ca, considered);
                    }
                    if (candidate != null && localLlmProcessManager != null
                            && !localLlmProcessManager.isAvailable(candidate.cfg().getBaseUrl())) candidate = null;
                    if (candidate != null && usesDeviceFallback && gatewayProbeService != null && hasCapabilityRequirements(original.cfg())) {
                        RoutingEligibility inherited = gatewayProbeService.evaluate(candidate.key(), candidate.cfg(), stage(ca, candidate.cfg()));
                        if (inherited == null || !inherited.eligible() || !preservesRequirements(original.cfg(), candidate.cfg(), inherited))
                            candidate = null;
                    }
                    if (candidate == null) {
                        return null;
                    }
                    if (usesDeviceFallback) {
                        traceDeviceFallbackSelection(sel, candidate);
                    }
                    if (localLlmProcessManager != null && localLlmProcessManager.managesEndpoint(sel.cfg().getBaseUrl())) {
                        localLlmProcessManager.recordFallback(failureClass.name());
                    }
                    fallbackRoute.set(null);
                    considered.add(candidate.key());
                    ChatModel fallbackModel;
                    try { fallbackModel = recordOutcomes(
                            buildRoutedModel(candidate, withoutLibraryRetries(ca), false, fallbackRoute::set, "fallback",
                                    usedRoutes.size() <= 1 && isLocalEligibilityProvider(sel.cfg().getProvider())
                                    && gatewayProbeService != null && gatewayProbeService.cloudFallbackEnabled() ? 1 : 0),
                            candidate.key());
                    } catch (RuntimeException unavailable) {
                        if (LlmGatewayFailureClassifier.isCancellation(unavailable)) throw unavailable;
                        TraceStore.put("llm.gateway.fallback.skippedReason", "candidate_build_unavailable");
                        continue;
                    }
                    if (fallbackModel == null || fallbackModel instanceof ExpectedFailureChatModel) continue;
                    return new FallbackAwareChatModel.ResolvedFallback(
                            fallbackModel,
                            candidate.key(),
                            fallbackRoute.get());
                    }
                    return null;
                },
                gatewayFailureClassifier,
                gatewayBreadcrumbPublisher,
                sel.key(),
                modelRuntimeHealthTracker,
                requestTimelineId,
                primaryRoute.get(),
                gatewayProbeService != null && gatewayProbeService.cloudFallbackEnabled()
                        && isLocalEligibilityProvider(sel.cfg().getProvider()) ? 2 : 1)
                .withMaskedFallbackReporter(this::reportMaskedFallback);
    }

    private boolean preferCloudOnLocalFailure() {
        return (gatewayProbeService != null && gatewayProbeService.localFailoverEnabled())
                || (bool("llm.gateway.local-device-failover.enabled", "LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENABLED", false)
                && "ENFORCE".equalsIgnoreCase(firstNonBlank(get("llm.gateway.local-device-failover.enforcement"),
                        get("LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENFORCEMENT"))));
    }

    private static void recordPreselectionFallback(String from, String to, String reason) {
        String safeFrom = SafeRedactor.traceLabelOrFallback(from, "route");
        String safeTo = SafeRedactor.traceLabelOrFallback(to, "route");
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unavailable");
        long count = TraceStore.inc("llm.gateway.preselectionFallbackCount");
        TraceStore.put("llm.gateway.fallback.selectedRoute", safeTo);
        TraceStore.put("llm.gateway.preselectionReason", safeReason);
        log.info("[llm-gateway] bypass from={} to={} reason={} count={}", safeFrom, safeTo, safeReason, count);
    }

    private static CallArgs withoutLibraryRetries(CallArgs ca) {
        return new CallArgs(ca.requestedModelId, ca.temperature, ca.topP, ca.frequencyPenalty,
                ca.presencePenalty, ca.maxTokens, ca.timeoutSeconds, 0);
    }

    private LlmRouterBandit.Selected nextEligibleCloudSelection(LlmRouterBandit.Selected primary, CallArgs ca,
            java.util.Set<String> usedRoutes) {
        if (gatewayProbeService == null || !gatewayProbeService.cloudFallbackEnabled()
                || props == null || props.getModels() == null) return null;
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        LlmRouterBandit.Selected cursor = primary;
        for (int i = 0; i < Math.min(64, props.getModels().size()); i++) {
            cursor = fallbackSelection(cursor);
            if (cursor == null || !ordered.add(cursor.key())) break;
        }
        props.getModels().keySet().stream().sorted().forEach(ordered::add);
        java.util.Set<String> usedEndpoints = new java.util.HashSet<>();
        for (String key : usedRoutes) {
            var cfg = props.getModels().get(key);
            if (cfg != null) usedEndpoints.add(endpointModelIdentity(cfg));
        }
        for (String key : ordered) {
            var cfg = props.getModels().get(key);
            if (cfg == null || !cfg.isEnabled() || usedRoutes.contains(key)
                    || usedEndpoints.contains(endpointModelIdentity(cfg))) continue;
            String provider = firstNonBlank(cfg.getProvider(), providerForBaseUrl(cfg.getBaseUrl()));
            if ("local".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider)) continue;
            RoutingEligibility eligibility = gatewayProbeService.evaluate(key, cfg, stage(ca, cfg));
            if (eligibility == null || !eligibility.eligible()) continue;
            if (!preservesRequirements(primary.cfg(), cfg, eligibility)) {
                TraceStore.put("llm.gateway.fallback.skippedReason", "capability_unverified"); continue;
            }
            if (localLlmProcessManager != null && !localLlmProcessManager.isAvailable(cfg.getBaseUrl())) continue;
            return new LlmRouterBandit.Selected(key, cfg);
        }
        return null;
    }

    private static boolean hasCapabilityRequirements(LlmRouterProperties.ModelConfig cfg) {
        return cfg != null && (cfg.isManagedFileSearch() || "vision".equalsIgnoreCase(cfg.getStage())
                || (cfg.getMinContextTokens() != null && cfg.getMinContextTokens() > 0));
    }

    private static boolean preservesRequirements(LlmRouterProperties.ModelConfig original,
            LlmRouterProperties.ModelConfig candidate, RoutingEligibility eligibility) {
        if (original == null) return true;
        Integer requiredContext = original.getMinContextTokens();
        if (requiredContext != null && requiredContext > 0) {
            Object available = eligibility.safeMeta().get("contextTokens");
            if (!(available instanceof Number n) || n.longValue() < requiredContext) return false;
        }
        if (original.isManagedFileSearch() && (!candidate.isManagedFileSearch()
                || !endpointModelIdentity(original).equals(endpointModelIdentity(candidate)))) return false;
        if ("vision".equalsIgnoreCase(original.getStage()) && !"vision".equalsIgnoreCase(candidate.getStage())) {
            Object capabilities = eligibility.safeMeta().get("capabilities");
            if (!(capabilities instanceof java.util.Collection<?> caps)
                    || caps.stream().noneMatch(cap -> "vision".equalsIgnoreCase(String.valueOf(cap)))) return false;
        }
        return true;
    }

    private static String endpointModelIdentity(LlmRouterProperties.ModelConfig cfg) {
        return ModelRuntimeHealthTracker.endpointIdentityHash(cfg.getBaseUrl()) + ":"
                + (cfg.getName() == null ? "" : cfg.getName().trim().toLowerCase(Locale.ROOT));
    }

    private ChatModel recordOutcomes(ChatModel model, String key) {
        if (model == null || model instanceof ExpectedFailureChatModel || bandit == null || !StringUtils.hasText(key)) {
            return model;
        }
        if (model instanceof RecordingChatModel) {
            return model;
        }
        RecordingChatModel recording = new RecordingChatModel(model, bandit, key, gatewayFailureClassifier);
        var config = props.getModels() == null ? null : props.getModels().get(key);
        if (apiFailureRecorder != null && config != null) {
            String provider = firstNonBlank(config.getProvider(), providerForBaseUrl(config.getBaseUrl()));
            recording.incidentReporter = (success, failureClass) -> {
                if (success) apiFailureRecorder.recordSuccess(provider, config.getName());
                else apiFailureRecorder.recordFailureClass(provider, config.getName(), failureClass.name());
            };
        }
        if (localLlmProcessManager != null && config != null
                && localLlmProcessManager.managesEndpoint(config.getBaseUrl())) {
            recording.onFailure = failure -> localLlmProcessManager.requestRecovery(failure.name());
        }
        return recording;
    }

    /** Original-route failure stays on record even when a fallback later serves the user. */
    private void reportMaskedFallback(String fromKey, String toKey) {
        var recorder = apiFailureRecorder;
        if (recorder == null || props == null || props.getModels() == null) return;
        var from = props.getModels().get(fromKey);
        var to = props.getModels().get(toKey);
        if (from == null || to == null) return;
        recorder.markMasked(
                firstNonBlank(from.getProvider(), providerForBaseUrl(from.getBaseUrl())), from.getName(),
                firstNonBlank(to.getProvider(), providerForBaseUrl(to.getBaseUrl())), to.getName());
    }

    /** Route-level failures (local endpoint/GPU/eligibility) are recorded before any fallback can hide them. */
    private void recordRouteFailure(String key, String failureClass) {
        var recorder = apiFailureRecorder;
        if (recorder == null || props == null || props.getModels() == null) return;
        var cfg = props.getModels().get(key);
        if (cfg == null) return;
        recorder.recordFailureClass(
                firstNonBlank(cfg.getProvider(), providerForBaseUrl(cfg.getBaseUrl())), cfg.getName(), failureClass);
    }

    private LlmRouterBandit.Selected eligibleFallbackSelection(LlmRouterBandit.Selected selected, CallArgs ca) {
        LlmRouterBandit.Selected fallback = fallbackSelection(selected);
        if (fallback != null && gatewayProbeService.isEnforce()) {
            RoutingEligibility eligibility = gatewayProbeService.evaluate(
                    fallback.key(), fallback.cfg(), stage(ca, fallback.cfg()));
            if (gatewayBreadcrumbPublisher != null) {
                gatewayBreadcrumbPublisher.publishEligibility(eligibility);
            }
            if (eligibility == null || !eligibility.eligible()) {
                return null;
            }
        }
        return fallback;
    }

    private LlmRouterBandit.Selected fallbackSelection(LlmRouterBandit.Selected selected) {
        if (selected == null || props == null || props.getModels() == null
                || gatewayProbeService == null || !gatewayProbeService.cloudFallbackEnabled()) {
            return null;
        }
        String fallbackKey = firstNonBlank(
                selected.cfg() == null ? null : selected.cfg().getFallbackKey(),
                gatewayProbeService.cloudRouteKey());
        if (fallbackKey == null || fallbackKey.equals(selected.key())) {
            return null;
        }
        LlmRouterProperties.ModelConfig fallbackCfg = props.getModels().get(fallbackKey);
        if (fallbackCfg == null || !fallbackCfg.isEnabled()) {
            return null;
        }
        return new LlmRouterBandit.Selected(fallbackKey, fallbackCfg);
    }

    private boolean hasConfiguredDeviceFallback(LlmRouterBandit.Selected selected) {
        if (selected == null || selected.cfg() == null || props == null || props.getModels() == null) {
            return false;
        }
        String fallbackKey = trimToNull(selected.cfg().getDeviceFallbackKey());
        if (fallbackKey == null || fallbackKey.equals(selected.key())) {
            return false;
        }
        LlmRouterProperties.ModelConfig fallbackCfg = props.getModels().get(fallbackKey);
        return fallbackCfg != null && fallbackCfg.isEnabled();
    }

    private ChatModel routeWhenAllAutoLocalEndpointsOpen(CallArgs callArgs) {
        if (callArgs == null
                || !isAutoRouterRequest(callArgs.requestedModelId)
                || gatewayProbeService == null
                || !gatewayProbeService.isEnforce()
                || props == null
                || props.getModels() == null
                || props.getModels().isEmpty()) {
            return null;
        }
        int localCandidates = 0;
        int quarantinedLocalCandidates = 0;
        for (Map.Entry<String, LlmRouterProperties.ModelConfig> entry : props.getModels().entrySet()) {
            String key = entry.getKey();
            LlmRouterProperties.ModelConfig cfg = entry.getValue();
            if (!StringUtils.hasText(key)
                    || cfg == null
                    || !cfg.isEnabled()
                    || cfg.isFallbackOnly()
                    || cfg.getWeight() <= 0.0d) {
                continue;
            }
            RoutingEligibility eligibility = gatewayProbeService.evaluate(key, cfg, stage(callArgs, cfg));
            if (eligibility == null || !isLocalEligibilityProvider(eligibility.provider())) {
                continue;
            }
            localCandidates++;
            if (eligibility.failureClasses().contains(LlmFailureClass.GPU_DEVICE_LOST)) {
                quarantinedLocalCandidates++;
            }
        }
        if (localCandidates == 0 || quarantinedLocalCandidates != localCandidates) {
            return null;
        }

        LlmRouterBandit.Selected cloud = explicitCloudFallbackSelection();
        if (cloud != null) {
            RoutingEligibility cloudEligibility = gatewayProbeService.evaluate(
                    cloud.key(), cloud.cfg(), stage(callArgs, cloud.cfg()));
            if (cloudEligibility == null || !cloudEligibility.eligible()
                    || isLocalEligibilityProvider(cloudEligibility.provider())) {
                cloud = nextEligibleCloudSelection(cloud, callArgs, java.util.Set.of(cloud.key()));
                cloudEligibility = cloud == null ? null : gatewayProbeService.evaluate(
                        cloud.key(), cloud.cfg(), stage(callArgs, cloud.cfg()));
            }
            if (cloudEligibility != null
                    && cloudEligibility.eligible()
                    && !isLocalEligibilityProvider(cloudEligibility.provider())) {
                if (gatewayBreadcrumbPublisher != null) {
                    gatewayBreadcrumbPublisher.publishFallback(
                            "auto", cloud.key(), LlmFailureClass.GPU_DEVICE_LOST,
                            "all_local_endpoints_open");
                }
                recordPreselectionFallback("auto", cloud.key(), "all_local_endpoints_open");
                return routeWithGateway(cloud, callArgs);
            }
        }
        failGpuDeviceLost("auto", null);
        return null;
    }

    private LlmRouterBandit.Selected explicitCloudFallbackSelection() {
        if (gatewayProbeService == null
                || !gatewayProbeService.cloudFallbackEnabled()
                || props == null
                || props.getModels() == null) {
            return null;
        }
        String key = trimToNull(gatewayProbeService.cloudRouteKey());
        if (key == null) {
            return null;
        }
        LlmRouterProperties.ModelConfig cfg = props.getModels().get(key);
        return cfg == null || !cfg.isEnabled() ? null : new LlmRouterBandit.Selected(key, cfg);
    }

    private static boolean isAutoRouterRequest(String requestedModelId) {
        if (requestedModelId == null) {
            return false;
        }
        String normalized = requestedModelId.trim().toLowerCase(Locale.ROOT);
        return "llmrouter".equals(normalized)
                || "llmrouter.auto".equals(normalized)
                || "llmrouter.".equals(normalized);
    }

    private static boolean isLocalEligibilityProvider(String provider) {
        return provider == null
                || provider.isBlank()
                || "local".equalsIgnoreCase(provider)
                || "ollama".equalsIgnoreCase(provider);
    }

    private LlmRouterBandit.Selected deviceFallbackSelection(
            LlmRouterBandit.Selected selected,
            CallArgs callArgs,
            RoutingEligibility primaryEligibility) {
        if (selected == null
                || selected.cfg() == null
                || props == null
                || props.getModels() == null
                || gatewayProbeService == null
                || primaryEligibility == null
                || !primaryEligibility.failureClasses().contains(LlmFailureClass.GPU_DEVICE_LOST)) {
            return null;
        }
        LlmRouterBandit.Selected fallback = validatedDeviceFallbackSelection(selected, callArgs, true);
        if (fallback != null) {
            traceDeviceFallbackSelection(selected, fallback);
        }
        return fallback;
    }

    private LlmRouterBandit.Selected runtimeDeviceFallbackSelection(
            LlmRouterBandit.Selected selected,
            CallArgs callArgs) {
        return validatedDeviceFallbackSelection(selected, callArgs, false);
    }

    private LlmRouterBandit.Selected validatedDeviceFallbackSelection(
            LlmRouterBandit.Selected selected,
            CallArgs callArgs,
            boolean failClosed) {
        if (selected == null
                || selected.cfg() == null
                || props == null
                || props.getModels() == null
                || gatewayProbeService == null) {
            return null;
        }
        LlmRouterProperties.ModelConfig primaryCfg = selected.cfg();
        String fallbackKey = trimToNull(primaryCfg.getDeviceFallbackKey());
        if (fallbackKey == null) {
            return null;
        }
        LlmRouterProperties.ModelConfig fallbackCfg = props.getModels().get(fallbackKey);
        if (fallbackKey.equals(selected.key()) || fallbackCfg == null || !fallbackCfg.isEnabled()) {
            return rejectDeviceFallback(
                    selected, "device_fallback_unavailable", failClosed);
        }

        String primaryProvider = trimToNull(primaryCfg.getProvider());
        String fallbackProvider = trimToNull(fallbackCfg.getProvider());
        String primaryRole = trimToNull(primaryCfg.getDeviceRole());
        String fallbackRole = trimToNull(fallbackCfg.getDeviceRole());
        String primaryEndpointHash = ModelRuntimeHealthTracker.endpointIdentityHash(primaryCfg.getBaseUrl());
        String fallbackEndpointHash = ModelRuntimeHealthTracker.endpointIdentityHash(fallbackCfg.getBaseUrl());
        boolean localProviders = isExplicitLocalProvider(primaryProvider)
                && isExplicitLocalProvider(fallbackProvider);
        boolean distinctRoles = primaryRole != null
                && fallbackRole != null
                && !primaryRole.equalsIgnoreCase(fallbackRole);
        boolean distinctEndpoints = !"unknown".equals(primaryEndpointHash)
                && !"unknown".equals(fallbackEndpointHash)
                && !primaryEndpointHash.equals(fallbackEndpointHash);
        if (!localProviders || !distinctRoles || !distinctEndpoints) {
            return rejectDeviceFallback(selected, "gpu_role_ambiguous", failClosed);
        }

        String primaryStage = stage(callArgs, primaryCfg);
        String fallbackStage = stage(callArgs, fallbackCfg);
        if (!primaryStage.equalsIgnoreCase(fallbackStage)) {
            return rejectDeviceFallback(
                    selected, "device_fallback_stage_mismatch", failClosed);
        }
        RoutingEligibility fallbackEligibility = gatewayProbeService.evaluate(
                fallbackKey,
                fallbackCfg,
                fallbackStage);
        if (gatewayBreadcrumbPublisher != null) {
            gatewayBreadcrumbPublisher.publishEligibility(fallbackEligibility);
        }
        if (fallbackEligibility == null) {
            return rejectDeviceFallback(
                    selected, "device_fallback_eligibility_unavailable", failClosed);
        }
        if (!fallbackEligibility.eligible()) {
            return rejectDeviceFallback(
                    selected,
                    "device_fallback_ineligible_"
                            + fallbackEligibility.primaryFailure().name().toLowerCase(Locale.ROOT),
                    failClosed);
        }
        return new LlmRouterBandit.Selected(fallbackKey, fallbackCfg);
    }

    private LlmRouterBandit.Selected rejectDeviceFallback(
            LlmRouterBandit.Selected selected,
            String reason,
            boolean failClosed) {
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "device_fallback_rejected");
        TraceStore.put("llm.localEndpoint.deviceFallbackRejected", true);
        TraceStore.put("llm.localEndpoint.deviceFallbackRejectedReason", safeReason);
        if (failClosed) {
            failRoute(
                    selected == null ? null : selected.key(),
                    selected == null || selected.cfg() == null
                            ? null
                            : selected.cfg().getBaseUrl(),
                    safeReason);
        }
        return null;
    }

    private static void traceDeviceFallbackSelection(
            LlmRouterBandit.Selected selected,
            LlmRouterBandit.Selected fallback) {
        LlmRouterProperties.ModelConfig primaryCfg = selected.cfg();
        LlmRouterProperties.ModelConfig fallbackCfg = fallback.cfg();
        String primaryRole = trimToNull(primaryCfg.getDeviceRole());
        String fallbackRole = trimToNull(fallbackCfg.getDeviceRole());
        String primaryEndpointHash = ModelRuntimeHealthTracker.endpointIdentityHash(primaryCfg.getBaseUrl());
        String fallbackEndpointHash = ModelRuntimeHealthTracker.endpointIdentityHash(fallbackCfg.getBaseUrl());
        TraceStore.put("llm.localEndpoint.selectionDecision", "device_fallback");
        TraceStore.put("llm.localEndpoint.sourceEndpointHash", primaryEndpointHash);
        TraceStore.put("llm.localEndpoint.fallbackEndpointHash", fallbackEndpointHash);
        TraceStore.put("llm.localEndpoint.sourceRoleHash", SafeRedactor.hashValue(primaryRole));
        TraceStore.put("llm.localEndpoint.fallbackRoleHash", SafeRedactor.hashValue(fallbackRole));
    }

    private static boolean isExplicitLocalProvider(String provider) {
        return "local".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider);
    }

    private static String stage(CallArgs ca, LlmRouterProperties.ModelConfig cfg) {
        if (cfg != null && StringUtils.hasText(cfg.getStage())) {
            return cfg.getStage().trim();
        }
        if (ca == null || ca.requestedModelId == null) {
            return "chat";
        }
        String requested = ca.requestedModelId.toLowerCase(Locale.ROOT);
        if (requested.contains("judge") || requested.contains("critic")) {
            return "judge";
        }
        if (requested.contains("coder")) {
            return "coder";
        }
        if (requested.contains("vision")) {
            return "vision";
        }
        return "chat";
    }

    private ChatModel buildRoutedModel(
            LlmRouterBandit.Selected sel,
            CallArgs ca,
            boolean ambiguousRouteAttempts) {
        return buildRoutedModel(sel, ca, ambiguousRouteAttempts, null, "primary");
    }

    @Around("@annotation(org.springframework.context.annotation.Bean) && execution(dev.langchain4j.model.chat.ChatModel *(..))")
    public Object aroundSpringChatModelBean(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (!(result instanceof ChatModel model)
                || model instanceof ExpectedFailureChatModel
                || modelRuntimeHealthTracker == null) {
            return result;
        }
        String beanMethod = pjp.getSignature() == null ? "spring_chat_model" : pjp.getSignature().getName();
        String beanProtocol = model instanceof com.example.lms.llm.OllamaNativeChatModel
                ? "ollama_native"
                : "openai_chat_completions";
        String beanProvider = "ollama_native".equals(beanProtocol)
                ? "ollama"
                : "local_openai_compatible";
        Object[] beanArgs = pjp.getArgs();
        String configuredModel = stringArg(beanArgs, 2);
        return modelRuntimeHealthTracker.decorateRequestAttempt(
                model,
                "primary",
                modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                        "spring_bean", configuredModel, null, beanProtocol),
                springBeanOptionEnvelope(
                        beanMethod, beanProvider, configuredModel, beanProtocol, beanArgs));
    }

    private static Map<String, Object> springBeanOptionEnvelope(
            String beanMethod,
            String provider,
            String configuredModel,
            String protocol,
            Object[] args) {
        Map<String, Object> owned = new java.util.LinkedHashMap<>();
        boolean judge = "judgeChatModel".equals(beanMethod);
        owned.put("temperature", judge ? 0.0d : arg(args, 3));
        Object timeoutSeconds = arg(args, judge ? 3 : 4);
        owned.put("timeoutMs", timeoutSeconds instanceof Number number
                ? Math.max(1L, number.longValue()) * 1_000L
                : null);
        int retryIndex = "chatModel".equals(beanMethod) ? 6 : (judge ? 4 : 5);
        owned.put("maxRetries", arg(args, retryIndex));
        int tokenIndex = switch (beanMethod) {
            case "chatModel" -> 5;
            case "fastChatModel", "exploreChatModel", "highModel" -> 6;
            case "judgeChatModel" -> 5;
            default -> -1;
        };
        Object tokenValue = arg(args, tokenIndex);
        if ("ollama_native".equals(protocol)
                || !OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(configuredModel, stringArg(args, 0))) {
            owned.put("maxOutputTokens", tokenValue);
        } else {
            owned.put("maxTokens", tokenValue);
        }
        return ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                provider, configuredModel, protocol, owned);
    }

    private static Object arg(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length ? args[index] : null;
    }

    private static String stringArg(Object[] args, int index) {
        Object value = arg(args, index);
        return value instanceof String stringValue ? stringValue : null;
    }

    private ChatModel buildRoutedModel(
            LlmRouterBandit.Selected sel,
            CallArgs ca,
            boolean ambiguousRouteAttempts,
            Consumer<ModelRuntimeHealthTracker.RequestAttemptRoute> attemptRouteObserver,
            String attemptRole) {
        return buildRoutedModel(sel, ca, ambiguousRouteAttempts, attemptRouteObserver, attemptRole, 0);
    }

    private ChatModel buildRoutedModel(
            LlmRouterBandit.Selected sel, CallArgs ca, boolean ambiguousRouteAttempts,
            Consumer<ModelRuntimeHealthTracker.RequestAttemptRoute> attemptRouteObserver,
            String attemptRole, int remainingRoutes) {
        String key = sel.key();
        LlmRouterProperties.ModelConfig cfg = sel.cfg();

        if (cfg == null) {
            failRoute(key, null, "missing_route_config");
        }
        if (!cfg.isEnabled()) {
            failRoute(key, cfg.getBaseUrl(), "route_disabled");
        }

        String modelName = trimToNull(cfg.getName());
        String rawBaseUrl = trimToNull(cfg.getBaseUrl());
        if (modelName == null || rawBaseUrl == null) {
            failRoute(key, rawBaseUrl, "missing_route_config");
        }

        String baseUrl = normalizeBaseUrl(rawBaseUrl);
        long routeTimeoutMs = routeTimeoutMillis(ca.cueTimeoutMs > 0 ? ca.cueTimeoutMs : Math.max(1_000L, ca.timeoutMs), attemptRole);
        TimeBudget sharedBudget = TimeBudgetContext.get();
        if (remainingRoutes > 0 && sharedBudget != null)
            routeTimeoutMs = Math.max(1, Math.min(routeTimeoutMs, sharedBudget.remainingMillis() / (remainingRoutes + 1)));
        if (ambiguousRouteAttempts) {
            TimeBudget budget = TimeBudgetContext.get();
            if (budget != null) routeTimeoutMs = Math.max(1, Math.min(routeTimeoutMs, budget.remainingMillis() / 3));
            ca = withoutLibraryRetries(ca);
        }

        // Model-guard: prevent Responses-only models from hitting /v1/chat/completions.
        if (modelGuardProps != null && modelGuardProps.isEnabled()
                && ModelGuardSupport.isResponsesOnlyModel(modelName, modelGuardProps.getResponsesOnlyPrefixes())
                && (!modelGuardProps.isOpenAiBaseOnly() || ModelGuardSupport.looksLikeOpenAiBaseUrl(baseUrl))) {

            try {
                String canonicalModel = ModelGuardSupport.canonicalModelName(modelName);
                TraceStore.put("llm.modelGuard.requestedModelHash", SafeRedactor.hashValue(canonicalModel));
                TraceStore.put("llm.modelGuard.requestedModelLength", canonicalModel == null ? 0 : canonicalModel.length());
                TraceStore.put("llm.modelGuard.mode", modelGuardProps.getMode().name());
            } catch (Exception ignore) {
                traceSuppressed("modelGuard.requestedModel", ignore);
            }

            switch (modelGuardProps.getMode()) {
                case FAIL_FAST:
                    return expectedFailure(
                            modelName,
                            baseUrl,
                            "/v1/chat/completions",
                            "FAIL_FAST",
                            "responses_only_model_on_chat_completions_endpoint",
                            attemptRole,
                            cfg,
                            ca);
                case SUBSTITUTE_CHAT:
                    String sub = modelGuardProps.getSubstituteChatModel();
                    if (!StringUtils.hasText(sub)) {
                        sub = get("llm.chat-model");
                    }
                    if (!StringUtils.hasText(sub)) {
                        return expectedFailure(
                                modelName,
                                baseUrl,
                                "/v1/chat/completions",
                                "SUBSTITUTE_CHAT(no_substitute_configured)",
                                "responses_only_model_on_chat_completions_endpoint",
                                attemptRole,
                                cfg,
                                ca);
                    }
                    if (StringUtils.hasText(sub)) {
                        modelName = sub.trim();
                        try {
                            TraceStore.put("llm.modelGuard.substituteChatModelHash", SafeRedactor.hashValue(modelName));
                            TraceStore.put("llm.modelGuard.substituteChatModelLength", modelName.length());
                        } catch (Exception ignore) {
                            traceSuppressed("modelGuard.substituteChatModel", ignore);
                        }
                        if (ModelGuardSupport.isResponsesOnlyModel(modelName, modelGuardProps.getResponsesOnlyPrefixes())) {
                            return expectedFailure(
                                    cfg.getName(),
                                    baseUrl,
                                    "/v1/chat/completions",
                                    "SUBSTITUTE_CHAT(no_chat_compatible_substitute)",
                                    "responses_only_model_on_chat_completions_endpoint",
                                    attemptRole,
                                    cfg,
                                    ca);
                        }
                    }
                    break;
                case ROUTE_RESPONSES:
                    String apiKey = resolveOpenAiApiKey();
                    if (!StringUtils.hasText(apiKey)) {
                        return expectedFailure(
                                modelName,
                                baseUrl,
                                "/v1/responses",
                                "ROUTE_RESPONSES(no_api_key)",
                                "no_api_key_for_responses_endpoint",
                                attemptRole,
                                cfg,
                                ca);
                    }
                    ModelRuntimeHealthTracker.RequestAttemptRoute directAttemptRoute = modelRuntimeHealthTracker == null
                            ? null
                            : modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                    key, modelName, baseUrl, "openai_responses");
                    ChatModel responseModel = verifyResponseModelIfRequired(
                            new OpenAiResponsesChatModel(baseUrl, apiKey, modelName, routeTimeoutMs,
                                    modelRuntimeHealthTracker, attemptRole, directAttemptRoute, null, ca.maxTokens),
                            modelName,
                            cfg);
                    publishSelectedRoute(
                            key,
                            modelName,
                            baseUrl,
                            "openai_responses",
                            cfg,
                            ambiguousRouteAttempts,
                            attemptRouteObserver);
                    return decorateRoutedAttempt(responseModel, attemptRole, key, modelName, baseUrl,
                            "openai_responses", ca, cfg);
            }
        }

        Double temperature = ModelCapabilities.sanitizeTemperature(modelName, ca.temperature);
        Double topP = ModelCapabilities.sanitizeTopP(modelName, ca.topP);
        Double freq = ModelCapabilities.sanitizeFrequencyPenalty(modelName, ca.frequencyPenalty);
        Double pres = ModelCapabilities.sanitizePresencePenalty(modelName, ca.presencePenalty);

        if (isGeminiRoute(cfg, baseUrl)) {
            if (geminiGateway == null) {
                return expectedFailure(
                        modelName,
                        baseUrl,
                        "/v1/chat/completions",
                        "GEMINI_GATEWAY_UNAVAILABLE",
                        "gemini_gateway_unavailable",
                        attemptRole,
                        cfg,
                        ca);
            }
            LlmRouterContext.set(key, baseUrl, modelName);
            ChatModel gatewayModel = geminiGateway.buildOpenAiCompatibleChatModel(
                    new GeminiGateway.RouterSpec(
                            baseUrl,
                            modelName,
                            Duration.ofMillis(routeTimeoutMs),
                            ca.maxRetriesOverride == null ? 0 : Math.max(0, ca.maxRetriesOverride),
                            temperature,
                            topP,
                            freq,
                            pres,
                            ca.maxTokens), ca.cueJson, ca.cueJsonSchema);
            GeminiGateway.ProviderStatus gatewayStatus = geminiGateway.latestStatus();
            if (gatewayStatus == null || !gatewayStatus.enabled()) {
                LlmRouterContext.clear();
            }
            traceGeminiGatewayRoute(key, baseUrl, gatewayStatus);
            ChatModel routedModel = verifyResponseModelIfRequired(gatewayModel, modelName, cfg);
            publishSelectedRoute(
                    key,
                    modelName,
                    baseUrl,
                    "openai_chat_completions",
                    cfg,
                    ambiguousRouteAttempts,
                    attemptRouteObserver);
            return decorateRoutedAttempt(routedModel, attemptRole, key, modelName, baseUrl,
                    "openai_chat_completions", ca, cfg);
        }

        String apiKey = resolveApiKeyForBaseUrl(baseUrl);
        boolean localGatewayRoute = isLocalGatewayRoute(baseUrl, modelName);
        if (localGatewayRoute) {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    baseUrl,
                    bool("llm.provider-guard.allow-private-remote", "LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE", false),
                    gatewayAllowedHosts(),
                    bool("llm.provider-guard.require-auth-for-remote", "LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE", true),
                    apiKey,
                    ownerToken());
        }

        // carry to request end (for success/fail recording)
        LlmRouterContext.set(key, baseUrl, modelName);
        try {
            traceResolvedRoute(key, baseUrl, apiKey);
            traceEndpointDiagnostics("llmrouter.", baseUrl);
        } catch (Exception ignore) {
            traceSuppressed("endpoint.trace", ignore);
        }

        if (localGatewayRoute && OllamaNativeChatModel.supportsThinkFalseRoute(
                bool("llm.ollama-native.think-false.enabled",
                        "LLM_OLLAMA_NATIVE_THINK_FALSE_ENABLED",
                        true),
                modelName,
                baseUrl)) {
            ChatModel routedModel = verifyResponseModelIfRequired(
                    OllamaNativeChatModel.forRoutedRequest(
                            baseUrl,
                            modelName,
                            Duration.ofMillis(routeTimeoutMs),
                            ca.maxTokens != null && ca.maxTokens > 0 ? ca.maxTokens : null,
                            temperature,
                            topP,
                            ollamaNativeNumGpu(),
                            modelRuntimeHealthTracker,
                            attemptRole),
                    modelName,
                    cfg);
            publishSelectedRoute(
                    key,
                    modelName,
                    baseUrl,
                    "ollama_native",
                    cfg,
                    ambiguousRouteAttempts,
                    attemptRouteObserver);
            return decorateRoutedAttempt(routedModel, attemptRole, key, modelName, baseUrl,
                    "ollama_native", ca, cfg);
        }

        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .httpClientBuilder(modelRuntimeHealthTracker == null
                        ? dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder()
                        : modelRuntimeHealthTracker.observedHttpClientBuilder(attemptRole))
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(ca.maxRetriesOverride == null ? 0 : Math.max(0, ca.maxRetriesOverride))
                .timeout(Duration.ofMillis(routeTimeoutMs));

        if (localGatewayRoute && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, gatewayAllowedHosts())) {
            Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(
                    ownerTokenHeader(),
                    ownerToken());
            if (!headers.isEmpty()) {
                b.customHeaders(headers);
                try {
                    traceEndpointDiagnostics("llmrouter.", baseUrl);
                    TraceStore.put("llmrouter.hasOwnerToken", true);
                    TraceStore.put("llmrouter.api.hasOwnerToken", true);
                } catch (Exception ignore) {
                    traceSuppressed("ownerToken.trace", ignore);
                }
            }
        }

        if (temperature != null) {
            b.temperature(temperature);
        }
        if (topP != null) {
            b.topP(topP);
        }

        if (freq != null) {
            b.frequencyPenalty(freq);
        }
        if (pres != null) {
            b.presencePenalty(pres);
        }

        if (ca.maxTokens != null && ca.maxTokens > 0) {
            String tokenParam = OpenAiTokenParamCompat.tokenParamKey(modelName, baseUrl);
            if ("max_tokens".equals(tokenParam)) {
                b.maxTokens(ca.maxTokens);
            } else if ("max_completion_tokens".equals(tokenParam)) {
                b.maxCompletionTokens(ca.maxTokens);
            }
        }

        if (ca.cueJson) {
            var json = dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                    .responseFormat(ca.cueJsonSchema != null && "openai".equalsIgnoreCase(cfg.getProvider())
                            ? dev.langchain4j.model.chat.request.ResponseFormat.builder()
                                    .type(dev.langchain4j.model.chat.request.ResponseFormatType.JSON)
                                    .jsonSchema(ca.cueJsonSchema).build()
                            : dev.langchain4j.model.chat.request.ResponseFormat.JSON);
            if (modelName.contains("gpt-oss") || modelName.startsWith("gpt-5.6")) json.reasoningEffort("low");
            if ("openai".equalsIgnoreCase(cfg.getProvider())) json.serviceTier("default");
            b.defaultRequestParameters(json.build());
        }

        // Safety: ensure modelName is not dropped by later builder mutations (e.g.,
        // maxTokens)
        b.modelName(modelName);

        ChatModel routedModel = verifyResponseModelIfRequired(b.build(), modelName, cfg);
        publishSelectedRoute(
                key,
                modelName,
                baseUrl,
                "openai_chat_completions",
                cfg,
                ambiguousRouteAttempts,
                attemptRouteObserver);
        return decorateRoutedAttempt(routedModel, attemptRole, key, modelName, baseUrl,
                "openai_chat_completions", ca, cfg);
    }

    private static long routeTimeoutMillis(long configuredTimeoutMs, String attemptRole) {
        long safeConfiguredTimeoutMs = Math.max(1L, configuredTimeoutMs);
        if (!"fallback".equals(attemptRole)) {
            return safeConfiguredTimeoutMs;
        }
        TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget == null) {
            return safeConfiguredTimeoutMs;
        }
        long remainingMs = Math.max(0L, requestBudget.remainingMillis());
        return remainingMs <= 0L
                ? 1L
                : Math.min(safeConfiguredTimeoutMs, remainingMs);
    }

    private ChatModel decorateRoutedAttempt(
            ChatModel model,
            String role,
            String routeKey,
            String modelName,
            String baseUrl,
            String protocol,
            CallArgs callArgs,
            LlmRouterProperties.ModelConfig cfg) {
        if (gatewayProbeService != null && gatewayProbeService.localFailoverEnabled() && cfg != null
                && ("local".equalsIgnoreCase(cfg.getProvider()) || "ollama".equalsIgnoreCase(cfg.getProvider())
                || (!StringUtils.hasText(cfg.getProvider()) && isLocalGatewayRoute(baseUrl, modelName)))) {
            model = gatewayProbeService.guardLocalModel(model, baseUrl, modelName, cfg.getDeviceRole());
        }
        if (modelRuntimeHealthTracker == null || model == null) {
            return model;
        }
        Map<String, Object> ownedOptions = new java.util.LinkedHashMap<>();
        ownedOptions.put("temperature", callArgs == null ? null : callArgs.temperature);
        ownedOptions.put("topP", callArgs == null ? null : callArgs.topP);
        ownedOptions.put("frequencyPenalty", callArgs == null ? null : callArgs.frequencyPenalty);
        ownedOptions.put("presencePenalty", callArgs == null ? null : callArgs.presencePenalty);
        if ("openai_responses".equals(protocol)
                || "ollama_native".equals(protocol)
                || !OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(modelName, baseUrl)) {
            ownedOptions.put("maxOutputTokens", callArgs == null ? null : callArgs.maxTokens);
        } else {
            ownedOptions.put("maxTokens", callArgs == null ? null : callArgs.maxTokens);
        }
        ownedOptions.put("timeoutMs", callArgs == null ? null : callArgs.timeoutMs);
        ownedOptions.put("maxRetries", callArgs == null ? null : callArgs.maxRetriesOverride);
        ownedOptions.put("fallbackEnabled", cfg == null ? null : StringUtils.hasText(cfg.getFallbackKey()));
        ownedOptions.put("fallbackKey", cfg == null ? null : cfg.getFallbackKey());
        return modelRuntimeHealthTracker.decorateRequestAttempt(
                model,
                role,
                modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                        routeKey, modelName, baseUrl, protocol),
                ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                        providerForAttempt(cfg, modelName, baseUrl, protocol),
                        modelName,
                        protocol,
                        ownedOptions));
    }

    private void publishSelectedRoute(
            String routeKey,
            String selectedModelId,
            String baseUrl,
            String protocol,
            LlmRouterProperties.ModelConfig cfg,
            boolean ambiguousRouteAttempts,
            Consumer<ModelRuntimeHealthTracker.RequestAttemptRoute> attemptRouteObserver) {
        if (modelRuntimeHealthTracker == null) {
            return;
        }
        ModelRuntimeHealthTracker.RequestAttemptRoute attemptRoute =
                modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                        routeKey,
                        selectedModelId,
                        baseUrl,
                        protocol);
        if (attemptRouteObserver != null) {
            attemptRouteObserver.accept(attemptRoute);
        }
        String timelineId = capturedRequestTimelineId();
        if (timelineId != null) {
            modelRuntimeHealthTracker.recordRequestSelection(
                    timelineId,
                    "router",
                    providerForAttempt(cfg, selectedModelId, baseUrl, protocol),
                    routeKey,
                    selectedModelId,
                    baseUrl,
                    protocol,
                    cfg != null && cfg.isResponseModelVerificationRequired(),
                    ambiguousRouteAttempts);
        }
    }

    private String capturedRequestTimelineId() {
        if (modelRuntimeHealthTracker == null
                || !Boolean.TRUE.equals(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY))) {
            return null;
        }
        Object rawTimelineId = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId);
        return timelineId.isBlank() ? null : timelineId;
    }

    private static ChatModel verifyResponseModelIfRequired(
            ChatModel model,
            String expectedModelName,
            LlmRouterProperties.ModelConfig cfg) {
        if (model == null || cfg == null || !cfg.isResponseModelVerificationRequired()) {
            return model;
        }
        return new ResponseModelVerifyingChatModel(model, expectedModelName, cfg.getResponseModelAliases());
    }

    private ExpectedFailureChatModel expectedFailure(String requestedModel,
                                                     String baseUrl,
                                                     String endpoint,
                                                     String actionTaken,
                                                     String failReason,
                                                     String role,
                                                     LlmRouterProperties.ModelConfig cfg,
                                                     CallArgs callArgs) {
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.endpoint", endpoint == null ? "unknown_endpoint" : endpoint);
        TraceStore.put("llm.modelGuard.failReason",
                SafeRedactor.traceLabelOrFallback(failReason, "unknown_model_guard_failure"));
        String protocol = endpoint != null && endpoint.contains("responses")
                ? "openai_responses"
                : "openai_chat_completions";
        Map<String, Object> ownedOptions = new java.util.LinkedHashMap<>();
        ownedOptions.put("temperature", callArgs == null ? null : callArgs.temperature);
        ownedOptions.put("topP", callArgs == null ? null : callArgs.topP);
        ownedOptions.put("frequencyPenalty", callArgs == null ? null : callArgs.frequencyPenalty);
        ownedOptions.put("presencePenalty", callArgs == null ? null : callArgs.presencePenalty);
        ownedOptions.put("maxOutputTokens", callArgs == null ? null : callArgs.maxTokens);
        ownedOptions.put("timeoutMs", callArgs == null ? null : callArgs.timeoutMs);
        ownedOptions.put("maxRetries", callArgs == null ? null : callArgs.maxRetriesOverride);
        ownedOptions.put("fallbackEnabled", cfg == null ? null : StringUtils.hasText(cfg.getFallbackKey()));
        ownedOptions.put("fallbackKey", cfg == null ? null : cfg.getFallbackKey());
        ModelRuntimeHealthTracker.ExpectedFailureAttemptEvidence attemptEvidence = modelRuntimeHealthTracker == null
                ? null
                : modelRuntimeHealthTracker.expectedFailureAttemptEvidence(
                        role,
                        modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                                "router_expected_failure",
                                requestedModel,
                                baseUrl,
                                protocol),
                        ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                                providerForAttempt(cfg, requestedModel, baseUrl, protocol),
                                requestedModel,
                                protocol,
                                ownedOptions));
        return new ExpectedFailureChatModel(
                ModelGuardSupport.buildExpectedFailureMessage(requestedModel, endpoint, actionTaken),
                SafeRedactor.hashValue(ModelGuardSupport.canonicalModelName(requestedModel)),
                attemptEvidence);
    }

    private static String providerForAttempt(
            LlmRouterProperties.ModelConfig cfg,
            String modelName,
            String baseUrl,
            String protocol) {
        if (cfg != null && StringUtils.hasText(cfg.getProvider())) {
            return cfg.getProvider().trim();
        }
        if ("openai_responses".equals(protocol) || ModelGuardSupport.looksLikeOpenAiBaseUrl(baseUrl)) {
            return "openai";
        }
        return ModelCapabilities.isLocalChatModelId(modelName) ? "local_openai_compatible" : "unknown";
    }

    private static boolean isGeminiRoute(LlmRouterProperties.ModelConfig cfg, String baseUrl) {
        return (cfg != null && "gemini".equalsIgnoreCase(trimToNull(cfg.getProvider())))
                || "gemini".equals(providerForBaseUrl(baseUrl));
    }

    private void traceGeminiGatewayRoute(
            String key,
            String baseUrl,
            GeminiGateway.ProviderStatus gatewayStatus) {
        try {
            TraceStore.put("llmrouter.route.key", SafeRedactor.traceLabelOrFallback(key, "route"));
            TraceStore.put("llmrouter.route.enabled", true);
            TraceStore.put("llmrouter.api.provider", "gemini");
            boolean enabled = gatewayStatus != null && gatewayStatus.enabled();
            TraceStore.put("llmrouter.api.providerDisabled", !enabled);
            TraceStore.put("llmrouter.api.failureClass",
                    gatewayStatus == null || gatewayStatus.errorClass().isBlank()
                            ? "none"
                            : gatewayStatus.errorClass());
            traceEndpointDiagnostics("llmrouter.api.", baseUrl);
            traceEndpointDiagnostics("llmrouter.", baseUrl);
            TraceStore.put("llmrouter.api.hasKey",
                    gatewayStatus != null && gatewayStatus.credentialPresent());
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            traceSuppressed("geminiGateway.trace", ignore);
        }
    }

    private String resolveLocalApiKey() {
        KeyResolver keyResolver = keyResolverProvider.getIfAvailable();
        ProviderCredentialResolver.Resolution resolution = keyResolver == null
                ? new ProviderCredentialResolver(env).resolve(ProviderCredentialResolver.Provider.LOCAL_LLM)
                : keyResolver.resolveLocalLlmCredential();
        if (!resolution.enabled() && resolution.credentialPresent()) {
            throw new IllegalStateException(
                    "provider=local_llm disabledReason=" + resolution.disabledReason());
        }
        return StringUtils.hasText(resolution.valueOrNull()) ? resolution.valueOrNull() : "ollama";
    }

    private String resolveOpenAiApiKey() {
        KeyResolver keyResolver = keyResolverProvider.getIfAvailable();
        ProviderCredentialResolver.Resolution resolution = keyResolver == null
                ? new ProviderCredentialResolver(env).resolve(ProviderCredentialResolver.Provider.OPENAI)
                : keyResolver.resolveOpenAiCredential();
        if (!resolution.enabled() && resolution.credentialPresent()) {
            throw new IllegalStateException(
                    "provider=openai disabledReason=" + resolution.disabledReason());
        }
        return resolution.valueOrNull();
    }

    private String resolveApiKeyForBaseUrl(String baseUrl) {
        String provider = providerForBaseUrl(baseUrl);
        if ("groq".equals(provider)) {
            return requireProviderKey(provider, resolveGroqApiKey(), "missing GROQ_API_KEY", baseUrl);
        }
        if ("mistral".equals(provider)) {
            return requireProviderKey(provider, resolveMistralApiKey(), "missing MISTRAL_API_KEY", baseUrl);
        }
        if ("anthropic".equals(provider)) {
            return failUnsupportedProvider(provider, "unsupported_anthropic_native_route", baseUrl);
        }
        if ("cerebras".equals(provider)) {
            return requireProviderKey(provider, resolveCerebrasApiKey(), "missing CEREBRAS_API_KEY", baseUrl);
        }
        if ("openrouter".equals(provider)) {
            return requireProviderKey(provider, resolveOpenRouterApiKey(), "missing OPENROUTER_API_KEY", baseUrl);
        }
        if ("opencode".equals(provider)) {
            return requireProviderKey(provider, resolveOpenCodeApiKey(), "missing OPENCODE_API_KEY", baseUrl);
        }
        if ("openai".equals(provider)) {
            return requireProviderKey(provider, resolveOpenAiApiKey(), "missing OPENAI_API_KEY", baseUrl);
        }
        // Otherwise, use local/generic API key.
        return resolveLocalApiKey();
    }

    private String resolveGroqApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return firstNonBlank(get("llm.groq.api-key"), get("GROQ_API_KEY"), System.getenv("GROQ_API_KEY"));
        }
        return kr.resolveGroqApiKeyStrict();
    }

    private String resolveMistralApiKey() {
        return resolveStrictProviderKey(
                "Mistral",
                "MISTRAL_API_KEY",
                "llm.mistral.api-key");
    }

    private String resolveCerebrasApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return firstNonBlank(get("llm.cerebras.api-key"), System.getenv("CEREBRAS_API_KEY"));
        }
        return kr.resolveCerebrasApiKeyStrict();
    }

    private String resolveOpenRouterApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return resolveStrictProviderKey(
                    "OpenRouter",
                    "OPENROUTER_API_KEY",
                    "llm.openrouter.api-key");
        }
        return kr.resolveOpenRouterApiKeyStrict();
    }

    private String resolveOpenCodeApiKey() {
        KeyResolver kr = keyResolverProvider.getIfAvailable();
        if (kr == null) {
            return resolveStrictProviderKey(
                    "OpenCode",
                    "OPENCODE_API_KEY",
                    "llm.opencode.api-key");
        }
        return kr.resolveOpenCodeApiKeyStrict();
    }

    private String requireProviderKey(String provider, String apiKey, String disabledReason, String baseUrl) {
        if (StringUtils.hasText(apiKey) && !looksLikePlaceholderKey(apiKey)) {
            return apiKey;
        }
        try {
            TraceStore.put("llmrouter.api.provider", provider);
            TraceStore.put("llmrouter.api.providerDisabled", true);
            TraceStore.put("llmrouter.api.failureClass", "provider-disabled");
            traceEndpointDiagnostics("llmrouter.api.", baseUrl);
            TraceStore.put("llmrouter.api.hasKey", false);
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
            TraceStore.put("llmrouter.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
        } catch (Exception ignore) {
            traceSuppressed("providerDisabled.trace", ignore);
        }
        throw new IllegalStateException("provider=" + provider + " disabledReason="
                + SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
    }

    private String failUnsupportedProvider(String provider, String disabledReason, String baseUrl) {
        try {
            TraceStore.put("llmrouter.api.provider", provider);
            TraceStore.put("llmrouter.api.providerDisabled", true);
            TraceStore.put("llmrouter.api.failureClass", "unsupported-provider");
            traceEndpointDiagnostics("llmrouter.api.", baseUrl);
            TraceStore.put("llmrouter.api.hasKey", false);
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
            TraceStore.put("llmrouter.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
        } catch (Exception ignore) {
            traceSuppressed("unsupportedProvider.trace", ignore);
        }
        throw new IllegalStateException("provider=" + provider + " disabledReason="
                + SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
    }

    private void failRoute(String key, String rawBaseUrl, String disabledReason) {
        try {
            String baseUrl = normalizeBaseUrl(rawBaseUrl);
            TraceStore.put("llmrouter.route.key", SafeRedactor.traceLabelOrFallback(key, "route"));
            TraceStore.put("llmrouter.route.enabled", false);
            TraceStore.put("llmrouter.api.provider", providerForBaseUrl(baseUrl));
            TraceStore.put("llmrouter.api.providerDisabled", true);
            TraceStore.put("llmrouter.api.failureClass", "provider-disabled");
            traceEndpointDiagnostics("llmrouter.api.", baseUrl);
            TraceStore.put("llmrouter.api.hasKey", false);
            TraceStore.put("llmrouter.api.hasOwnerToken", false);
            TraceStore.put("llmrouter.api.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
        } catch (Exception ignore) {
            traceSuppressed("failRoute.trace", ignore);
        }
        String message = "provider=llmrouter." + SafeRedactor.traceLabelOrFallback(key, "route") + " disabledReason="
                + SafeRedactor.traceLabelOrFallback(disabledReason, "unknown");
        if ("route_disabled".equals(disabledReason)) {
            throw new LlmGatewayException(message, LlmFailureClass.DISABLED, "route_disabled");
        }
        throw new IllegalStateException(message);
    }

    private void failGpuDeviceLost(String key, String rawBaseUrl) {
        recordRouteFailure(key, "GPU_DEVICE_LOST");
        String endpointHash = ModelRuntimeHealthTracker.endpointIdentityHash(rawBaseUrl);
        TraceStore.put("llmrouter.route.key", SafeRedactor.traceLabelOrFallback(key, "route"));
        TraceStore.put("llmrouter.route.enabled", false);
        TraceStore.put("llmrouter.api.providerDisabled", true);
        TraceStore.put("llmrouter.api.failureClass", "gpu_device_lost");
        TraceStore.put("llmrouter.api.disabledReason", "gpu_device_lost");
        TraceStore.put("llm.localEndpoint.endpointHash", endpointHash);
        throw new LlmGatewayException(
                "Local endpoint quarantined endpointHash=" + endpointHash,
                LlmFailureClass.GPU_DEVICE_LOST,
                "gpu_device_lost");
    }

    private static void traceResolvedRoute(String key, String baseUrl, String apiKey) {
        String provider = providerForBaseUrl(baseUrl);
        TraceStore.put("llmrouter.route.key", SafeRedactor.traceLabelOrFallback(key, "route"));
        TraceStore.put("llmrouter.route.enabled", true);
        TraceStore.put("llmrouter.api.provider", provider);
        TraceStore.put("llmrouter.api.providerDisabled", false);
        TraceStore.put("llmrouter.api.failureClass", "none");
        traceEndpointDiagnostics("llmrouter.api.", baseUrl);
        TraceStore.put("llmrouter.api.hasKey",
                StringUtils.hasText(apiKey) && !looksLikePlaceholderKey(apiKey));
        TraceStore.put("llmrouter.api.hasOwnerToken", false);
    }

    private static String providerForBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "local";
        }
        String u = baseUrl.toLowerCase(Locale.ROOT);
        if (u.contains("api.groq.com")) {
            return "groq";
        }
        if (u.contains("generativelanguage.googleapis.com") || u.contains("aiplatform.googleapis.com")) {
            return "gemini";
        }
        if (u.contains("api.mistral.ai")) {
            return "mistral";
        }
        if (u.contains("api.anthropic.com")) {
            return "anthropic";
        }
        if (u.contains("api.cerebras.ai")) {
            return "cerebras";
        }
        if (u.contains("openrouter.ai")) {
            return "openrouter";
        }
        if (u.contains("opencode.ai")) {
            return "opencode";
        }
        if (ModelGuardSupport.looksLikeOpenAiBaseUrl(baseUrl)) {
            return "openai";
        }
        return "local";
    }

    private static void traceEndpointDiagnostics(String prefix, String baseUrl) {
        TraceStore.put(prefix + "endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
        TraceStore.put(prefix + "endpointScheme", LocalLlmGatewaySecurity.endpointScheme(baseUrl));
        TraceStore.put(prefix + "endpointFamily", LocalLlmGatewaySecurity.endpointFamily(baseUrl));
    }

    private boolean isLocalGatewayRoute(String baseUrl, String modelName) {
        return "local".equals(providerForBaseUrl(baseUrl))
                && ModelCapabilities.isLocalChatModelId(modelName);
    }

    private static boolean looksLikePlaceholderKey(String raw) {
        if (raw == null) {
            return true;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        return k.isEmpty()
                || "dummy".equals(k)
                || "null".equals(k)
                || "test".equals(k)
                || "changeme".equals(k)
                || "sk-local".equals(k)
                || "ollama".equals(k)
                || k.startsWith("${");
    }

    private String get(String key) {
        if (env == null || key == null) {
            return null;
        }
        try {
            return env.getProperty(key);
        } catch (Exception ignore) {
            traceSuppressed("config.get", ignore);
            return null;
        }
    }

    private boolean bool(String propertyKey, String envKey, boolean defaultValue) {
        String value = firstNonBlank(get(propertyKey), get(envKey), System.getenv(envKey));
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private Integer ollamaNativeNumGpu() {
        String value = firstNonBlank(
                get("llm.ollama-native.num-gpu"),
                get("LLM_OLLAMA_NATIVE_NUM_GPU"),
                System.getenv("LLM_OLLAMA_NATIVE_NUM_GPU"));
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException invalidValue) {
            try {
                TraceStore.put("llm.ollamaNative.numGpuConfig", "invalid");
                TraceStore.put("llm.ollamaNative.numGpuConfigLength", value.length());
                TraceStore.put("llm.ollamaNative.numGpuConfigHash", SafeRedactor.hashValue(value));
            } catch (Exception traceFailure) {
                traceSuppressed("ollamaNative.numGpuConfig", traceFailure);
            }
            return null;
        }
    }

    private String gatewayAllowedHosts() {
        return firstNonBlank(
                get("llm.provider-guard.allowed-hosts"),
                get("LLM_PROVIDER_GUARD_ALLOWED_HOSTS"),
                System.getenv("LLM_PROVIDER_GUARD_ALLOWED_HOSTS"));
    }

    private String ownerToken() {
        return firstNonBlank(get("llm.owner-token"), get("LLM_OWNER_TOKEN"), System.getenv("LLM_OWNER_TOKEN"));
    }

    private String ownerTokenHeader() {
        return firstNonBlank(get("llm.owner-token-header"), get("LLM_OWNER_TOKEN_HEADER"), "X-Owner-Token");
    }

    private String resolveStrictProviderKey(String label, String envKey, String... propertyKeys) {
        String propertyValue = null;
        String propertySource = null;
        if (propertyKeys != null) {
            for (String propertyKey : propertyKeys) {
                String value = trimToNull(get(propertyKey));
                if (value == null) {
                    continue;
                }
                if (propertyValue != null) {
                    throw new IllegalStateException("Conflicting " + label + " API keys: set only ONE of ["
                            + propertySource + ", " + propertyKey + "]");
                }
                propertyValue = value;
                propertySource = propertyKey;
            }
        }
        String envValue = trimToNull(get(envKey));
        if (propertyValue != null && envValue != null) {
            throw new IllegalStateException("Conflicting " + label + " API keys: set only ONE of ["
                    + propertySource + ", " + envKey + "]");
        }
        return firstNonBlank(propertyValue, envValue);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveAlias(String requestedModelId) {
        if (requestedModelId == null || props == null) {
            return null;
        }
        var aliases = props.getAliases();
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }

        String key = requestedModelId.trim();
        if (key.isEmpty()) {
            return null;
        }

        String v = aliases.get(key);
        if (v == null) {
            v = aliases.get(key.toLowerCase(Locale.ROOT));
        }
        if (v == null) {
            return null;
        }
        String out = v.trim();
        return out.isEmpty() ? null : out;
    }

    private static String normalizeBaseUrl(String raw) {
        return com.example.lms.llm.OpenAiCompatBaseUrl.sanitize(raw);
    }

    private static void traceSuppressed(String stage, Exception ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(), "unknown");
        TraceStore.put("llmrouter.suppressed.stage", safeStage);
        TraceStore.put("llmrouter.suppressed.errorType", errorType);
        TraceStore.put("llmrouter.suppressed." + safeStage, true);
        TraceStore.put("llmrouter.suppressed." + safeStage + ".errorType", errorType);
    }

    private static boolean looksLikeMissingOpenAiKey(IllegalStateException ise) {
        if (ise == null) {
            return false;
        }
        String msg = ise.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("openai") && m.contains("api key") && m.contains("missing");
    }

    /** Forward the full request; a legacy list-only delegate keeps its original entry point. */
    private static ChatResponse invokeDelegate(
            ChatModel delegate, List<ChatMessage> messages, ChatRequest request) {
        if (request == null || messages == null || messages.isEmpty()) {
            return delegate.chat(messages);
        }
        try {
            return delegate.chat(request);
        } catch (RuntimeException failure) {
            if (isMissingDoChatContract(failure)) {
                return delegate.chat(messages);
            }
            throw failure;
        }
    }

    private static boolean isMissingDoChatContract(RuntimeException failure) {
        if (failure == null
                || failure.getClass() != RuntimeException.class
                || !"Not implemented".equals(failure.getMessage())) {
            return false;
        }
        // Only the interface-default doChat produces this exact throw before any
        // transport; a provider error raised inside a real doChat must propagate.
        StackTraceElement[] frames = failure.getStackTrace();
        return frames.length > 0
                && "dev.langchain4j.model.chat.ChatModel".equals(frames[0].getClassName())
                && "doChat".equals(frames[0].getMethodName());
    }

    static final class RecordingChatModel implements ChatModel {
        private Consumer<LlmFailureClass> onFailure = ignored -> { };
        private java.util.function.BiConsumer<Boolean, LlmFailureClass> incidentReporter = (s, f) -> { };
        private final ChatModel delegate;
        private final LlmRouterBandit bandit;
        private final String key;
        private final LlmGatewayFailureClassifier failureClassifier;

        RecordingChatModel(
                ChatModel delegate,
                LlmRouterBandit bandit,
                String key,
                LlmGatewayFailureClassifier failureClassifier) {
            this.delegate = delegate;
            this.bandit = bandit;
            this.key = key;
            this.failureClassifier = failureClassifier == null
                    ? new LlmGatewayFailureClassifier()
                    : failureClassifier;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return doChat(request.messages(), request);
        }

        private ChatResponse doChat(List<ChatMessage> messages, ChatRequest request) {
            long started = System.nanoTime();
            try {
                ChatResponse response = invokeDelegate(delegate, messages, request);
                boolean success = responseHasText(response) && !(delegate instanceof ExpectedFailureChatModel);
                record(success, success ? LlmFailureClass.NONE : LlmFailureClass.UNKNOWN, started);
                return response;
            } catch (com.example.lms.llm.gateway.LlmResponseTerminalException terminal) {
                if (terminal.failureClass().hardBreakerFailure()) record(false, terminal.failureClass(), started);
                throw terminal;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                LlmFailureClass failureClass = classifyFailure(ex);
                record(false, failureClass, started);
                throw ex;
            } catch (NullPointerException | UnsupportedOperationException | UncheckedIOException ex) {
                LlmFailureClass failureClass = classifyFailure(ex);
                record(false, failureClass, started);
                throw ex;
            } catch (RuntimeException ex) {
                record(false, classifyFailure(ex), started);
                throw ex;
            }
        }

        private LlmFailureClass classifyFailure(Throwable failure) {
            if (failure instanceof LlmGatewayException gatewayFailure) {
                return gatewayFailure.failureClass();
            }
            return failureClassifier.classify(failure);
        }

        private void record(boolean success, LlmFailureClass failureClass, long startedNanos) {
            long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            LlmFailureClass safeFailureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
            if (!success) onFailure.accept(safeFailureClass);
            try { incidentReporter.accept(success, safeFailureClass); }
            catch (RuntimeException ignored) { traceSuppressed("incident.record", ignored); }
            if (bandit != null) {
                bandit.recordOutcome(key, success, latencyMs, safeFailureClass);
            }
            try {
                TraceStore.put("llmrouter.bandit.rewardRecorded", true);
                TraceStore.put("llmrouter.bandit.reward", success ? "success" : "fail");
                TraceStore.put("llmrouter.bandit.failureClass",
                        safeFailureClass.name().toLowerCase(Locale.ROOT));
                TraceStore.put("llmrouter.bandit.latencyMs", latencyMs);
                TraceStore.put("llmrouter.bandit.routeKeyHash", SafeRedactor.hashValue(key));
                TraceStore.put("llmrouter.bandit.routeKeyLength", key == null ? 0 : key.length());
            } catch (IllegalArgumentException | IllegalStateException ignore) {
                traceSuppressed("bandit.record", ignore);
                // best-effort diagnostics only
            }
        }

        private static boolean responseHasText(ChatResponse response) {
            return StringUtils.hasText(responseText(response));
        }

        private static String responseText(ChatResponse response) {
            return response == null || response.aiMessage() == null
                    ? null
                    : response.aiMessage().text();
        }

    }

    static final class ResponseModelVerifyingChatModel implements ChatModel {
        private final ChatModel delegate;
        private final String expectedModelName;
        private final List<String> approvedAliases;

        ResponseModelVerifyingChatModel(
                ChatModel delegate,
                String expectedModelName,
                List<String> approvedAliases) {
            this.delegate = delegate;
            this.expectedModelName = canonicalResponseModel(expectedModelName);
            this.approvedAliases = approvedAliases == null
                    ? List.of()
                    : approvedAliases.stream()
                    .map(ResponseModelVerifyingChatModel::canonicalResponseModel)
                    .filter(StringUtils::hasText)
                    .toList();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return doChat(request.messages(), request);
        }

        private ChatResponse doChat(List<ChatMessage> messages, ChatRequest request) {
            ChatResponse response = invokeDelegate(delegate, messages, request);
            String actualModelName = canonicalResponseModel(response == null ? null : response.modelName());
            String verdict;
            if (!StringUtils.hasText(expectedModelName) || !StringUtils.hasText(actualModelName)) {
                verdict = "unverified";
            } else if (expectedModelName.equalsIgnoreCase(actualModelName)) {
                verdict = "exact";
            } else if (approvedAliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(actualModelName))) {
                verdict = "approved_alias";
            } else {
                verdict = "mismatch";
            }
            traceResponseModelVerdict(verdict, expectedModelName, actualModelName);
            if ("unverified".equals(verdict)) {
                throw new IllegalStateException("provider=model-router disabledReason=response_model_unverified");
            }
            if ("mismatch".equals(verdict)) {
                throw new IllegalStateException("provider=model-router disabledReason=response_model_mismatch");
            }
            return response;
        }

        private static String canonicalResponseModel(String modelName) {
            String canonical = ModelCapabilities.canonicalModelName(modelName);
            return StringUtils.hasText(canonical) ? canonical.trim() : null;
        }

        private static void traceResponseModelVerdict(
                String verdict,
                String expectedModelName,
                String actualModelName) {
            try {
                TraceStore.put("llmrouter.responseModel.verdict", verdict);
                TraceStore.put("llmrouter.responseModel.expectedHash", SafeRedactor.hashValue(expectedModelName));
                TraceStore.put("llmrouter.responseModel.expectedLength",
                        expectedModelName == null ? 0 : expectedModelName.length());
                TraceStore.put("llmrouter.responseModel.actualHash", SafeRedactor.hashValue(actualModelName));
                TraceStore.put("llmrouter.responseModel.actualLength",
                        actualModelName == null ? 0 : actualModelName.length());
            } catch (IllegalArgumentException | IllegalStateException ignore) {
                traceSuppressed("responseModel.verify", ignore);
            }
        }
    }

    private static final class CallArgs {
        final String requestedModelId;
        final Double temperature;
        final Double topP;
        final Double frequencyPenalty;
        final Double presencePenalty;
        final Integer maxTokens;
        final int timeoutSeconds;
        final long timeoutMs;
        final Integer maxRetriesOverride;
        boolean cueJson;
        dev.langchain4j.model.chat.request.json.JsonSchema cueJsonSchema;
        long cueTimeoutMs;

        private CallArgs(
                String requestedModelId,
                Double temperature,
                Double topP,
                Double frequencyPenalty,
                Double presencePenalty,
                Integer maxTokens,
                int timeoutSeconds,
                Integer maxRetriesOverride) {
            this.requestedModelId = requestedModelId;
            this.temperature = temperature;
            this.topP = topP;
            this.frequencyPenalty = frequencyPenalty;
            this.presencePenalty = presencePenalty;
            this.maxTokens = maxTokens;
            this.timeoutSeconds = timeoutSeconds;
            this.timeoutMs = (long) timeoutSeconds * 1000L;
            this.maxRetriesOverride = maxRetriesOverride;
        }

        static CallArgs parse(Object[] args) {
            if (args == null || args.length == 0) {
                return null;
            }
            if (!(args[0] instanceof String modelId)) {
                return null;
            }

            // overload 1: (String, Double, Double, Integer, int)
            if (args.length == 5) {
                return new CallArgs(
                        modelId,
                        safeDouble(args[1]),
                        safeDouble(args[2]),
                        null,
                        null,
                        safeIntObj(args[3]),
                        safeInt(args[4]),
                        null);
            }

            // overload 2: (String, Double, Double, Double, Double, Integer, int)
            if (args.length == 7) {
                return new CallArgs(
                        modelId,
                        safeDouble(args[1]),
                        safeDouble(args[2]),
                        safeDouble(args[3]),
                        safeDouble(args[4]),
                        safeIntObj(args[5]),
                        safeInt(args[6]),
                        null);
            }

            // overload 3: (String, Double, Double, Double, Double, Integer, int, Integer)
            if (args.length == 8) {
                return new CallArgs(
                        modelId,
                        safeDouble(args[1]),
                        safeDouble(args[2]),
                        safeDouble(args[3]),
                        safeDouble(args[4]),
                        safeIntObj(args[5]),
                        safeInt(args[6]),
                        safeIntObj(args[7]));
            }

            return null;
        }

        private static Double safeDouble(Object o) {
            if (o == null) {
                return null;
            }
            if (o instanceof Double d) {
                return d;
            }
            if (o instanceof Number n) {
                return n.doubleValue();
            }
            return null;
        }

        private static Integer safeIntObj(Object o) {
            if (o == null) {
                return null;
            }
            if (o instanceof Integer i) {
                return i;
            }
            if (o instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        private static int safeInt(Object o) {
            if (o == null) {
                return 0;
            }
            if (o instanceof Integer i) {
                return i;
            }
            if (o instanceof Number n) {
                return n.intValue();
            }
            return 0;
        }
    }
}
