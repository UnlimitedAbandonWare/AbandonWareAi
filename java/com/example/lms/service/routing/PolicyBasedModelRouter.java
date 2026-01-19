package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.AblationContributionTracker;
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

    @Value("${llm.chat.temperature:0.3}")
    private double defaultTemperature;
    @Value("${llm.fast.temperature:0.0}")
    private double fastTemperature;
    @Value("${llm.high.temperature:0.3}")
    private double highTemperature;

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

        // REWRITE/유틸 작업은 fast 모델로
        if (sig.intent() == RouteSignal.Intent.REWRITE) {
            if (log.isDebugEnabled()) {
                log.debug("[Router] intent=REWRITE -> fastModel");
            }
            try {
                TraceStore.put("ml.router.intent", "REWRITE");
                TraceStore.put("ml.router.selected", resolveModelName(fastModel));
                TraceStore.put("ml.router.reason", "intent=REWRITE");
            } catch (Throwable ignore) {
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

                // (E) Fix: never silently promote/fallback without leaving a breadcrumb.
                try {
                    TraceStore.put("ml.router.promote.blocked", true);
                    TraceStore.put("ml.router.promote.blocked.highModel", highName);
                    TraceStore.put("ml.router.promote.blocked.reason", "missing_credentials");
                    java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                    ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                    ev.put("ts", java.time.Instant.now().toString());
                    ev.put("event", "promote.blocked");
                    ev.put("highModel", highName);
                    ev.put("intent", String.valueOf(sig.intent()));
                    if (sig.reason() != null)
                        ev.put("reason", sig.reason());
                    TraceStore.append("ml.router.events", ev);
                    TraceStore.inc("ml.router.events.count");
                } catch (Throwable ignore) {
                }
                try {
                    AblationContributionTracker.recordPenaltyOnce(
                            "ablation.router.promote.blocked",
                            "router",
                            "promote_blocked",
                            0.15,
                            "missing_credentials");
                } catch (Throwable ignore) {
                }
                if (debugEventStore != null) {
                    try {
                        java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                        dd.put("highModel", highName);
                        dd.put("defaultModel", resolveModelName(defaultModel));
                        dd.put("fastModel", resolveModelName(fastModel));
                        dd.put("intent", String.valueOf(sig.intent()));
                        dd.put("reason", sig.reason());
                        debugEventStore.emit(
                                DebugProbeType.MODEL_GUARD,
                                DebugEventLevel.WARN,
                                "router.promote.blocked",
                                "Promotion requested but highModel is not serveable (missing credentials).",
                                "PolicyBasedModelRouter.route",
                                dd,
                                null);
                    } catch (Throwable ignore) {
                    }
                }

                if (fastModel != null) {
                    String fastName = resolveModelName(fastModel);
                    if (factory.canServe(fastName)) {
                        try {
                            TraceStore.put("ml.router.promote.fallback", "fastModel");
                            TraceStore.put("ml.router.selected", fastName);
                        } catch (Throwable ignore) {
                        }
                        return fastModel;
                    }
                }
                try {
                    TraceStore.put("ml.router.promote.fallback", "defaultModel");
                    TraceStore.put("ml.router.selected", resolveModelName(defaultModel));
                } catch (Throwable ignore) {
                }
                return defaultModel;
            }

            if (log.isDebugEnabled()) {
                log.debug("[Router] promote -> highModel (reason={})", sig.reason());
            }
            try {
                TraceStore.put("ml.router.promote", true);
                TraceStore.put("ml.router.selected", highName);
                TraceStore.put("ml.router.reason", sig.reason());
            } catch (Throwable ignore) {
            }
            return highModel;
        }

        try {
            TraceStore.put("ml.router.selected", resolveModelName(defaultModel));
            TraceStore.put("ml.router.reason", "default");
        } catch (Throwable ignore) {
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
        ChatModel base = route(intent, riskLevel, verbosityHint, targetMaxTokens);

        String req = (requestedModel == null) ? null : requestedModel.trim();
        if (req == null || req.isBlank()) {
            return base;
        }

        // 채팅 부적합 모델(embedding/legacy)이면 기본 모델로 폴백
        if (isDisallowedChatModel(req)) {
            String baseName = resolveModelName(base);
            log.warn("[Router] requestedModel='{}' is non-chat model; falling back to baseModel='{}'",
                    req, baseName);
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "disallowed_non_chat");
                TraceStore.put("ml.router.selected", baseName);
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.ignored");
                ev.put("requestedModel", req);
                ev.put("reason", "disallowed_non_chat");
                ev.put("selected", baseName);
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
            } catch (Throwable ignore) {
            }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("requestedModel", req);
                    dd.put("selected", baseName);
                    dd.put("reason", "disallowed_non_chat");
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.disallowed",
                            "Requested model is not suitable for chat; falling back to base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) {
                }
            }
            return base;
        }
        // Ignore wrapper labels or legacy values like
        // "OpenAiChatModel:fallback:evidence".
        if (looksLikeWrapperLabel(req)) {
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "wrapper_label");
                TraceStore.put("ml.router.selected", resolveModelName(base));
            } catch (Throwable ignore) {
            }
            return base;
        }
        if (factory == null) {
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "factory_unavailable");
                TraceStore.put("ml.router.selected", resolveModelName(base));
            } catch (Throwable ignore) {
            }
            return base;
        }

        // RequestedModelGate: provider 미구성(OpenAI 키 없음 등)이면 요청 모델 무시
        if (!factory.canServe(req)) {
            String baseName = resolveModelName(base);
            log.warn("[Router] requestedModel='{}' ignored (provider not configured). Using baseModel='{}'",
                    req, baseName);
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.ignored", true);
                TraceStore.put("ml.router.requestedModel.ignored.reason", "provider_not_configured");
                TraceStore.put("ml.router.selected", baseName);
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.ignored");
                ev.put("requestedModel", req);
                ev.put("reason", "provider_not_configured");
                ev.put("selected", baseName);
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
            } catch (Throwable ignore) {
            }
            try {
                AblationContributionTracker.recordPenaltyOnce(
                        "ablation.router.requested.ignored",
                        "router",
                        "requested_ignored",
                        0.10,
                        "provider_not_configured");
            } catch (Throwable ignore) {
            }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("requestedModel", req);
                    dd.put("selected", baseName);
                    dd.put("reason", "provider_not_configured");
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.ignored",
                            "Requested model ignored because provider is not configured; using base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) {
                }
            }
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
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.applied", true);
                TraceStore.put("ml.router.selected", req);
            } catch (Throwable ignore) {
            }

            ChatModel prev = requestedCache.putIfAbsent(key, built);
            return (prev != null) ? prev : built;
        } catch (Exception e) {
            log.warn("[Router] failed to build requested model='{}' (tier={}): {}", req, tier, e.toString());
            try {
                TraceStore.put("ml.router.requestedModel", req);
                TraceStore.put("ml.router.requestedModel.buildFailed", true);
                TraceStore.put("ml.router.requestedModel.buildFailed.tier", String.valueOf(tier));
                TraceStore.put("ml.router.requestedModel.buildFailed.error", e.getClass().getSimpleName());
                TraceStore.put("ml.router.selected", resolveModelName(base));
                java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("seq", TraceStore.nextSequence("ml.router.events"));
                ev.put("ts", java.time.Instant.now().toString());
                ev.put("event", "requested.buildFailed");
                ev.put("requestedModel", req);
                ev.put("tier", String.valueOf(tier));
                ev.put("error", e.getClass().getSimpleName());
                TraceStore.append("ml.router.events", ev);
                TraceStore.inc("ml.router.events.count");
            } catch (Throwable ignore) {
            }
            if (debugEventStore != null) {
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("requestedModel", req);
                    dd.put("tier", String.valueOf(tier));
                    dd.put("error", e.getClass().getSimpleName());
                    dd.put("message", String.valueOf(e.getMessage()));
                    dd.put("selected", resolveModelName(base));
                    debugEventStore.emit(
                            DebugProbeType.MODEL_GUARD,
                            DebugEventLevel.WARN,
                            "router.requestedModel.buildFailed",
                            "Failed to build requested model; falling back to base model.",
                            "PolicyBasedModelRouter.route(requestedModel)",
                            dd,
                            null);
                } catch (Throwable ignore) {
                }
            }
            return base;
        }
    }

    private enum Tier {
        FAST, DEFAULT, HIGH
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

        // Prefer the configured model id (e.g. "gpt-5.2-chat-latest") over wrapper
        // class names
        // (e.g. "OpenAiChatModel"). LangChain4j does not expose a stable accessor
        // across
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
        if (viaGetter == null)
            viaGetter = tryInvokeNoArgString(model, "getModelName");
        if (viaGetter == null)
            viaGetter = tryInvokeNoArgString(model, "model");
        if (viaGetter != null && looksLikeModelId(viaGetter)) {
            return viaGetter.trim();
        }

        // 2) Try common field names.
        String viaField = tryReadStringField(model, "modelName");
        if (viaField == null)
            viaField = tryReadStringField(model, "model");
        if (viaField != null && looksLikeModelId(viaField)) {
            return viaField.trim();
        }

        // 3) Nested request params (LangChain4j often keeps modelName there).
        Object params = tryInvokeNoArg(model, "defaultRequestParameters");
        if (params == null)
            params = tryReadField(model, "defaultRequestParameters");
        if (params != null) {
            String nested = tryInvokeNoArgString(params, "modelName");
            if (nested == null)
                nested = tryReadStringField(params, "modelName");
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
        } catch (Exception ignore) {
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
