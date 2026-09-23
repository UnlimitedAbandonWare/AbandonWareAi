package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interrupt hygiene for
 * {@code com.example.lms.search.provider.HybridWebSearchProvider}.
 *
 * <p>
 * Why: an interrupt is the caller's cancellation signal. Clearing it at this
 * boundary can let cancelled requests continue into provider and cache work.
 *
 * <p>
 * This aspect:
 * <ul>
 * <li>Observes and preserves an interrupt flag at entry/exit of the provider
 * boundary.</li>
 * <li>Skips provider execution when cancellation is already present.</li>
 * <li>Emits fixed TraceStore keys for preservation observability.</li>
 * <li>Detects waitedMs=0 interrupt patterns in {@code web.await.events} and
 * records an "interruptResidual" hint.</li>
 * <li>For interrupt-like exceptions that bubble up, fails-soft to an empty
 * list.</li>
 * </ul>
 *
 * <p>
 * Fail-soft by design: never breaks the request path.
 * </p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class HybridWebSearchInterruptHygieneAspect {

    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchInterruptHygieneAspect.class);

    @Around("execution(java.util.List com.example.lms.search.provider.HybridWebSearchProvider.search(..))")
    public Object aroundSearch(ProceedingJoinPoint pjp) throws Throwable {
        if (Thread.currentThread().isInterrupted()) {
            try {
                TraceStore.inc("web.interruptHygiene.preserved.entry.count");
                TraceStore.put("interrupt.preserved", true);
                TraceStore.put("web.interruptHygiene.preserved.entry.where", "HybridWebSearchProvider.search");
            } catch (Exception ignore) { traceSuppressed("entry.preserveTrace", ignore); }
            return Collections.emptyList();
        }

        boolean preservedThrow = false;

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            if (looksInterrupted(t)) {
                preservedThrow = true;
                Thread.currentThread().interrupt();
                try {
                    TraceStore.inc("web.interruptHygiene.swallowed.count");
                    TraceStore.put("web.interruptHygiene.swallowed.where", "HybridWebSearchProvider.search");
                    TraceStore.put("web.interruptHygiene.swallowed.error", "interrupted");
                    TraceStore.inc("web.interruptHygiene.preserved.throw.count");
                    TraceStore.put("interrupt.preserved", true);
                    TraceStore.put("web.interruptHygiene.preserved.throw.where", "HybridWebSearchProvider.search");
                    TraceStore.put("web.interruptHygiene.preserved.throw.error", "interrupted");
                } catch (Exception ignore) { traceSuppressed("throw.preserveTrace", ignore); }
                log.debug(
                        "[nova][interrupt-hygiene] preserved interrupt-like cancellation from HybridWebSearchProvider.search(): errorHash={} errorLength={}",
                        SafeRedactor.hashValue(t.getMessage()), t.getMessage() == null ? 0 : t.getMessage().length());
                return Collections.emptyList();
            }
            throw t;
        } finally {
            // Detect the "waitedMs=0 interrupted" pattern (thread interrupt residual) for
            // debugging.
            try {
                observeWaitedMs0InterruptResidual();
                observeWebAwaitRootCause();
            } catch (Exception ignore) { traceSuppressed("finally.observe", ignore); }

            if (Thread.currentThread().isInterrupted()) {
                try {
                    TraceStore.inc("web.interruptHygiene.preserved.exit.count");
                    TraceStore.put("interrupt.preserved", true);
                    TraceStore.put("web.interruptHygiene.preserved.exit.where", "HybridWebSearchProvider.search");
                    if (preservedThrow) {
                        TraceStore.put("web.interruptHygiene.preserved.throwAndExit", true);
                    }
                } catch (Exception ignore) { traceSuppressed("exit.preserveTrace", ignore); }
            }
        }
    }

    private static void observeWaitedMs0InterruptResidual() {
        Object raw = TraceStore.get("web.await.events");
        if (!(raw instanceof List<?> events) || events.isEmpty()) {
            return;
        }

        long waited0 = 0L;
        LinkedHashSet<String> engines = new LinkedHashSet<>();
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        ArrayList<String> digests = new ArrayList<>();

        for (Object o : events) {
            if (!(o instanceof Map<?, ?> m0) || m0.isEmpty()) {
                continue;
            }

            String cause = lower(m0.get("cause"));
            String err = lower(m0.get("err"));
            long waitedMs = toLong(m0.get("waitedMs"));

            boolean interruptLike = cause.contains("interrupt")
                    || cause.contains("intentional_cancel")
                    || cause.contains("cancel")
                    || err.contains("interrupt")
                    || err.contains("cancel");

            if (interruptLike && waitedMs == 0L) {
                waited0++;

                String eng = safeLabel(m0.get("engine"));
                if (eng != null && !eng.isBlank()) {
                    engines.add(eng);
                }
                String step = safeLabel(m0.get("step"));
                if (step != null && !step.isBlank()) {
                    steps.add(step);
                }

                if (digests.size() < 6) {
                    String d = (eng == null ? "?" : eng)
                            + ":" + (step == null ? "" : step)
                            + ":" + safeLabel(cause.isBlank() ? "interrupt" : cause)
                            + ":waitedMs=0";
                    digests.add(d);
                }
            }
        }

        if (waited0 <= 0L) {
            return;
        }

        try {
            TraceStore.put("web.await.interruptResidual.waitedMs0.count", waited0);
            if (!engines.isEmpty()) {
                TraceStore.put("web.await.interruptResidual.waitedMs0.engines", String.join(",", engines));
            }
            if (!steps.isEmpty()) {
                TraceStore.put("web.await.interruptResidual.waitedMs0.steps", String.join(",", steps));
            }
            if (!digests.isEmpty()) {
                TraceStore.put("web.await.interruptResidual.waitedMs0.digest", String.join(" | ", digests));
            }
        } catch (Exception ignore) { traceSuppressed("interruptResidual.trace", ignore); }
    }

    /**
     * Persist a single "root" await failure summary so operations can jump to the
     * most likely trigger
     * without scanning the full {@code web.await.events} list.
     */
    @SuppressWarnings("unchecked")
    private static void observeWebAwaitRootCause() {
        Object raw = TraceStore.get("web.await.events");
        if (!(raw instanceof List<?> events) || events.isEmpty()) {
            return;
        }

        Map<String, Object> best = null;
        long bestSeq = Long.MAX_VALUE;
        for (Object o : events) {
            if (!(o instanceof Map<?, ?> m0) || m0.isEmpty()) {
                continue;
            }
            Object okVal = m0.get("ok");
            boolean ok = (okVal == null) || Boolean.parseBoolean(String.valueOf(okVal));
            if (ok) {
                continue;
            }
            long seq = toLong(m0.get("seq"));
            if (seq < 0) {
                // Fallback to time-based comparison.
                seq = toLong(m0.get("tNs"));
            }
            if (seq < 0) {
                continue;
            }
            if (seq < bestSeq) {
                bestSeq = seq;
                best = (Map<String, Object>) m0;
            }
        }

        if (best == null) {
            return;
        }

        try {
            TraceStore.put("web.await.root.engine", safeLabel(best.get("engine")));
            TraceStore.put("web.await.root.stage", safeLabel(best.get("stage")));
            TraceStore.put("web.await.root.step", safeLabel(best.get("step")));
            TraceStore.put("web.await.root.cause", safeLabel(best.get("cause")));
            TraceStore.put("web.await.root.waitedMs", best.get("waitedMs"));
            String detail = str(best.get("detail"));
            if (detail != null) {
                if (detail.length() > 200) {
                    int end = 200;
                    if (Character.isHighSurrogate(detail.charAt(end - 1))
                            && Character.isLowSurrogate(detail.charAt(end))) {
                        end--;
                    }
                    detail = detail.substring(0, end);
                }
                TraceStore.put("web.await.root.detail",
                        SafeRedactor.diagnosticValue("web.await.root.detail", detail, 200));
            }
        } catch (Exception ignore) { traceSuppressed("awaitRoot.trace", ignore); }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        TraceStore.put("web.interruptHygiene.suppressed.stage", safeStage);
        TraceStore.put("web.interruptHygiene.suppressed.errorType", errorType);
        TraceStore.put("web.interruptHygiene.suppressed." + safeStage, true);
        TraceStore.put("web.interruptHygiene.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String stackDigest(int maxFrames) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            if (st == null || st.length == 0) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            int added = 0;
            for (int i = 2; i < st.length; i++) {
                StackTraceElement e = st[i];
                if (e == null) {
                    continue;
                }
                String cls = e.getClassName();
                if (cls == null) {
                    continue;
                }
                // Trim noise.
                if (cls.startsWith("java.lang.Thread")) {
                    continue;
                }
                if (cls.startsWith(HybridWebSearchInterruptHygieneAspect.class.getName())) {
                    continue;
                }

                if (added > 0) {
                    sb.append(" <- ");
                }
                sb.append(cls)
                        .append("#").append(e.getMethodName())
                        .append(":").append(e.getLineNumber());

                if (++added >= maxFrames) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception ignore) {
            traceSuppressed("stackDigest", ignore);
            return "";
        }
    }

    private static String lower(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v).toLowerCase(Locale.ROOT).trim();
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static String safeLabel(Object v) {
        String s = str(v);
        if (s == null) {
            return null;
        }
        String oneLine = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (oneLine.matches("[A-Za-z0-9_.:-]{1,80}")
                && !oneLine.toLowerCase(Locale.ROOT).matches(".*(authorization|owner-token|owner_token|cookie|secret=|token=|api_key=|apikey=|password=).*")) {
            return oneLine;
        }
        return SafeRedactor.hashValue(oneLine);
    }

    private static long toLong(Object v) {
        if (v == null) {
            return -1L;
        }
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                traceSuppressed("toLong", new NumberFormatException("non-finite"));
                return -1L;
            }
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignore) {
            traceSuppressed("toLong", ignore);
            return -1L;
        }
    }

    private static boolean looksInterrupted(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof InterruptedException) {
            return true;
        }
        String name = t.getClass().getName();
        if (name != null && name.contains("Interrupted")) {
            return true;
        }
        return looksInterrupted(t.getCause());
    }
}
