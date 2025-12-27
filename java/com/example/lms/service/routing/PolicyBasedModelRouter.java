package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.llm.DynamicChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Real ModelRouter implementation.
 *
 * <p>Replaces the pass-through router from {@link ModelRouterAutoConfig} by registering
 * a primary {@link ModelRouter} bean.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Utility/REWRITE intents -> fast model</li>
 *   <li>Promotion to high model is decided by {@link RouterPolicy}</li>
 *   <li>Fail-soft when optional models are missing</li>
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

    @Value("${llm.chat.temperature:0.3}")
    private double defaultTemperature;
    @Value("${llm.fast.temperature:0.0}")
    private double fastTemperature;
    @Value("${llm.high.temperature:0.3}")
    private double highTemperature;

    private final ConcurrentHashMap<String, ChatModel> requestedCache = new ConcurrentHashMap<>();

    public PolicyBasedModelRouter(
            ChatModel defaultModel,
            @Qualifier("fastChatModel") ObjectProvider<ChatModel> fastProvider,
            @Qualifier("highModel") ObjectProvider<ChatModel> highProvider,
            RouterPolicy policy,
            DynamicChatModelFactory factory
    ) {
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

        // REWRITE/유틸 작업은 fast 모델로
        if (sig.intent() == RouteSignal.Intent.REWRITE) {
            if (log.isDebugEnabled()) {
                log.debug("[Router] intent=REWRITE -> fastModel");
            }
            return fastModel;
        }

        boolean promote = false;
        try {
            promote = (policy != null) && policy.shouldPromote(sig);
        } catch (Exception e) {
            promote = false;
        }

        if (promote) {
            String highName = resolveModelName(highModel);
            if (factory != null && !factory.canServe(highName)) {
                log.warn("[Router] promote blocked: highModel='{}' (missing credentials). -> fastModel", highName);
                if (fastModel != null) {
                    String fastName = resolveModelName(fastModel);
                    if (factory.canServe(fastName)) {
                        return fastModel;
                    }
                }
                return defaultModel;
            }

            if (log.isDebugEnabled()) {
                log.debug("[Router] promote -> highModel (reason={})", sig.reason());
            }
            return highModel;
        }

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

        RouteSignal.Preference pref =
                (highRisk || i == RouteSignal.Intent.LATEST_TECH || v == RouteSignal.Verbosity.VERBOSE)
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
                "mapped"
        );

        return route(sig);
    }

    @Override
    public ChatModel route(String intent,
                           String riskLevel,
                           String verbosityHint,
                           Integer targetMaxTokens,
                           String requestedModel) {
        ChatModel base = route(intent, riskLevel, verbosityHint, targetMaxTokens);

        String req = (requestedModel == null) ? null : requestedModel.trim();
        if (req == null || req.isBlank()) {
            return base;
        }
        // Ignore wrapper labels or legacy values like "OpenAiChatModel:fallback:evidence".
        if (looksLikeWrapperLabel(req)) {
            return base;
        }
        if (factory == null) {
            return base;
        }

        // RequestedModelGate: provider 미구성(OpenAI 키 없음 등)이면 요청 모델 무시
        if (!factory.canServe(req)) {
            log.warn("[Router] requestedModel='{}' ignored (provider not configured). Using baseModel='{}'",
                    req, resolveModelName(base));
            return base;
        }

        Tier tier = (base == fastModel) ? Tier.FAST : (base == highModel) ? Tier.HIGH : Tier.DEFAULT;
        int maxTok = (targetMaxTokens != null && targetMaxTokens > 0) ? targetMaxTokens : 1024;
        int timeout = switch (tier) {
            case FAST -> fastTimeoutSeconds;
            case HIGH -> highTimeoutSeconds;
            default -> timeoutSeconds;
        };
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
            ChatModel prev = requestedCache.putIfAbsent(key, built);
            return (prev != null) ? prev : built;
        } catch (Exception e) {
            log.warn("[Router] failed to build requested model='{}' (tier={}): {}", req, tier, e.toString());
            return base;
        }
    }

    private enum Tier { FAST, DEFAULT, HIGH }

    private static boolean looksLikeWrapperLabel(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return false;

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

    @Override
    public ChatModel escalate(RouteSignal sig) {
        return highModel;
    }

    @Override
    public String resolveModelName(ChatModel model) {
        if (model == null) {
            return "unknown";
        }

        // Prefer the configured model id (e.g. "gpt-5.2-chat-latest") over wrapper class names
        // (e.g. "OpenAiChatModel"). LangChain4j does not expose a stable accessor across
        // versions, so we use defensive reflection.
        String configured = tryExtractConfiguredModelId(model);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return model.getClass().getSimpleName();
    }

    private static String tryExtractConfiguredModelId(ChatModel model) {
        // 1) Try common getter names (public / declared).
        String viaGetter = tryInvokeNoArgString(model, "modelName");
        if (viaGetter == null) viaGetter = tryInvokeNoArgString(model, "getModelName");
        if (viaGetter == null) viaGetter = tryInvokeNoArgString(model, "model");
        if (viaGetter != null && looksLikeModelId(viaGetter)) {
            return viaGetter.trim();
        }

        // 2) Try common field names.
        String viaField = tryReadStringField(model, "modelName");
        if (viaField == null) viaField = tryReadStringField(model, "model");
        if (viaField != null && looksLikeModelId(viaField)) {
            return viaField.trim();
        }

        // 3) Nested request params (LangChain4j often keeps modelName there).
        Object params = tryInvokeNoArg(model, "defaultRequestParameters");
        if (params == null) params = tryReadField(model, "defaultRequestParameters");
        if (params != null) {
            String nested = tryInvokeNoArgString(params, "modelName");
            if (nested == null) nested = tryReadStringField(params, "modelName");
            if (nested != null && looksLikeModelId(nested)) {
                return nested.trim();
            }
        }

        // 4) Last resort: parse toString() for a token that looks like a model id.
        return firstModelIdToken(String.valueOf(model));
    }

    private static String tryInvokeNoArgString(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            if (m.getReturnType() == String.class && m.getParameterCount() == 0) {
                return (String) m.invoke(target);
            }
        } catch (Exception ignore) {
        }
        try {
            Method m = target.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            if (m.getReturnType() == String.class && m.getParameterCount() == 0) {
                return (String) m.invoke(target);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            if (m.getParameterCount() == 0) {
                return m.invoke(target);
            }
        } catch (Exception ignore) {
        }
        try {
            Method m = target.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            if (m.getParameterCount() == 0) {
                return m.invoke(target);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String tryReadStringField(Object target, String fieldName) {
        Object v = tryReadField(target, fieldName);
        return (v instanceof String s) ? s : null;
    }

    private static Object tryReadField(Object target, String fieldName) {
        Class<?> c = target.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignore) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean looksLikeModelId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.length() > 96) return false;
        if (t.contains("http://") || t.contains("https://")) return false;
        if (t.chars().anyMatch(Character::isWhitespace)) return false;

        // allow tokens like "gpt-5.2-chat-latest", "qwen2.5-7b-instruct", "gemma3:27b"
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '/')) {
                return false;
            }
        }
        return true;
    }

    private static String firstModelIdToken(String s) {
        if (s == null) return null;
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
        } catch (Exception ignore) {
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.contains("rewrite") || s.contains("disamb") || s.contains("transform")) return RouteSignal.Intent.REWRITE;
            if (s.contains("code")) return RouteSignal.Intent.CODE;
            if (s.contains("search")) return RouteSignal.Intent.SEARCH_HEAVY;
            if (s.contains("latest") || s.contains("tech")) return RouteSignal.Intent.LATEST_TECH;
            if (s.contains("fact")) return RouteSignal.Intent.FACT;
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
