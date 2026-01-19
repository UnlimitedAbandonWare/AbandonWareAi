package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Interrupt hygiene for pooled web-search threads.
 *
 * <p>EROR_AWS 로그에서 Naver korean-trace search가 InterruptedException으로 실패하는 케이스가
 * 반복적으로 관측됨. timeout/cancel이 발생하면 executor thread의 interrupt flag가 남아
 * 이후 작업까지 오염시키는 경우가 있어,
 *
 * <ul>
 *   <li>메서드 진입 시 interrupt 상태면 빠르게 빈 결과로 반환</li>
 *   <li>Interrupted/CANCELLED/TIMEOUT 계열 예외는 분리 분류 후 interrupt flag를 정리하고 빈 결과로 반환 (fail-soft)</li>
 * </ul>
 *
 * <p>추가(관측): TraceStore에 interrupt 정리/흡수 카운터를 남김.
 *
 * <p>주의: 오직 INTERRUPTED/CANCELLED/TIMEOUT 계열만 흡수하며, 그 외 예외는 그대로 throw.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NaverInterruptHygieneAspect {

    private static final Logger log = LoggerFactory.getLogger(NaverInterruptHygieneAspect.class);

    /** Coarse failure kinds we intentionally absorb (fail-soft) to avoid breaker poisoning. */
    private enum HygieneKind {
        INTERRUPTED,
        CANCELLED,
        TIMEOUT
    }

    @Around("execution(com.example.lms.service.NaverSearchService.SearchResult com.example.lms.service.NaverSearchService.searchWithTraceSync(..))")
    public Object aroundSearchWithTraceSync(ProceedingJoinPoint pjp) throws Throwable {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted(); // clear
            try {
                TraceStore.inc("naver.interruptHygiene.cleared.entry.count");
                TraceStore.inc("naver.interruptHygiene.cleared.entry.INTERRUPTED.count");
                TraceStore.put("naver.interruptHygiene.cleared.entry.method", "searchWithTraceSync");
                TraceStore.put("naver.interruptHygiene.cleared.entry.kind", "INTERRUPTED");
            } catch (Exception ignore) {
            }
            return emptyTraceResult();
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            HygieneKind kind = classify(t);
            if (kind != null) {
                // Clear only when needed; but never leave a poisoned interrupt flag behind.
                if (kind == HygieneKind.INTERRUPTED || Thread.currentThread().isInterrupted()) {
                    Thread.interrupted();
                }
                try {
                    TraceStore.inc("naver.interruptHygiene.swallowed.count");
                    TraceStore.inc("naver.interruptHygiene.swallowed." + kind.name() + ".count");
                    TraceStore.put("naver.interruptHygiene.swallowed.method", "searchWithTraceSync");
                    TraceStore.put("naver.interruptHygiene.swallowed.error", t.getClass().getSimpleName());
                    TraceStore.put("naver.interruptHygiene.swallowed.kind", kind.name());
                } catch (Exception ignore) {
                }
                log.debug("[nova][interrupt-hygiene] swallowed {} searchWithTraceSync(): {}", kind.name(), t.toString());
                return emptyTraceResult();
            }
            throw t;
        }
    }

    @Around("execution(java.util.List com.example.lms.service.NaverSearchService.searchSnippetsSync(..))")
    public Object aroundSearchSnippetsSync(ProceedingJoinPoint pjp) throws Throwable {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted(); // clear
            try {
                TraceStore.inc("naver.interruptHygiene.cleared.entry.count");
                TraceStore.inc("naver.interruptHygiene.cleared.entry.INTERRUPTED.count");
                TraceStore.put("naver.interruptHygiene.cleared.entry.method", "searchSnippetsSync");
                TraceStore.put("naver.interruptHygiene.cleared.entry.kind", "INTERRUPTED");
            } catch (Exception ignore) {
            }
            return Collections.emptyList();
        }

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            HygieneKind kind = classify(t);
            if (kind != null) {
                if (kind == HygieneKind.INTERRUPTED || Thread.currentThread().isInterrupted()) {
                    Thread.interrupted();
                }
                try {
                    TraceStore.inc("naver.interruptHygiene.swallowed.count");
                    TraceStore.inc("naver.interruptHygiene.swallowed." + kind.name() + ".count");
                    TraceStore.put("naver.interruptHygiene.swallowed.method", "searchSnippetsSync");
                    TraceStore.put("naver.interruptHygiene.swallowed.error", t.getClass().getSimpleName());
                    TraceStore.put("naver.interruptHygiene.swallowed.kind", kind.name());
                } catch (Exception ignore) {
                }
                log.debug("[nova][interrupt-hygiene] swallowed {} searchSnippetsSync(): {}", kind.name(), t.toString());
                return Collections.emptyList();
            }
            throw t;
        }
    }

    private NaverSearchService.SearchResult emptyTraceResult() {
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }


    private static HygieneKind classify(Throwable t) {
        if (looksInterrupted(t)) {
            return HygieneKind.INTERRUPTED;
        }
        if (looksTimeout(t)) {
            return HygieneKind.TIMEOUT;
        }
        if (looksCancelled(t)) {
            return HygieneKind.CANCELLED;
        }
        return null;
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

    private static boolean looksTimeout(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
            return true;
        }
        String name = t.getClass().getName();
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("timeout")) {
                return true;
            }
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            String m = msg.toLowerCase(Locale.ROOT);
            if (m.contains("timeout") || m.contains("timed out") || m.contains("time out")
                    || m.contains("timeout on blocking")) {
                return true;
            }
        }
        // Also scan suppressed, as Reactor sometimes attaches the real root as suppressed.
        try {
            Throwable[] sup = t.getSuppressed();
            if (sup != null) {
                for (Throwable s : sup) {
                    if (looksTimeout(s)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return looksTimeout(t.getCause());
    }

    private static boolean looksCancelled(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof CancellationException) {
            return true;
        }
        String name = t.getClass().getName();
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("cancel") || n.contains("cancellation")) {
                return true;
            }
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            String m = msg.toLowerCase(Locale.ROOT);
            if (m.contains("cancel") || m.contains("canceled") || m.contains("cancelled")
                    || m.contains("disposed") || m.contains("dispose")) {
                return true;
            }
        }

        // Heuristic for reactive/netty cancellation paths: empty-message exceptions where stack
        // indicates reactive cancellation/disposal.
        try {
            StackTraceElement[] st = t.getStackTrace();
            if (st != null) {
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    String cn = st[i] == null ? null : st[i].getClassName();
                    if (cn == null) continue;
                    String c = cn.toLowerCase(Locale.ROOT);
                    if ((c.contains("reactor") || c.contains("netty") || c.contains("webclient"))
                            && (c.contains("cancel") || c.contains("dispose"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }

        try {
            Throwable[] sup = t.getSuppressed();
            if (sup != null) {
                for (Throwable s : sup) {
                    if (looksCancelled(s)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }

        return looksCancelled(t.getCause());
    }
}
