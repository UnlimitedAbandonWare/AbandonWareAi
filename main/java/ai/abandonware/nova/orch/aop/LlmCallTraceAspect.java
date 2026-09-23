package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Debug/trace aspect for LangChain4j ChatModel calls.
 *
 * <p>Primary goal:
 * - Log the <b>effective</b> baseUrl / modelName right before the HTTP request goes out
 *   (especially for {@code fastChatModel} auxiliary calls).</p>
 *
 * <p>Notes:
 * - Uses the configured bean role and redacted settings instead of inspecting
 *   LangChain4j private fields.
 * - This aspect is intentionally behind a property gate to avoid log spam in prod.</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class LlmCallTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(LlmCallTraceAspect.class);

    private final Environment env;

    public LlmCallTraceAspect(Environment env) {
        this.env = env;
    }

    // ---- fastChatModel (aux) ----

    @Around("execution(* dev.langchain4j.model.chat.ChatModel.chat(..)) && bean(fastChatModel)")
    public Object aroundFastChatModel(ProceedingJoinPoint pjp) throws Throwable {
        if (!isEnabled()) {
            return pjp.proceed();
        }
        return trace("fastChatModel", pjp, /*alwaysInfo*/ isVerbose());
    }

    // ---- optionally trace primary models too (useful when 'model is required' happens in chat:draft) ----

    @Around("execution(* dev.langchain4j.model.chat.ChatModel.chat(..)) && (bean(chatModel) || bean(highModel) || bean(miniModel) || bean(greenChatModel))")
    public Object aroundPrimaryModels(ProceedingJoinPoint pjp) throws Throwable {
        if (!isEnabled()) {
            return pjp.proceed();
        }
        if (!"all".equalsIgnoreCase(get("nova.orch.debug.llm-trace.scope", "fast"))) {
            return pjp.proceed();
        }
        return trace("chatModel*", pjp, /*alwaysInfo*/ isVerbose());
    }

    private Object trace(String tag, ProceedingJoinPoint pjp, boolean alwaysInfo) throws Throwable {
        long startedNs = System.nanoTime();

        Object target = pjp.getTarget();
        String clazz = (target == null) ? "null" : target.getClass().getName();

        GuardContext ctx = GuardContextHolder.getOrDefault();
        String model = configuredModelForTag(tag, target);
        String baseUrl = configuredBaseUrlForTag(tag, target);

        // Also record resolved config keys (helps when env vars override YAML to empty).
        String cfgFastModel = get("llm.fast.model", null);
        String cfgChatModel = get("llm.chat-model", null);
        String cfgFastBase = get("llm.fast.base-url", null);
        String cfgBase = get("llm.base-url", null);

        String argsSummary = summarizeArgs(pjp.getArgs());
        String argsHash = SafeRedactor.hashValue(argsSummary);
        String modelHash = SafeRedactor.hashValue(model);
        String baseUrlHost = hostOf(baseUrl);
        String baseUrlHash = SafeRedactor.hashValue(baseUrl);

        // record in request-scoped trace bag
        try {
            TraceStore.put("llm.trace.last.tag", tag);
            TraceStore.put("llm.trace.last.class", clazz);
            TraceStore.put("llm.trace.last.modelHash", modelHash);
            TraceStore.put("llm.trace.last.baseUrlHost", baseUrlHost);
            TraceStore.put("llm.trace.last.baseUrlHash", baseUrlHash);
            TraceStore.put("llm.trace.last.argsSummary", argsSummary);
            TraceStore.put("llm.trace.last.argsHash", argsHash);
            TraceStore.put("llm.trace.last.at", Instant.now().toString());
            TraceStore.put("llm.trace.cfg.llm.fast.modelHash", SafeRedactor.hashValue(cfgFastModel));
            TraceStore.put("llm.trace.cfg.llm.chat-modelHash", SafeRedactor.hashValue(cfgChatModel));
            TraceStore.put("llm.trace.cfg.llm.fast.base-urlHost", hostOf(cfgFastBase));
            TraceStore.put("llm.trace.cfg.llm.base-urlHost", hostOf(cfgBase));
        } catch (Throwable ignore) {
            logSuppressed("trace.preCall", ignore);
        }

        // highlight the common root cause (empty model -> server responds "model is required")
        boolean modelBlank = (model == null || model.trim().isEmpty());
        if (modelBlank) {
            log.warn("[LlmTrace] {} modelName is BLANK right before call (this usually triggers 'model is required'). " +
                            "class={} baseUrlHost={} cfg(llm.fast.modelHash={}, llm.chat-modelHash={}) argsSummary={} argsHash={}",
                    tag, clazz, baseUrlHost, SafeRedactor.hashValue(cfgFastModel), SafeRedactor.hashValue(cfgChatModel), argsSummary, argsHash);
        } else if (alwaysInfo) {
            log.info("[LlmTrace] {} call -> baseUrlHost={} modelHash={} class={} argsSummary={} argsHash={}",
                    tag, baseUrlHost, modelHash, clazz, argsSummary, argsHash);
        } else {
            log.debug("[LlmTrace] {} call -> baseUrlHost={} modelHash={} class={} argsSummary={} argsHash={}",
                    tag, baseUrlHost, modelHash, clazz, argsSummary, argsHash);
        }

        try {
            Object out = pjp.proceed();
            long ms = (System.nanoTime() - startedNs) / 1_000_000L;

            try {
                TraceStore.put("llm.trace.last.ms", ms);
                TraceStore.put("llm.trace.last.ok", true);
            } catch (Throwable ignore) {
                logSuppressed("trace.success", ignore);
            }

            if (alwaysInfo) {
                log.info("[LlmTrace] {} ok {}ms baseUrlHost={} modelHash={} ctx(auxHardDown={}, auxDegraded={}, bypass={}, irr={})",
                        tag, ms, baseUrlHost, modelHash,
                        (ctx != null && ctx.isAuxHardDown()),
                        (ctx != null && ctx.isAuxDegraded()),
                        (ctx != null && ctx.isBypassMode()),
                        (ctx != null ? ctx.getIrregularityScore() : -1.0));
            } else {
                log.debug("[LlmTrace] {} ok {}ms baseUrlHost={} modelHash={}", tag, ms, baseUrlHost, modelHash);
            }

            return out;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - startedNs) / 1_000_000L;

            try {
                TraceStore.put("llm.trace.last.ms", ms);
                TraceStore.put("llm.trace.last.ok", false);
                TraceStore.put("llm.trace.last.err", "llm_trace_call_failed");
            } catch (Throwable ignore) {
                logSuppressed("trace.failure", ignore);
            }

            log.warn("[LlmTrace] {} FAIL {}ms baseUrlHost={} modelHash={} class={} err={} errorHash={} errorLength={} argsSummary={} argsHash={}",
                    tag, ms, baseUrlHost, modelHash, clazz,
                    t.getClass().getSimpleName(), SafeRedactor.hashValue(t.getMessage()),
                    t.getMessage() == null ? 0 : t.getMessage().length(), argsSummary, argsHash);

            throw t;
        }
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.llm-trace.enabled", "false"));
    }

    private boolean isVerbose() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.llm-trace.verbose", "false"));
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (log.isDebugEnabled()) {
            log.debug("[LlmTrace] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        }
    }

    private String get(String key, String def) {
        if (env == null || key == null) {
            return def;
        }
        try {
            String v = env.getProperty(key);
            return (v == null) ? def : v;
        } catch (Exception ignore) {
            logSuppressed("config.get", ignore);
            return def;
        }
    }

    private static String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            if (a == null) {
                sb.append("null");
                continue;
            }
            if (a instanceof String s) {
                sb.append("String(len=").append(s.length()).append(')');
                continue;
            }
            if (a instanceof List<?> list) {
                int size = list.size();
                int totalChars = 0;
                int guard = 0;
                for (Object e : list) {
                    if (e == null) continue;
                    String es = String.valueOf(e);
                    totalChars += es.length();
                    if (guard++ > 12) break;
                }
                sb.append("List(size=").append(size).append(", approxChars=").append(totalChars).append(')');
                continue;
            }
            sb.append(a.getClass().getSimpleName());
        }
        sb.append(']');
        return sb.toString();
    }

    private String configuredModelForTag(String tag, Object target) {
        String configured = tag != null && tag.startsWith("fast")
                ? firstNonBlank(get("llm.fast.model", null), get("llm.chat-model", null), get("llm.model", null))
                : firstNonBlank(get("llm.chat-model", null), get("llm.model", null), get("llm.fast.model", null));
        if (looksLikeModelId(configured)) {
            return configured.trim();
        }
        String parsed = parseToStringForKey(String.valueOf(target), "modelName");
        if (looksLikeModelId(parsed)) {
            return parsed.trim();
        }
        return firstTokenLikeModelId(String.valueOf(target));
    }

    private String configuredBaseUrlForTag(String tag, Object target) {
        String configured = tag != null && tag.startsWith("fast")
                ? firstNonBlank(get("llm.fast.base-url", null), get("llm.base-url", null), get("llm.ollama.base-url", null))
                : firstNonBlank(get("llm.base-url", null), get("llm.ollama.base-url", null), get("llm.fast.base-url", null));
        if (looksLikeBaseUrl(configured)) {
            return configured.trim();
        }
        String parsed = parseToStringForKey(String.valueOf(target), "baseUrl");
        return looksLikeBaseUrl(parsed) ? parsed.trim() : null;
    }

    private static String parseToStringForKey(String s, String key) {
        if (s == null || key == null) return null;
        String needle = key + "=";
        int idx = s.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        int end = s.indexOf(',', start);
        if (end < 0) end = s.indexOf(')', start);
        if (end < 0) end = Math.min(s.length(), start + 200);
        String out = s.substring(start, end).trim();
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() >= 2) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out.isEmpty() ? null : out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean looksLikeBaseUrl(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static String hostOf(String s) {
        if (!looksLikeBaseUrl(s)) return null;
        try {
            URI uri = URI.create(s.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            logSuppressed("hostOf", ignored);
            return null;
        }
    }

    private static boolean looksLikeModelId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.length() > 96) return false;
        if (t.contains("http://") || t.contains("https://")) return false;
        if (t.chars().anyMatch(Character::isWhitespace)) return false;

        // allow tokens like "gpt-5.5", "qwen2.5-7b-instruct", "gemma3:27b"
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '/')) {
                return false;
            }
        }
        return true;
    }

    private static String firstTokenLikeModelId(String s) {
        if (s == null) return null;
        for (String tok : s.split("[\\s,;()]+")) {
            if (looksLikeModelId(tok)) {
                return tok.trim();
            }
        }
        return null;
    }

}
