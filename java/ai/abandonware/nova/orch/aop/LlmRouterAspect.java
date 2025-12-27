package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.OpenAiTokenParamCompat;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Locale;

/**
 * Orchestration glue for UAW's llmrouter.* logical model IDs.
 *
 * <p>Intercepts DynamicChatModelFactory.lcWithTimeout(..) and resolves:
 * - llmrouter.<key> -> llmrouter.models.<key> mapping
 * - llmrouter.auto / llmrouter -> automatic selection via {@link LlmRouterBandit}
 */
@Aspect
public class LlmRouterAspect {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterAspect.class);

    private final Environment env;
    private final LlmRouterProperties props;
    private final LlmRouterBandit bandit;

    public LlmRouterAspect(Environment env, LlmRouterProperties props, LlmRouterBandit bandit) {
        this.env = env;
        this.props = props;
        this.bandit = bandit;
    }

    @Around("execution(* com.example.lms.llm.DynamicChatModelFactory.lcWithTimeout(..))")
    public Object aroundLcWithTimeout(ProceedingJoinPoint pjp) throws Throwable {
        if (props == null || !props.isEnabled() || bandit == null) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();

        // Optional alias mapping for legacy/default model ids.
        boolean argsRewritten = false;
        if (args != null && args.length > 0 && args[0] instanceof String modelId) {
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

        // 1) Resolve llmrouter.* directly.
        LlmRouterBandit.Selected sel = bandit.pick(ca.requestedModelId);
        if (sel != null) {
            return buildRoutedModel(sel, ca);
        }

        // 2) Otherwise proceed; if OpenAI key is missing, optionally fall back to llmrouter.auto.
        try {
            return argsRewritten ? pjp.proceed(args) : pjp.proceed();
        } catch (IllegalStateException ise) {
            if (props.isFallbackWhenOpenAiMissing() && looksLikeMissingOpenAiKey(ise)) {
                LlmRouterBandit.Selected autoSel = bandit.pick("llmrouter.auto");
                if (autoSel != null) {
                    log.warn("[llmrouter] OpenAI key missing; falling back to local auto route key={}", autoSel.key());
                    return buildRoutedModel(autoSel, ca);
                }
            }
            throw ise;
        }
    }

    private ChatModel buildRoutedModel(LlmRouterBandit.Selected sel, CallArgs ca) {
        String key = sel.key();

        String modelName = firstNonBlank(
                sel.cfg() != null ? sel.cfg().getName() : null,
                get("llm.chat-model"),
                ca.requestedModelId
        );

        String rawBaseUrl = firstNonBlank(
                sel.cfg() != null ? sel.cfg().getBaseUrl() : null,
                get("llm.base-url"),
                get("llm.ollama.base-url"),
                "http://localhost:11434/v1"
        );

        String baseUrl = normalizeBaseUrl(rawBaseUrl);

        // carry to request end (for success/fail recording)
        LlmRouterContext.set(key, baseUrl, modelName);

        Double temperature = ModelCapabilities.sanitizeTemperature(modelName, ca.temperature);
        Double topP = ModelCapabilities.sanitizeTopP(modelName, ca.topP);
        Double freq = ModelCapabilities.sanitizeFrequencyPenalty(modelName, ca.frequencyPenalty);
        Double pres = ModelCapabilities.sanitizePresencePenalty(modelName, ca.presencePenalty);

        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveLocalApiKey())
                .modelName(modelName)
                .timeout(Duration.ofSeconds(Math.max(1, ca.timeoutSeconds)));

        if (temperature != null) {
            b.temperature(temperature);
        }
        if (topP != null) {
            b.topP(topP);
        }

        // penalties are optional depending on langchain4j version
        invokeFluent(b, "frequencyPenalty", freq);
        invokeFluent(b, "presencePenalty", pres);

        if (ca.maxTokens != null && ca.maxTokens > 0
                && OpenAiTokenParamCompat.shouldSendLegacyMaxTokens(modelName, baseUrl)) {
            b.maxTokens(ca.maxTokens);
        }

        return b.build();
    }

    private String resolveLocalApiKey() {
        // DynamicChatModelFactory's local key default is sk-local; replicate that behavior.
        return firstNonBlank(get("llm.api-key"), get("LLM_API_KEY"), "sk-local");
    }

    private String get(String key) {
        if (env == null || key == null) {
            return null;
        }
        try {
            return env.getProperty(key);
        } catch (Exception ignore) {
            return null;
        }
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
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1")) {
            return s;
        }
        return s + "/v1";
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

    private static void invokeFluent(Object builder, String methodName, Double arg) {
        if (builder == null || methodName == null || arg == null) {
            return;
        }
        try {
            builder.getClass().getMethod(methodName, Double.class).invoke(builder, arg);
        } catch (Exception ignore) {
            // ignore: optional API
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

        private CallArgs(
                String requestedModelId,
                Double temperature,
                Double topP,
                Double frequencyPenalty,
                Double presencePenalty,
                Integer maxTokens,
                int timeoutSeconds
        ) {
            this.requestedModelId = requestedModelId;
            this.temperature = temperature;
            this.topP = topP;
            this.frequencyPenalty = frequencyPenalty;
            this.presencePenalty = presencePenalty;
            this.maxTokens = maxTokens;
            this.timeoutSeconds = timeoutSeconds;
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
                        safeInt(args[4])
                );
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
                        safeInt(args[6])
                );
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
