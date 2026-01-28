package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.service.web.BraveSearchResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds lightweight per-provider events into {@link TraceStore} for quick root-cause triage.
 *
 * <p>
 * This is intentionally low-overhead and does not log full payloads. The primary goal is to
 * correlate "web starvation" (timeouts/cancel chains) with provider behavior using a stable
 * request correlation key (sid/rid) emitted by the logging pattern.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class WebProviderStructuredLogAspect {

    private static final Logger log = LoggerFactory.getLogger(WebProviderStructuredLogAspect.class);

    private final ObjectProvider<NightmareBreaker> breakerProvider;

    public WebProviderStructuredLogAspect(ObjectProvider<NightmareBreaker> breakerProvider) {
        this.breakerProvider = breakerProvider;
    }

    @Around("execution(* com.example.lms.service.web.BraveSearchService.searchWithMeta(..))")
    public Object aroundBrave(ProceedingJoinPoint pjp) throws Throwable {
        return around("Brave", NightmareKeys.WEBSEARCH_BRAVE, pjp);
    }

    @Around("execution(* com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundNaver(ProceedingJoinPoint pjp) throws Throwable {
        return around("Naver", NightmareKeys.WEBSEARCH_NAVER, pjp);
    }

    private Object around(String engine, String breakerKey, ProceedingJoinPoint pjp) throws Throwable {
        final long startNs = System.nanoTime();
        final long seq = nextSeq();

        Object[] args = pjp.getArgs();
        String q = (args != null && args.length >= 1) ? safeStr(args[0]) : "";
        Integer limit = (args != null && args.length >= 2) ? safeInt(args[1]) : null;

        appendEvent(eventMap(
                "seq", seq,
                "tNs", startNs,
                "engine", engine,
                "phase", "start",
                "q", clip(q, 64),
                "qLen", q == null ? 0 : q.length(),
                "limit", limit,
                "breaker", breakerState(breakerKey)
        ));

        try {
            Object out = pjp.proceed();
            long tookMs = (System.nanoTime() - startNs) / 1_000_000L;

            int outCount = safeOutCount(engine, out);
            String status = safeStatus(engine, out);
            Integer httpStatus = safeHttpStatus(engine, out);

            appendEvent(eventMap(
                    "seq", seq,
                    "tNs", System.nanoTime(),
                    "engine", engine,
                    "phase", "end",
                    "ok", Boolean.TRUE,
                    "tookMs", tookMs,
                    "out", outCount,
                    "status", status,
                    "httpStatus", httpStatus,
                    "breaker", breakerState(breakerKey)
            ));
            return out;
        } catch (Throwable t) {
            long tookMs = (System.nanoTime() - startNs) / 1_000_000L;
            Integer httpStatus = extractHttpStatus(t);
            appendEvent(eventMap(
                    "seq", seq,
                    "tNs", System.nanoTime(),
                    "engine", engine,
                    "phase", "end",
                    "ok", Boolean.FALSE,
                    "tookMs", tookMs,
                    "err", t.getClass().getSimpleName(),
                    "httpStatus", httpStatus,
                    "breaker", breakerState(breakerKey)
            ));

            // Keep one compact log line for cross-checking (TraceStore dumps may be truncated).
            if (log.isDebugEnabled()) {
                log.debug("[Nova][web.provider] {} error: {}{}", engine, t.toString(), LogCorrelation.suffix());
            }
            throw t;
        }
    }

    private static long nextSeq() {
        try {
            return TraceStore.nextSequence("web.provider");
        } catch (Throwable ignore) {
            return System.nanoTime();
        }
    }

    private static void appendEvent(Map<String, Object> base) {
        try {
            TraceStore.append("web.provider.events", new LinkedHashMap<>(base));
            TraceStore.inc("web.provider.events.count");
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    /**
     * Null-safe event builder.
     *
     * <p>Important: {@code Map.of(...)} disallows null keys/values and will throw
     * {@link NullPointerException}. In provider aspects this can masquerade as a
     * provider failure and poison downstream breaker metrics. We therefore build
     * events with a mutable {@link LinkedHashMap} and omit null values.
     */
    private static Map<String, Object> eventMap(Object... kv) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (kv == null) {
            return out;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object k = kv[i];
            Object v = kv[i + 1];
            if (k == null) {
                continue;
            }
            String key = String.valueOf(k);
            if (key.isBlank()) {
                continue;
            }
            // omit null values (signal absence by omitting the key)
            if (v == null) {
                continue;
            }
            out.put(key, v);
        }
        return out;
    }


    private String breakerState(String key) {
        try {
            NightmareBreaker breaker = breakerProvider.getIfAvailable();
            if (breaker == null || key == null) {
                return null;
            }
            if (breaker.isOpenOrHalfOpen(key)) {
                long rem = breaker.remainingOpenMs(key);
                return "open(remMs=" + rem + ")";
            }
            return "closed";
        } catch (Throwable ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static int safeOutCount(String engine, Object out) {
        try {
            if (out == null) return 0;
            if ("Brave".equals(engine) && out instanceof BraveSearchResult br) {
                return br.snippets() == null ? 0 : br.snippets().size();
            }
            if (out instanceof java.util.List<?> list) {
                return list.size();
            }
            return 1;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static String safeStatus(String engine, Object out) {
        try {
            if ("Brave".equals(engine) && out instanceof BraveSearchResult br) {
                return br.status() == null ? null : br.status().name();
            }
        } catch (Throwable ignore) {
            // best-effort
        }
        return null;
    }

    private static String safeStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Integer safeInt(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Number n) {
                return n.intValue();
            }
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable ignore) {
            return null;
        }

    }

    private static Integer safeHttpStatus(String engine, Object out) {
        try {
            if ("Brave".equals(engine) && out instanceof BraveSearchResult br) {
                return br.httpStatus();
            }
        } catch (Throwable ignore) {
            // best-effort
        }
        return null;
    }

    private static Integer extractHttpStatus(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable cur = t;
        for (int i = 0; i < 10 && cur != null; i++) {
            if (cur instanceof WebClientResponseException wcre) {
                try {
                    return wcre.getRawStatusCode();
                } catch (Throwable ignore) {
                    return null;
                }
            }
            cur = cur.getCause();
        }
        return null;

    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
