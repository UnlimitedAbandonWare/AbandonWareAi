package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
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
 * Why: pooled threads can carry stale interrupt flags from cancelled tasks.
 * HybridWebSearchProvider awaits provider futures and an interrupted thread can
 * cause
 * immediate InterruptedException (often with waitedMs=0), cascading into empty
 * results and noisy traces.
 *
 * <p>
 * This aspect:
 * <ul>
 * <li>Clears a stale interrupt flag at entry/exit of the provider
 * boundary.</li>
 * <li>Emits TraceStore keys for observability, including a compact stack digest
 * when a stale interrupt is cleared.</li>
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
        boolean clearedAtEntry = false;

        // Clear a stale interrupt flag before search execution.
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted(); // clears
            clearedAtEntry = true;
            try {
                TraceStore.inc("web.interruptHygiene.cleared.entry.count");
                TraceStore.put("web.interruptHygiene.cleared.entry.where", "HybridWebSearchProvider.search");
                TraceStore.put("web.interruptHygiene.cleared.entry.stack", stackDigest(12));
            } catch (Exception ignore) {
            }
        }

        boolean swallowed = false;

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            if (looksInterrupted(t)) {
                swallowed = true;
                Thread.interrupted(); // clear to avoid poisoning downstream stages
                try {
                    TraceStore.inc("web.interruptHygiene.swallowed.count");
                    TraceStore.put("web.interruptHygiene.swallowed.where", "HybridWebSearchProvider.search");
                    TraceStore.put("web.interruptHygiene.swallowed.error", t.getClass().getSimpleName());
                } catch (Exception ignore) {
                }
                log.debug(
                        "[nova][interrupt-hygiene] swallowed interrupt-like error from HybridWebSearchProvider.search(): {}",
                        t.toString());
                return Collections.emptyList();
            }
            throw t;
        } finally {
            // Detect the "waitedMs=0 interrupted" pattern (thread interrupt residual) for
            // debugging.
            try {
                observeWaitedMs0InterruptResidual();
                observeWebAwaitRootCause();
            } catch (Exception ignore) {
            }

            // If the provider left the thread in interrupted state, clear it to prevent
            // poisoning downstream stages.
            if (Thread.currentThread().isInterrupted()) {
                Thread.interrupted();
                try {
                    TraceStore.inc("web.interruptHygiene.cleared.exit.count");
                    TraceStore.put("web.interruptHygiene.cleared.exit.where", "HybridWebSearchProvider.search");
                    TraceStore.put("web.interruptHygiene.cleared.exit.stack", stackDigest(12));
                    if (clearedAtEntry) {
                        TraceStore.put("web.interruptHygiene.cleared.entryAndExit", true);
                    }
                    if (swallowed) {
                        TraceStore.put("web.interruptHygiene.swallowed.andExitInterrupted", true);
                    }
                } catch (Exception ignore) {
                }
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

                String eng = str(m0.get("engine"));
                if (eng != null && !eng.isBlank()) {
                    engines.add(eng);
                }
                String step = str(m0.get("step"));
                if (step != null && !step.isBlank()) {
                    steps.add(step);
                }

                if (digests.size() < 6) {
                    String d = (eng == null ? "?" : eng)
                            + ":" + (step == null ? "" : step)
                            + ":" + (cause.isBlank() ? "interrupt" : cause)
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
        } catch (Exception ignore) {
        }
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
            TraceStore.put("web.await.root.engine", str(best.get("engine")));
            TraceStore.put("web.await.root.stage", str(best.get("stage")));
            TraceStore.put("web.await.root.step", str(best.get("step")));
            TraceStore.put("web.await.root.cause", str(best.get("cause")));
            TraceStore.put("web.await.root.waitedMs", best.get("waitedMs"));
            String detail = str(best.get("detail"));
            if (detail != null) {
                if (detail.length() > 200) {
                    detail = detail.substring(0, 200);
                }
                TraceStore.put("web.await.root.detail", detail);
            }
        } catch (Exception ignore) {
        }
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

    private static long toLong(Object v) {
        if (v == null) {
            return -1L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignore) {
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
