package com.example.lms.llm;

import com.example.lms.guard.KeyResolver;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicChatModelFactory {

    @Value("${llm.fast.model:${llm.chat-model:}}")
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

    /**
     * Local API key (optional; some gateways require it). Default is a harmless
     * placeholder.
     */
    @Value("${llm.api-key:${LLM_API_KEY:sk-local}}")
    private String localApiKey;

    @Value("${llm.dynamic.max-retries:${llm.max-retries:0}}")
    private int dynamicMaxRetries;

    private static final AtomicBoolean MAX_RETRIES_METHOD_LOOKUP_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean MAX_RETRIES_WARNED = new AtomicBoolean(false);
    private static volatile Method MAX_RETRIES_METHOD;

    private final Environment env;
    private final KeyResolver keyResolver;

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

    public ChatModel lcWithTimeout(String modelName,
            Double temperature,
            Double topP,
            Double frequencyPenalty,
            Double presencePenalty,
            Integer maxTokens,
            int timeoutSeconds) {

        String rawModel = trimToNull(modelName);
        if (rawModel == null) {
            rawModel = trimToNull(defaultModelName);
        }
        // Extra fallback: a blank llm.fast.model can slip in via env overrides. In that case,
        // fall back to the primary chat model to avoid sending requests with an empty model.
        if (rawModel == null) {
            rawModel = trimToNull(env.getProperty("llm.chat-model"));
        }

        String effectiveModel = ModelCapabilities.canonicalModelName(rawModel);
        if (effectiveModel == null || effectiveModel.isBlank()) {
            throw new IllegalStateException(
                    "model is required (blank modelName after canonicalize). rawModel='"
                            + (rawModel == null ? "<null>" : rawModel) + "'");
        }

        // Route selection + credential selection (fail-soft):
        // - local 모델이면 localBaseUrl/localApiKey
        // - OpenAI 모델이면 openAiBaseUrl + OpenAI key(없으면 IllegalStateException)
        boolean local = isLocalModel(effectiveModel);
        String baseUrl = OpenAiCompatBaseUrl.sanitize(local ? localBaseUrl : openAiBaseUrl);
        String apiKeyForCall = local ? localApiKey : resolveOpenAiApiKey();

        // Best-effort trace breadcrumbs (no secrets).
        try {
            com.example.lms.search.TraceStore.put("llm.factory.model.raw", rawModel);
            com.example.lms.search.TraceStore.put("llm.factory.model.effective", effectiveModel);
            com.example.lms.search.TraceStore.put("llm.factory.local", local);
            com.example.lms.search.TraceStore.put("llm.factory.baseUrl", baseUrl);
        } catch (Throwable ignore) {
            // tracing is best-effort
        }
        if (!local) {
            assertOpenAiReady(effectiveModel, baseUrl, apiKeyForCall);
        }

        Double safeTemp = null;
        if (temperature != null) {
            double sanitized = ModelCapabilities.sanitizeTemperature(effectiveModel, temperature);
            safeTemp = sanitized;
            if (!Objects.equals(temperature, safeTemp)) {
                log.debug("Adjusted temperature {} -> {} for model={}", temperature, safeTemp, effectiveModel);
            }
        }

        Double safeTopP = null;
        if (topP != null) {
            double sanitized = ModelCapabilities.sanitizeTopP(effectiveModel, topP);
            safeTopP = sanitized;
            if (!Objects.equals(topP, safeTopP)) {
                log.debug("Adjusted top_p {} -> {} for model={}", topP, safeTopP, effectiveModel);
            }
        }

        Double safeFreqPenalty = null;
        if (frequencyPenalty != null) {
            double sanitized = ModelCapabilities.sanitizeFrequencyPenalty(effectiveModel, frequencyPenalty);
            safeFreqPenalty = sanitized;
            if (!Objects.equals(frequencyPenalty, safeFreqPenalty)) {
                log.debug("Adjusted frequency_penalty {} -> {} for model={}", frequencyPenalty, safeFreqPenalty,
                        effectiveModel);
            }
        }

        Double safePresencePenalty = null;
        if (presencePenalty != null) {
            double sanitized = ModelCapabilities.sanitizePresencePenalty(effectiveModel, presencePenalty);
            safePresencePenalty = sanitized;
            if (!Objects.equals(presencePenalty, safePresencePenalty)) {
                log.debug("Adjusted presence_penalty {} -> {} for model={}", presencePenalty, safePresencePenalty,
                        effectiveModel);
            }
        }

        try {
            String safeApiKey = (apiKeyForCall == null || apiKeyForCall.isBlank()) ? "sk-local" : apiKeyForCall;
            var builder = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(safeApiKey)
                    .modelName(effectiveModel)
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            // Prevent nested retries/timeouts: best-effort apply builder.maxRetries(...)
            // (signature varies across LangChain4j versions).
            applyMaxRetries(builder, dynamicMaxRetries);

            if (safeTemp != null) {
                builder.temperature(safeTemp);
            }
            if (safeTopP != null) {
                builder.topP(safeTopP);
            }

            // Some OpenAI-compatible servers reject penalties; some LangChain4j versions
            // rename these.
            // Best-effort apply via reflection (safe no-op when unsupported).
            invokeFluent(builder, "frequencyPenalty", safeFreqPenalty);
            invokeFluent(builder, "presencePenalty", safePresencePenalty);

            if (maxTokens != null) {
                if (OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(effectiveModel, baseUrl)) {
                    builder.maxTokens(maxTokens);
                } else {
                    builder.maxCompletionTokens(maxTokens);
                }
            }

            // Safety: ensure modelName is not dropped by later builder mutations (e.g., maxTokens/maxCompletionTokens)
            builder.modelName(effectiveModel);

            return builder.build();
        } catch (Exception e) {
            throw wrapConnect(e, baseUrl);
        }
    }

    private String resolveOpenAiApiKey() {
        // UAW strict policy: if multiple sources are set (even if equal), fail-fast.
        // See KeyResolver.resolveOpenAiApiKeyStrict().
        return keyResolver.resolveOpenAiApiKeyStrict();
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
        return new RuntimeException("Failed to build chat model for baseUrl=" + baseUrl + ": " + e.getMessage(), e);
    }



    /**
     * Best-effort apply builder.maxRetries(...) without hard-wiring to a specific LangChain4j version.
     *
     * <p>Some versions expose maxRetries(Integer), others use maxRetries(int). We apply it via reflection
     * and treat absence/failure as non-fatal.</p>
     */
    private void applyMaxRetries(Object builder, int maxRetries) {
        if (builder == null) {
            return;
        }
        int mr = Math.max(0, maxRetries);
        try {
            // Resolve method once (fail-soft if missing)
            Method m;
            if (!MAX_RETRIES_METHOD_LOOKUP_DONE.get()) {
                MAX_RETRIES_METHOD = findMaxRetriesMethod(builder.getClass());
                MAX_RETRIES_METHOD_LOOKUP_DONE.set(true);
            }
            m = MAX_RETRIES_METHOD;
            if (m == null) {
                return;
            }

            Class<?> p = m.getParameterTypes()[0];
            Object arg = (p == Integer.class) ? Integer.valueOf(mr) : mr;
            m.invoke(builder, arg);
        } catch (Exception ex) {
            // Non-fatal: we still want to build the model.
            if (MAX_RETRIES_WARNED.compareAndSet(false, true)) {
                log.warn("Failed to apply maxRetries={} to builder: {}", mr, ex.toString());
            }
        }
    }

    private static Method findMaxRetriesMethod(Class<?> builderClass) {
        if (builderClass == null) {
            return null;
        }
        try {
            return builderClass.getMethod("maxRetries", Integer.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return builderClass.getMethod("maxRetries", int.class);
        } catch (NoSuchMethodException ignored) {
        }

        // Fallback: scan by name
        for (Method cand : builderClass.getMethods()) {
            if (!"maxRetries".equals(cand.getName()) || cand.getParameterCount() != 1) {
                continue;
            }
            Class<?> p = cand.getParameterTypes()[0];
            if (p == int.class || p == Integer.class) {
                return cand;
            }
        }
        return null;
    }
    /**
     * Best-effort apply optional builder methods (e.g., penalties) without
     * hard-wiring to a specific LangChain4j version.
     */
    private void invokeFluent(Object builder, String methodName, Double value) {
        if (value == null) {
            return;
        }

        try {
            Method method = null;
            try {
                method = builder.getClass().getMethod(methodName, Double.class);
            } catch (NoSuchMethodException ignored) {
                try {
                    method = builder.getClass().getMethod(methodName, double.class);
                } catch (NoSuchMethodException ignored2) {
                    // Fallback: pick any single-arg method with the same name and a numeric param.
                    for (Method cand : builder.getClass().getMethods()) {
                        if (!cand.getName().equals(methodName) || cand.getParameterCount() != 1) {
                            continue;
                        }
                        Class<?> p = cand.getParameterTypes()[0];
                        if (p.isPrimitive() && (p == double.class || p == float.class)) {
                            method = cand;
                            break;
                        }
                        if (!p.isPrimitive() && Number.class.isAssignableFrom(p)) {
                            method = cand;
                            break;
                        }
                    }
                }
            }

            if (method == null) {
                log.debug("Builder does not support {}(...); skipping", methodName);
                return;
            }

            Class<?> p = method.getParameterTypes()[0];
            Object arg;
            if (p == float.class || p == Float.class) {
                arg = value.floatValue();
            } else {
                // Covers double/double wrapper/Number.
                arg = value;
            }

            method.invoke(builder, arg);
        } catch (Exception ex) {
            // Non-fatal: we still want to build the model without penalties.
            log.warn("Failed to apply {}={} to builder: {}", methodName, value, ex.toString());
        }
    }
}
