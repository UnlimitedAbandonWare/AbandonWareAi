package com.example.lms.llm;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmGatewayProperties;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

@Slf4j
@Component
public class DynamicChatModelFactory {
    @Autowired(required = false)
    private com.example.lms.llm.gateway.HybridLlmGatewayProbeService localGatewayProbe;
    @Autowired(required = false)
    private ai.abandonware.nova.orch.aop.LlmRouterAspect localFailoverRouter;

    private static final Map<ChatModel, ChatUsageLedger.ConfiguredCap> CONFIGURED_TOKEN_BUDGETS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final LlmGatewayFailureClassifier FAILURE_CLASSIFIER = new LlmGatewayFailureClassifier();

    @Value("${llm.chat-model:${llm.fast.model:gemma4:26b}}")
    private String defaultModelName;

    /**
     * Remote(OpenAI/OpenAI-compatible) endpoint.
     *
     * NOTE:
     * - 설정 문서(application-llm.yaml) 키: llm.base-url-openai
     * - 일부 레거시 경로 키: llm.openai.base-url
     * - 운영 환경 키: OPENAI_BASE_URL
     */
    @Value("${llm.base-url-openai:${llm.openai.base-url:${OPENAI_BASE_URL:https://api.openai.com/v1}}}")
    private String openAiBaseUrl;

    /**
     * Local(OpenAI-compatible) endpoint (Ollama/vLLM/llama.cpp).
     *
     * NOTE:
     * - 설정 키: llm.base-url
     * - 일부 레거시 키: llm.ollama.base-url
     */
    @Value("${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}")
    private String localBaseUrl;

    @Value("${llm.fast.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}")
    private String fastLocalBaseUrl;

    @Value("${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}")
    private String highLocalBaseUrl;

    @Value("${llm.judge.base-url:${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String judgeLocalBaseUrl;

    @Value("${llm.coder.base-url:${llm.high.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String coderLocalBaseUrl;

    @Value("${llm.vision.base-url:${llm.fast.base-url:${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}}}")
    private String visionLocalBaseUrl;

    /**
     * Local API key (optional; some gateways require it). Default is a harmless
     * placeholder.
     */
    @Value("${llm.api-key:${LLM_API_KEY:ollama}}")
    private String localApiKey;

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${llm.owner-token-header:${LLM_OWNER_TOKEN_HEADER:X-Owner-Token}}")
    private String ownerTokenHeader;

    @Value("${llm.provider-guard.allow-private-remote:${LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE:false}}")
    private boolean allowPrivateRemote;

    @Value("${llm.provider-guard.allowed-hosts:${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}}")
    private String allowedHosts;

    @Value("${llm.provider-guard.require-auth-for-remote:${LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE:true}}")
    private boolean requireAuthForRemote;

    @Value("${llm.dynamic.max-retries:${llm.max-retries:0}}")
    private int dynamicMaxRetries;

    @Value("${llm.ollama-native.think-false.enabled:true}")
    private boolean ollamaNativeThinkFalseEnabled;

    @Value("${llm.ollama-native.num-gpu:${LLM_OLLAMA_NATIVE_NUM_GPU:}}")
    private String ollamaNativeNumGpu;

    private final Environment env;
    private final KeyResolver keyResolver;
    private final ModelRuntimeHealthTracker modelRuntimeHealthTracker;
    private final LlmGatewayProperties llmGatewayProperties;

    @Autowired(required = false)
    private ai.abandonware.nova.config.LlmRouterProperties llmRouterProperties;

    @Autowired
    public DynamicChatModelFactory(Environment env,
                                   KeyResolver keyResolver,
                                   ModelRuntimeHealthTracker modelRuntimeHealthTracker,
                                   LlmGatewayProperties llmGatewayProperties) {
        this.env = env;
        this.keyResolver = keyResolver;
        this.modelRuntimeHealthTracker = modelRuntimeHealthTracker == null
                ? new ModelRuntimeHealthTracker()
                : modelRuntimeHealthTracker;
        this.llmGatewayProperties = llmGatewayProperties == null
                ? new LlmGatewayProperties()
                : llmGatewayProperties;
    }

    public DynamicChatModelFactory(Environment env,
                                   KeyResolver keyResolver,
                                   ModelRuntimeHealthTracker modelRuntimeHealthTracker) {
        this(env, keyResolver, modelRuntimeHealthTracker, new LlmGatewayProperties());
    }

    public DynamicChatModelFactory(Environment env, KeyResolver keyResolver) {
        this(env, keyResolver, new ModelRuntimeHealthTracker(), new LlmGatewayProperties());
    }

    /**
     * Backward-compatible overload (no penalties).
     */
    public ChatModel lc(String modelName, Double temperature, Double topP, Integer maxTokens) {
        return lc(modelName, temperature, topP, null, null, maxTokens);
    }

    /**
     * Creates a request-scoped ChatModel with optional sampling controls.
     *
     * <p>
     * LangChain4j OpenAiChatModel is configured at build-time (not per request),
     * so this factory is expected to be called frequently.
     * </p>
     */
    public ChatModel lc(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens) {

        return lcWithTimeout(modelName, temperature, topP, frequencyPenalty, presencePenalty, maxTokens, 120);
    }

    /**
     * Backward-compatible overload (no penalties).
     */
    public ChatModel lcWithTimeout(String modelName, Double temperature, Double topP, Integer maxTokens,
            int timeoutSeconds) {
        return lcWithTimeout(modelName, temperature, topP, null, null, maxTokens, timeoutSeconds);
    }

    /**
     * Router/Workflow 사전 게이트:
     * - OpenAI 계열(gpt-/o*) 요청인데 OpenAI 키가 없으면 false
     */
    public boolean canServe(String modelName) {
        String model = ModelCapabilities.canonicalModelName(modelName);
        if (isLocalModel(model)) {
            return true;
        }
        String key = resolveOpenAiApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public boolean canServeQuietly(String modelName) {
        try {
            return canServe(modelName);
        } catch (RuntimeException ex) {
            log.warn("[AWX2AF2][model-policy] canServe failed modelHash={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(modelName),
                    SafeRedactor.hashValue(String.valueOf(ex)),
                    String.valueOf(ex).length());
            return false;
        }
    }

    public ChatModel lcWithTimeout(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens,
            int timeoutSeconds) {
        return lcWithTimeout(modelName, temperature, topP, frequencyPenalty, presencePenalty,
                maxTokens, timeoutSeconds, null);
    }

    public ChatModel lcWithTimeout(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens,
            int timeoutSeconds,
            Integer maxRetriesOverride) {

        boolean creativeSamplingClaimed = claimCreativeProviderSampling();
        try {
        String rawModel = trimToNull(modelName);
        if (rawModel == null) {
            rawModel = trimToNull(defaultModelName);
        }
        // Extra fallback: a blank llm.fast.model can slip in via env overrides. In that case,
        // fall back to the primary chat model to avoid sending requests with an empty model.
        if (rawModel == null) {
            rawModel = trimToNull(env.getProperty("llm.chat-model"));
        }

        boolean exactSelection = RequestedModelSelection.matches(rawModel);
        if (exactSelection && rawModel.startsWith("llmrouter."))
            throw new ModelSelectionException("provider_not_configured");
        String effectiveModel = exactSelection ? rawModel : ModelCapabilities.canonicalModelName(rawModel);
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new IllegalStateException(
                    "model is required (blank modelName after canonicalize). rawModel='"
                            + (rawModel == null ? "<null>" : rawModel) + "'");
        }

        // Route selection + credential selection (fail-soft):
        // - local 모델이면 localBaseUrl + central provider credential resolution
        // - OpenAI 모델이면 openAiBaseUrl + OpenAI key(없으면 IllegalStateException)
        boolean local = isLocalModel(effectiveModel);
        String baseUrl = OpenAiCompatBaseUrl.sanitize(local
                ? (exactSelection ? localBaseUrl : selectLocalBaseUrl(effectiveModel)) : openAiBaseUrl);
        LlmGatewayException routeFailure = LocalLlmGatewaySecurity.routePolicyFailure(
                llmRouterProperties, rawModel, effectiveModel, baseUrl);
        if (routeFailure != null) {
            throw routeFailure;
        }
        String apiKeyForCall = local ? resolveLocalApiKey() : resolveOpenAiApiKey();

        // Best-effort trace breadcrumbs (no secrets).
        try {
            com.example.lms.search.TraceStore.put("llm.factory.model.rawHash", SafeRedactor.hashValue(rawModel));
            com.example.lms.search.TraceStore.put("llm.factory.model.rawLength", rawModel == null ? 0 : rawModel.length());
            com.example.lms.search.TraceStore.put("llm.factory.model.effectiveHash", SafeRedactor.hashValue(effectiveModel));
            com.example.lms.search.TraceStore.put("llm.factory.model.effectiveLength", effectiveModel == null ? 0 : effectiveModel.length());
            com.example.lms.search.TraceStore.put("llm.factory.local", local);
            com.example.lms.search.TraceStore.put("llm.factory.baseUrlHost",
                    LocalLlmGatewaySecurity.endpointHost(baseUrl));
            com.example.lms.search.TraceStore.put("llm.factory.baseUrlHash", SafeRedactor.hashValue(baseUrl));
            com.example.lms.search.TraceStore.put("llm.factory.hasOwnerToken",
                    local && LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken));
        } catch (Throwable ignore) {
            log.debug("DynamicChatModelFactory: trace breadcrumbs skipped errorType={}",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
        }
        if (local) {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    baseUrl,
                    allowPrivateRemote,
                    allowedHosts,
                    requireAuthForRemote,
                    apiKeyForCall,
                    ownerToken);
        }
        if (!local) {
            assertOpenAiReady(effectiveModel, baseUrl, apiKeyForCall);
        }

        Double safeTemp = null;
        if (temperature != null) {
            double sanitized = ModelCapabilities.sanitizeTemperature(effectiveModel, temperature);
            safeTemp = sanitized;
            if (!Objects.equals(temperature, safeTemp)) {
                log.debug("Adjusted temperature {} -> {} for modelHash={} modelLength={}", temperature, safeTemp, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safeTopP = null;
        if (topP != null) {
            double sanitized = ModelCapabilities.sanitizeTopP(effectiveModel, topP);
            safeTopP = sanitized;
            if (!Objects.equals(topP, safeTopP)) {
                log.debug("Adjusted top_p {} -> {} for modelHash={} modelLength={}", topP, safeTopP, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safeFreqPenalty = null;
        if (frequencyPenalty != null) {
            double sanitized = ModelCapabilities.sanitizeFrequencyPenalty(effectiveModel, frequencyPenalty);
            safeFreqPenalty = sanitized;
            if (!Objects.equals(frequencyPenalty, safeFreqPenalty)) {
                log.debug("Adjusted frequency_penalty {} -> {} for modelHash={} modelLength={}", frequencyPenalty, safeFreqPenalty, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        Double safePresencePenalty = null;
        if (presencePenalty != null) {
            double sanitized = ModelCapabilities.sanitizePresencePenalty(effectiveModel, presencePenalty);
            safePresencePenalty = sanitized;
            if (!Objects.equals(presencePenalty, safePresencePenalty)) {
                log.debug("Adjusted presence_penalty {} -> {} for modelHash={} modelLength={}", presencePenalty, safePresencePenalty, SafeRedactor.hashValue(effectiveModel), effectiveModel == null ? 0 : effectiveModel.length());
            }
        }

        try {
            String safeApiKey = local
                    ? localApiKeyForCall(apiKeyForCall)
                    : apiKeyForCall.trim();
            Integer wireMaxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : null;
            boolean sharedLocalFailover = local && localFailoverRouter != null && localGatewayProbe != null
                    && localGatewayProbe.localFailoverEnabled() && localGatewayProbe.cloudFallbackEnabled();
            Duration primaryTimeout = localPrimaryTimeout(timeoutSeconds, sharedLocalFailover);
            if (local && shouldUseOllamaNativeThinkFalse(effectiveModel, baseUrl)) {
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route", true);
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route.modelHash", SafeRedactor.hashValue(effectiveModel));
                com.example.lms.search.TraceStore.put("llm.ollamaNative.route.modelLength", effectiveModel.length());
                ChatModel selectedModel = new OllamaNativeChatModel(
                        baseUrl,
                        effectiveModel,
                        primaryTimeout,
                        wireMaxTokens,
                        safeTemp,
                        safeTopP,
                        ollamaNativeNumGpu(),
                        modelRuntimeHealthTracker,
                        !sharedLocalFailover && (maxRetriesOverride == null || maxRetriesOverride > 0),
                        localGatewayProbe == null ? llmGatewayProperties.getLocalDeviceFailover().toEndpointQuarantinePolicy()
                                : ModelRuntimeHealthTracker.EndpointQuarantinePolicy.disabled());
                recordSelectedRequestEndpoint(effectiveModel, baseUrl);
                if (sharedLocalFailover) selectedModel = localFailoverRouter.routeLocalInference(selectedModel,
                        baseUrl, effectiveModel, timeoutSeconds * 1000, safeTemp, safeTopP,
                        safeFreqPenalty, safePresencePenalty, wireMaxTokens, "ollama_native");
                else {
                if (localGatewayProbe != null) selectedModel = localGatewayProbe.guardLocalModel(selectedModel, baseUrl, effectiveModel);
                selectedModel = decorateRequestAttempt(
                        selectedModel,
                        "ollama",
                        effectiveModel,
                        baseUrl,
                        "ollama_native",
                        safeTemp,
                        safeTopP,
                        safeFreqPenalty,
                        safePresencePenalty,
                        wireMaxTokens,
                        timeoutSeconds,
                        maxRetriesOverride);
                }
                recordCreativeEffectiveSampling(creativeSamplingClaimed, safeTemp, safeTopP);
                return rememberConfiguredTokenBudget(
                        selectedModel,
                        maxTokens,
                        ChatUsageLedger.ParameterKind.NUM_PREDICT);
            }
            String tokenParam = OpenAiTokenParamCompat.tokenParamKey(effectiveModel, baseUrl);
            if (tokenParam == null) {
                wireMaxTokens = null;
            }
            var builder = OpenAiChatModel.builder()
                    .httpClientBuilder(modelRuntimeHealthTracker.observedHttpClientBuilder("primary"))
                    .baseUrl(baseUrl)
                    .apiKey(safeApiKey)
                    .modelName(effectiveModel)
                    .timeout(primaryTimeout);

            // Keep SDK image/tool serialization while honoring Ollama's bounded-answer policy.
            if (local && ollamaNativeThinkFalseEnabled && "gemma4:26b".equals(effectiveModel)
                    && LocalLlmGatewaySecurity.isLoopbackBaseUrl(baseUrl)) {
                builder.defaultRequestParameters(dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                        .reasoningEffort("none")
                        .build());
            }

            if (local && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts)) {
                Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken);
                if (!headers.isEmpty()) {
                    builder.customHeaders(headers);
                }
            }

            // Prevent nested retries/timeouts; LangChain4j 1.0.1 exposes maxRetries(Integer).
            int effectiveMaxRetries = maxRetriesOverride == null
                    ? dynamicMaxRetries
                    : maxRetriesOverride;
            builder.maxRetries(Integer.valueOf(sharedLocalFailover ? 0 : Math.max(0, effectiveMaxRetries)));

            if (safeTemp != null) {
                builder.temperature(safeTemp);
            }
            if (safeTopP != null) {
                builder.topP(safeTopP);
            }

            if (safeFreqPenalty != null) {
                builder.frequencyPenalty(safeFreqPenalty);
            }
            if (safePresencePenalty != null) {
                builder.presencePenalty(safePresencePenalty);
            }

            if (wireMaxTokens != null) {
                if ("max_tokens".equals(tokenParam)) {
                    builder.maxTokens(wireMaxTokens);
                } else {
                    builder.maxCompletionTokens(wireMaxTokens);
                }
            }

            // Safety: ensure modelName is not dropped by later builder mutations (e.g., maxTokens/maxCompletionTokens)
            builder.modelName(effectiveModel);

            ChatModel selectedModel = builder.build();
            recordSelectedRequestEndpoint(effectiveModel, baseUrl);
            if (sharedLocalFailover) selectedModel = localFailoverRouter.routeLocalInference(selectedModel,
                    baseUrl, effectiveModel, timeoutSeconds * 1000, safeTemp, safeTopP,
                    safeFreqPenalty, safePresencePenalty, wireMaxTokens, "openai_chat_completions");
            else selectedModel = decorateRequestAttempt(
                    selectedModel,
                    local ? "local_openai_compatible" : "openai",
                    effectiveModel,
                    baseUrl,
                    "openai_chat_completions",
                    safeTemp,
                    safeTopP,
                    safeFreqPenalty,
                    safePresencePenalty,
                    wireMaxTokens,
                    timeoutSeconds,
                    maxRetriesOverride);
            if (local && !sharedLocalFailover) {
                selectedModel = guardLocalOpenAiCompatibleEndpoint(selectedModel, baseUrl, effectiveModel);
            }
            recordCreativeEffectiveSampling(creativeSamplingClaimed, safeTemp, safeTopP);
            ChatUsageLedger.ParameterKind parameterKind = tokenParam == null
                    ? ChatUsageLedger.ParameterKind.OMITTED
                    : "max_tokens".equals(tokenParam)
                            ? ChatUsageLedger.ParameterKind.MAX_TOKENS
                            : ChatUsageLedger.ParameterKind.MAX_COMPLETION_TOKENS;
            return rememberConfiguredTokenBudget(selectedModel, maxTokens, parameterKind);
        } catch (Exception e) {
            throw wrapConnect(e, baseUrl);
        }
        } catch (RuntimeException | Error failure) {
            recordCreativeSamplingFailure(creativeSamplingClaimed);
            throw failure;
        }
    }

    public static ChatUsageLedger.ConfiguredCap configuredTokenBudget(ChatModel model) {
        if (model == null) {
            return ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null);
        }
        ChatUsageLedger.ConfiguredCap configured = CONFIGURED_TOKEN_BUDGETS.get(model);
        return configured == null
                ? ChatUsageLedger.ConfiguredCap.providerDefaultUnknown(null, null)
                : configured;
    }

    private static void recordCreativeEffectiveSampling(Double temperature, Double topP) {
        recordCreativeEffectiveSampling(claimCreativeProviderSampling(), temperature, topP);
    }

    private static boolean claimCreativeProviderSampling() {
        com.example.lms.service.guard.GuardContext context =
                com.example.lms.service.guard.GuardContextHolder.get();
        if (context == null
                || !context.planBool("creative.emergence.active", false)
                || !context.planBool("creative.emergence.final.providerSamplingPending", false)) {
            return false;
        }
        context.getPlanOverrides().remove("creative.emergence.final.providerSamplingPending");
        context.getPlanOverrides().remove("creative.emergence.effectiveOptionsHash");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTemperature");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTopP");
        com.example.lms.search.TraceStore.put("creative.emergence.effectiveOptionsHash", null);
        return true;
    }

    private static void recordCreativeSamplingFailure(boolean claimed) {
        if (!claimed) {
            return;
        }
        com.example.lms.service.guard.GuardContext context =
                com.example.lms.service.guard.GuardContextHolder.get();
        if (context == null) {
            return;
        }
        context.getPlanOverrides().remove("creative.emergence.final.providerSamplingPending");
        context.getPlanOverrides().remove("creative.emergence.effectiveOptionsHash");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTemperature");
        context.getPlanOverrides().remove("creative.emergence.provider.effectiveTopP");
        context.putPlanOverride("creative.emergence.suppressedReason", "sampling-option-unproven");
        com.example.lms.search.TraceStore.put("creative.emergence.effectiveOptionsHash", null);
        com.example.lms.search.TraceStore.put(
                "creative.emergence.suppressedReason", "sampling-option-unproven");
    }

    private static void recordCreativeEffectiveSampling(
            boolean claimed,
            Double temperature,
            Double topP) {
        if (!claimed) {
            return;
        }
        com.example.lms.service.guard.GuardContext context =
                com.example.lms.service.guard.GuardContextHolder.get();
        if (context == null) {
            return;
        }
        if (!validCompleteCreativeProfile(context)) {
            context.putPlanOverride("creative.emergence.suppressedReason", "incomplete-profile");
            com.example.lms.search.TraceStore.put(
                    "creative.emergence.suppressedReason", "incomplete-profile");
            return;
        }
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        if (!profile.matches("VIVID|WILD|FERAL")
                || temperature == null || !Double.isFinite(temperature)
                || topP == null || !Double.isFinite(topP)) {
            context.putPlanOverride("creative.emergence.suppressedReason", "sampling-option-unproven");
            com.example.lms.search.TraceStore.put(
                    "creative.emergence.suppressedReason", "sampling-option-unproven");
            return;
        }
        String effectiveHash = SafeRedactor.hashValue(String.format(
                Locale.ROOT, "%s|%.2f|%.2f", profile, temperature, topP));
        context.putPlanOverride("creative.emergence.provider.effectiveTemperature", temperature);
        context.putPlanOverride("creative.emergence.provider.effectiveTopP", topP);
        context.putPlanOverride("creative.emergence.effectiveOptionsHash", effectiveHash);
        com.example.lms.search.TraceStore.put("creative.emergence.effectiveOptionsHash", effectiveHash);
    }

    private static boolean validCompleteCreativeProfile(
            com.example.lms.service.guard.GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash"))
                .toLowerCase(Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (String.valueOf(context.getPlanOverride("creative.emergence.profile"))) {
            case "VIVID" -> creativeProfileValuesInRange(context,
                    0.85d, 0.90d, 0.70d, 0.76d,
                    1.10d, 1.25d, 0.95d, 0.97d,
                    1.05d, 1.20d, 0.95d, 0.97d,
                    0.80d, 0.88d);
            case "WILD" -> creativeProfileValuesInRange(context,
                    0.91d, 0.97d, 0.77d, 0.83d,
                    1.26d, 1.45d, 0.97d, 0.99d,
                    1.21d, 1.40d, 0.97d, 0.99d,
                    0.89d, 0.97d);
            case "FERAL" -> creativeProfileValuesInRange(context,
                    0.98d, 1.00d, 0.84d, 0.85d,
                    1.46d, 1.50d, 0.99d, 1.00d,
                    1.41d, 1.50d, 0.99d, 1.00d,
                    0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean creativeProfileValuesInRange(
            com.example.lms.service.guard.GuardContext context,
            double searchTempMin, double searchTempMax,
            double searchRateMin, double searchRateMax,
            double candidateTempMin, double candidateTempMax,
            double candidateTopPMin, double candidateTopPMax,
            double finalTempMin, double finalTempMax,
            double finalTopPMin, double finalTopPMax,
            double selfAskMin, double selfAskMax) {
        return creativeValueInRange(context, "creative.emergence.search.temperature", searchTempMin, searchTempMax)
                && creativeValueInRange(context, "creative.emergence.search.rate", searchRateMin, searchRateMax)
                && creativeValueInRange(context, "creative.emergence.candidate.temperature",
                        candidateTempMin, candidateTempMax)
                && creativeValueInRange(context, "creative.emergence.candidate.topP",
                        candidateTopPMin, candidateTopPMax)
                && creativeValueInRange(context, "creative.emergence.final.temperature",
                        finalTempMin, finalTempMax)
                && creativeValueInRange(context, "creative.emergence.final.topP",
                        finalTopPMin, finalTopPMax)
                && creativeValueInRange(context, "creative.emergence.selfAsk.temperature",
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeValueInRange(
            com.example.lms.service.guard.GuardContext context,
            String key,
            double min,
            double max) {
        double value = context.planDouble(key, Double.NaN);
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static ChatModel rememberConfiguredTokenBudget(
            ChatModel model,
            Integer maxTokens,
            ChatUsageLedger.ParameterKind parameterKind) {
        ChatUsageLedger.ConfiguredCap configured;
        if (maxTokens == null
                || (maxTokens > 0 && parameterKind == ChatUsageLedger.ParameterKind.OMITTED)) {
            configured = ChatUsageLedger.ConfiguredCap.omitted(
                    null,
                    maxTokens,
                    ChatUsageLedger.CapSource.NORMALIZED_REQUEST);
        } else if (maxTokens > 0) {
            configured = ChatUsageLedger.ConfiguredCap.explicit(
                    null,
                    maxTokens,
                    maxTokens,
                    parameterKind,
                    ChatUsageLedger.CapSource.NORMALIZED_REQUEST);
        } else {
            configured = new ChatUsageLedger.ConfiguredCap(
                    null,
                    null,
                    null,
                    ChatUsageLedger.CapState.PROVIDER_DEFAULT_UNKNOWN,
                    ChatUsageLedger.ParameterKind.OMITTED,
                    ChatUsageLedger.CapSource.NORMALIZED_REQUEST);
        }
        CONFIGURED_TOKEN_BUDGETS.put(model, configured);
        return model;
    }

    private void recordSelectedRequestEndpoint(String modelId, String baseUrl) {
        try {
            Object captureEnabled = com.example.lms.search.TraceStore.get(
                    ModelRuntimeHealthTracker.REQUEST_ENDPOINT_CAPTURE_TRACE_KEY);
            if (!Boolean.TRUE.equals(captureEnabled)) {
                return;
            }
            Object rawTimelineId = com.example.lms.search.TraceStore.get(
                    ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
            String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
            if (timelineId.isBlank()) {
                return;
            }
            modelRuntimeHealthTracker.recordRequestPhase(
                    timelineId,
                    "pending",
                    modelId,
                    baseUrl,
                    "none");
        } catch (RuntimeException ex) {
            log.debug("DynamicChatModelFactory: request endpoint timeline skipped errorType={}",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
        }
    }

    private ChatModel decorateRequestAttempt(
            ChatModel model,
            String provider,
            String modelId,
            String baseUrl,
            String protocol,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens,
            int timeoutSeconds,
            Integer maxRetriesOverride) {
        if (modelRuntimeHealthTracker == null) {
            return model;
        }
        Map<String, Object> ownedOptions = new LinkedHashMap<>();
        ownedOptions.put("temperature", temperature);
        ownedOptions.put("topP", topP);
        ownedOptions.put("frequencyPenalty", frequencyPenalty);
        ownedOptions.put("presencePenalty", presencePenalty);
        if ("ollama_native".equals(protocol)
                || !OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(modelId, baseUrl)) {
            ownedOptions.put("maxOutputTokens", maxTokens);
        } else {
            ownedOptions.put("maxTokens", maxTokens);
        }
        ownedOptions.put("timeoutMs", Math.max(1, timeoutSeconds) * 1_000L);
        ownedOptions.put("maxRetries", maxRetriesOverride == null ? dynamicMaxRetries : maxRetriesOverride);
        return modelRuntimeHealthTracker.decorateRequestAttempt(
                model,
                "primary",
                modelRuntimeHealthTracker.redactedRequestAttemptRoute(
                        "dynamic_factory", modelId, baseUrl, protocol),
                ModelRuntimeHealthTracker.requestAttemptOptionEnvelope(
                        provider, modelId, protocol, ownedOptions));
    }

    private static Duration localPrimaryTimeout(int timeoutSeconds, boolean sharedFailover) {
        long configured = Math.max(1L, timeoutSeconds) * 1000L;
        if (!sharedFailover) return Duration.ofMillis(configured);
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        long remaining = budget == null ? configured : Math.min(configured, budget.remainingMillis());
        return Duration.ofMillis(Math.max(1L, remaining / 3));
    }

    private ChatModel guardLocalOpenAiCompatibleEndpoint(ChatModel model, String baseUrl, String modelName) {
        if (localGatewayProbe != null) return localGatewayProbe.guardLocalModel(model, baseUrl, modelName);
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                llmGatewayProperties.getLocalDeviceFailover().toEndpointQuarantinePolicy();
        if (model == null || !policy.enabled()) {
            return model;
        }
        return new ChatModel() {
            @Override
            public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return doGuarded(request.messages(), request);
            }

            private ChatResponse doGuarded(
                    List<ChatMessage> messages,
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                long now = System.currentTimeMillis();
                ModelRuntimeHealthTracker.EndpointAccess access = modelRuntimeHealthTracker.acquireEndpointAccess(
                        "local", baseUrl, policy, now);
                traceLocalEndpointAccess(access);
                if (!access.allowed()) {
                    throw new LlmGatewayException(
                            "Local endpoint quarantined endpointHash=" + access.endpointHash(),
                            LlmFailureClass.GPU_DEVICE_LOST,
                            "gpu_device_lost");
                }
                try {
                    ChatResponse response;
                    if (request == null || messages == null || messages.isEmpty()) {
                        response = model.chat(messages);
                    } else {
                        try {
                            response = model.chat(request);
                        } catch (RuntimeException failure) {
                            if (isMissingDoChatContract(failure)) {
                                response = model.chat(messages);
                            } else {
                                throw failure;
                            }
                        }
                    }
                    boolean successfulGpuPrimary = response != null
                            && response.aiMessage() != null
                            && response.aiMessage().text() != null
                            && !response.aiMessage().text().isBlank();
                    modelRuntimeHealthTracker.completeEndpointAccess(
                            access, successfulGpuPrimary && !access.halfOpenPermit(), policy, System.currentTimeMillis());
                    if (successfulGpuPrimary) modelRuntimeHealthTracker.recordEndpointModelSuccess("local", baseUrl, modelName);
                    return response;
                } catch (RuntimeException failure) {
                    if (LlmGatewayFailureClassifier.isCancellation(failure)) {
                        modelRuntimeHealthTracker.releaseEndpointAccess(access);
                        throw failure;
                    }
                    LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(failure);
                    if (failureClass == LlmFailureClass.GPU_DEVICE_LOST) {
                        modelRuntimeHealthTracker.recordEndpointDeviceLoss(
                                "local", baseUrl, policy, System.currentTimeMillis());
                    } else if (isRunnerTermination(failure)) {
                        modelRuntimeHealthTracker.recordEndpointRunnerTermination(
                                "local", baseUrl, false, policy, System.currentTimeMillis());
                    } else {
                        modelRuntimeHealthTracker.recordEndpointTransientFailure(
                                "local", baseUrl, modelName, failureClass, policy, System.currentTimeMillis());
                    }
                    modelRuntimeHealthTracker.completeEndpointAccess(
                            access, false, policy, System.currentTimeMillis());
                    throw failure;
                }
            }
        };
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

    private static void traceLocalEndpointAccess(ModelRuntimeHealthTracker.EndpointAccess access) {
        if (access == null) {
            return;
        }
        com.example.lms.search.TraceStore.put("llm.localEndpoint.endpointHash", access.endpointHash());
        com.example.lms.search.TraceStore.put(
                "llm.localEndpoint.state", access.state().name().toLowerCase(Locale.ROOT));
        com.example.lms.search.TraceStore.put("llm.localEndpoint.wouldBlock", access.wouldBlock());
        com.example.lms.search.TraceStore.put("llm.localEndpoint.enforced", !access.allowed());
        com.example.lms.search.TraceStore.put("llm.localEndpoint.halfOpenPermit", access.halfOpenPermit());
        com.example.lms.search.TraceStore.put("llm.localEndpoint.retryAfterMs", access.retryAfterMs());
    }

    private static boolean isRunnerTermination(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 20) {
            String message = current.getMessage();
            String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (normalized.contains("runner process has terminated")
                    || normalized.contains("llama-server process has terminated")) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private String localApiKeyForCall(String apiKeyForCall) {
        return ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKeyForCall)
                ? "ollama"
                : apiKeyForCall.trim();
    }

    private String resolveOpenAiApiKey() {
        return keyResolver.resolveOpenAiApiKeyStrict();
    }

    private String resolveLocalApiKey() {
        ProviderCredentialResolver.Resolution resolution = keyResolver.resolveLocalLlmCredential();
        if (!resolution.enabled() && resolution.credentialPresent()) {
            throw new IllegalStateException(
                    "provider=local_llm disabledReason=" + resolution.disabledReason());
        }
        return resolution.valueOrNull() == null || resolution.valueOrNull().isBlank()
                ? "ollama"
                : resolution.valueOrNull();
    }

    private void assertOpenAiReady(String model, String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI API key is missing. Requested model='" + model + "', baseUrl='" + baseUrl
                            + "'. Configure 'llm.api-key-openai' (or OPENAI_API_KEY).");
        }

        // Only treat gsk_ as invalid when the target is the real OpenAI endpoint.
        boolean baseLooksOpenAi = baseUrl != null
                && baseUrl.toLowerCase(Locale.ROOT).contains("api.openai.com");

        if (baseLooksOpenAi && apiKey.startsWith("gsk_")) {
            throw new IllegalStateException(
                    "Groq key(gsk_) mapped to OpenAI base-url. Set OPENAI_API_KEY(sk-/sk-proj-) instead.");
        }
    }

    private boolean isLocalModel(String model) {
        if (model == null || model.isBlank())
            return true;

        String m = model.toLowerCase(Locale.ROOT);

        // ":"(예: gemma3:27b), ollama/local 계열은 local로 취급
        if (m.contains(":"))
            return true;
        if (m.contains("ollama") || m.contains("llama") || m.contains("qwen") || m.contains("gemma")
                || m.contains("phi")) {
            return true;
        }

        // gpt-/o* 는 OpenAI로 취급
        if (m.startsWith("gpt-"))
            return false;
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4"))
            return false;

        // 기본값: local (fail-soft)
        return true;
    }

    private String selectLocalBaseUrl(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (m.contains("qwen3-coder")) {
            return firstNonBlank(coderLocalBaseUrl, highLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3:30b")) {
            return firstNonBlank(judgeLocalBaseUrl, highLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3-vl")) {
            return firstNonBlank(visionLocalBaseUrl, fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("qwen3:8b") || m.contains("qwen3.5:9b") || m.contains("qwen2.5")) {
            return firstNonBlank(fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("gemma3:4b") || m.contains("gemma3_4b")) {
            return firstNonBlank(fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("gemma4:12b")) {
            return firstNonBlank(fastLocalBaseUrl, localBaseUrl);
        }
        if (m.contains("gemma4") || m.contains("gemma3")) {
            return firstNonBlank(highLocalBaseUrl, localBaseUrl);
        }
        return firstNonBlank(localBaseUrl, highLocalBaseUrl, fastLocalBaseUrl);
    }

    private boolean shouldUseOllamaNativeThinkFalse(String model, String baseUrl) {
        return OllamaNativeChatModel.supportsThinkFalseRoute(
                ollamaNativeThinkFalseEnabled,
                model,
                baseUrl);
    }

    private Integer ollamaNativeNumGpu() {
        String value = trimToNull(ollamaNativeNumGpu);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            log.warn("[AWX][local-llm] invalid llm.ollama-native.num-gpu valueHash={} valueLength={}",
                    SafeRedactor.hashValue(value), value.length());
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String trimToNull(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private RuntimeException wrapConnect(Exception e, String baseUrl) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException("Failed to build chat model baseUrlHost="
                + LocalLlmGatewaySecurity.endpointHost(baseUrl)
                + " baseUrlHash=" + SafeRedactor.hashValue(baseUrl)
                + " error=" + SafeRedactor.traceLabelOrFallback(e.getMessage(), ""), e);
    }
}
