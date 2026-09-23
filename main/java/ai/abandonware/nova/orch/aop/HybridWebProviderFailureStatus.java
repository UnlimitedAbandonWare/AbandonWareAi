package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;

import java.util.Locale;
import java.util.concurrent.CancellationException;

final class HybridWebProviderFailureStatus {

    private HybridWebProviderFailureStatus() {
    }

    static String from(Throwable t) {
        NightmareBreaker.FailureKind kind = NightmareBreaker.classify(t);
        if (kind == NightmareBreaker.FailureKind.TIMEOUT) {
            return "timeout";
        }
        if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
            return "rate-limit";
        }
        if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
            return isCancellation(t) ? "cancelled" : "interrupted";
        }
        return "provider-error";
    }

    private static boolean isCancellation(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            if (cur instanceof CancellationException) {
                return true;
            }
            String className = cur.getClass().getName();
            if (className != null) {
                String lower = className.toLowerCase(Locale.ROOT);
                if (lower.contains("cancel") && lower.contains("exception")) {
                    return true;
                }
            }
            Throwable cause = cur.getCause();
            if (cause == cur) {
                break;
            }
            cur = cause;
        }
        return false;
    }
}
