package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * - LangChain4j does not provide stable accessors across versions; we use defensive reflection.
 * - This aspect is intentionally behind a property gate to avoid log spam in prod.</p>
 */
@Aspect
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
        String model = tryExtractConfiguredModelId(target);
        String baseUrl = tryExtractBaseUrl(target);

        // Also record resolved config keys (helps when env vars override YAML to empty).
        String cfgFastModel = get("llm.fast.model", null);
        String cfgChatModel = get("llm.chat-model", null);
        String cfgFastBase = get("llm.fast.base-url", null);
        String cfgBase = get("llm.base-url", null);

        String argsInfo = summarizeArgs(pjp.getArgs());

        // record in request-scoped trace bag
        try {
            TraceStore.put("llm.trace.last.tag", tag);
            TraceStore.put("llm.trace.last.class", clazz);
            TraceStore.put("llm.trace.last.model", model);
            TraceStore.put("llm.trace.last.baseUrl", baseUrl);
            TraceStore.put("llm.trace.last.args", argsInfo);
            TraceStore.put("llm.trace.last.at", Instant.now().toString());
            TraceStore.put("llm.trace.cfg.llm.fast.model", cfgFastModel);
            TraceStore.put("llm.trace.cfg.llm.chat-model", cfgChatModel);
            TraceStore.put("llm.trace.cfg.llm.fast.base-url", cfgFastBase);
            TraceStore.put("llm.trace.cfg.llm.base-url", cfgBase);
        } catch (Throwable ignore) {
        }

        // highlight the common root cause (empty model -> server responds "model is required")
        boolean modelBlank = (model == null || model.trim().isEmpty());
        if (modelBlank) {
            log.warn("[LlmTrace] {} modelName is BLANK right before call (this usually triggers 'model is required'). " +
                            "class={} baseUrl={} cfg(llm.fast.model={}, llm.chat-model={}) args={}",
                    tag, clazz, baseUrl, safe(cfgFastModel), safe(cfgChatModel), argsInfo);
        } else if (alwaysInfo) {
            log.info("[LlmTrace] {} call -> baseUrl={} model={} class={} args={}",
                    tag, baseUrl, model, clazz, argsInfo);
        } else {
            log.debug("[LlmTrace] {} call -> baseUrl={} model={} class={} args={}",
                    tag, baseUrl, model, clazz, argsInfo);
        }

        try {
            Object out = pjp.proceed();
            long ms = (System.nanoTime() - startedNs) / 1_000_000L;

            try {
                TraceStore.put("llm.trace.last.ms", ms);
                TraceStore.put("llm.trace.last.ok", true);
            } catch (Throwable ignore) {
            }

            if (alwaysInfo) {
                log.info("[LlmTrace] {} ok {}ms baseUrl={} model={} ctx(auxHardDown={}, auxDegraded={}, bypass={}, irr={})",
                        tag, ms, baseUrl, model,
                        (ctx != null && ctx.isAuxHardDown()),
                        (ctx != null && ctx.isAuxDegraded()),
                        (ctx != null && ctx.isBypassMode()),
                        (ctx != null ? ctx.getIrregularityScore() : -1.0));
            } else {
                log.debug("[LlmTrace] {} ok {}ms baseUrl={} model={}", tag, ms, baseUrl, model);
            }

            return out;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - startedNs) / 1_000_000L;

            try {
                TraceStore.put("llm.trace.last.ms", ms);
                TraceStore.put("llm.trace.last.ok", false);
                TraceStore.put("llm.trace.last.err", t.getClass().getSimpleName());
            } catch (Throwable ignore) {
            }

            log.warn("[LlmTrace] {} FAIL {}ms baseUrl={} model={} class={} err={} msg={} args={}",
                    tag, ms, baseUrl, model, clazz,
                    t.getClass().getSimpleName(), safe(t.getMessage()), argsInfo);

            throw t;
        }
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.llm-trace.enabled", "false"));
    }

    private boolean isVerbose() {
        return "true".equalsIgnoreCase(get("nova.orch.debug.llm-trace.verbose", "false"));
    }

    private String get(String key, String def) {
        if (env == null || key == null) {
            return def;
        }
        try {
            String v = env.getProperty(key);
            return (v == null) ? def : v;
        } catch (Exception ignore) {
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

    // ---- reflection helpers (defensive; copied conceptually from PolicyBasedModelRouter) ----

    private static String tryExtractConfiguredModelId(Object model) {
        if (model == null) {
            return null;
        }

        // 1) common getter names
        String viaGetter = tryInvokeNoArgString(model, "modelName");
        if (viaGetter == null) viaGetter = tryInvokeNoArgString(model, "getModelName");
        if (viaGetter == null) viaGetter = tryInvokeNoArgString(model, "model");
        if (looksLikeModelId(viaGetter)) {
            return viaGetter.trim();
        }

        // 2) common field names
        String viaField = tryReadStringField(model, "modelName");
        if (viaField == null) viaField = tryReadStringField(model, "model");
        if (looksLikeModelId(viaField)) {
            return viaField.trim();
        }

        // 3) nested request params
        Object params = tryInvokeNoArg(model, "defaultRequestParameters");
        if (params == null) params = tryReadField(model, "defaultRequestParameters");
        if (params != null) {
            String nested = tryInvokeNoArgString(params, "modelName");
            if (nested == null) nested = tryReadStringField(params, "modelName");
            if (looksLikeModelId(nested)) {
                return nested.trim();
            }
        }

        // 4) last resort parse toString
        return firstTokenLikeModelId(String.valueOf(model));
    }

    private static String tryExtractBaseUrl(Object model) {
        if (model == null) {
            return null;
        }

        // 1) getter
        String viaGetter = tryInvokeNoArgString(model, "baseUrl");
        if (viaGetter == null) viaGetter = tryInvokeNoArgString(model, "getBaseUrl");
        if (looksLikeBaseUrl(viaGetter)) {
            return viaGetter.trim();
        }

        // 2) field
        String viaField = tryReadStringField(model, "baseUrl");
        if (looksLikeBaseUrl(viaField)) {
            return viaField.trim();
        }

        // 3) nested client/request object (best-effort)
        Object client = tryReadField(model, "client");
        if (client == null) client = tryReadField(model, "openAiClient");
        if (client != null) {
            String nested = tryInvokeNoArgString(client, "baseUrl");
            if (nested == null) nested = tryReadStringField(client, "baseUrl");
            if (looksLikeBaseUrl(nested)) {
                return nested.trim();
            }
        }

        // 4) parse toString for baseUrl=...
        String s = String.valueOf(model);
        String parsed = parseToStringForKey(s, "baseUrl");
        if (looksLikeBaseUrl(parsed)) {
            return parsed.trim();
        }

        return null;
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

    private static String tryInvokeNoArgString(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
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
        if (target == null || methodName == null) {
            return null;
        }
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
        if (target == null || fieldName == null) return null;
        Class<?> c = target.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 10) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static boolean looksLikeBaseUrl(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
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

    private static String firstTokenLikeModelId(String s) {
        if (s == null) return null;
        for (String tok : s.split("[\\s,;()]+")) {
            if (looksLikeModelId(tok)) {
                return tok.trim();
            }
        }
        return null;
    }

    private static String safe(String s) {
        if (s == null) return "null";
        String t = s.replaceAll("[\r\n\t]+", " ").trim();
        if (t.length() > 240) {
            return t.substring(0, 240) + "...";
        }
        return t;
    }
}
