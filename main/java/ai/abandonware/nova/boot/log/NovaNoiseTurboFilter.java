package ai.abandonware.nova.boot.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Marker;
import java.io.IOException;

/**
 * Drops known noisy logs that are expected during fail-soft degradation.
 *
 * <p>Targeted filters only (narrow match) to avoid hiding real errors.</p>
 */
public class NovaNoiseTurboFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (logger == null || level == null) {
            return FilterReply.NEUTRAL;
        }

        String name = logger.getName();

        // 1) AnalyzeWebSearchRetriever "Failed to flatten JSON snippet" WARN spam.
        if (level == Level.WARN && name != null && name.endsWith("AnalyzeWebSearchRetriever")) {
            if (format != null && format.contains("Failed to flatten JSON snippet")) {
                return FilterReply.DENY;
            }
        }

        // 1.5) OllamaEmbeddingModel "dimension mismatch" warnings: often a Matryoshka slice
        // (actual vector larger than configured target). It's not a fatal mismatch; downstream
        // can safely truncate/pad. We suppress the misleading WARN and let the embedding
        // normalizer emit a single INFO "matryoshka slice" marker.
        if (level == Level.WARN && name != null && name.endsWith("OllamaEmbeddingModel")) {
            if (format != null && format.contains("dimension mismatch in embedding vector")) {
                try {
                    if (params != null && params.length >= 2) {
                        int actual = safeInt(params[0], -1);
                        int configured = safeInt(params[1], -1);
                        if (actual > 0 && configured > 0 && actual > configured) {
                            return FilterReply.DENY;
                        }
                    }
                } catch (RuntimeException parseError) {
                    recordNoiseFilterFallback("dimension_mismatch_params", parseError);
                }
            }
        }

        // 2) Fail-soft LLM timeout fast-bail: avoid huge ERROR stacks when we intentionally degrade.
        if (level.isGreaterOrEqual(Level.ERROR)) {
            if (isTomcatSseClientDisconnect(name, format, t)) {
                return FilterReply.DENY;
            }
            if (isFastBailThrowable(t)) {
                return FilterReply.DENY;
            }
            if (format != null && format.contains("LLM timeout fast-bail")) {
                return FilterReply.DENY;
            }
        }

        return FilterReply.NEUTRAL;
    }

    private static int safeInt(Object o, int def) {
        if (o == null) return def;
        try {
            if (o instanceof Number n) {
                return n.intValue();
            }
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (NumberFormatException parseError) {
            recordNoiseFilterFallback("safe_int", parseError);
            return def;
        }
    }

    private static void recordNoiseFilterFallback(String stage, Throwable error) {
        TraceStore.inc("nova.noiseFilter.fallback.count");
        TraceStore.put("nova.noiseFilter.fallback.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("nova.noiseFilter.fallback.errorType", errorType(stage, error));
    }

    private static String errorType(String stage, Throwable error) {
        return "safe_int".equals(stage) ? "invalid_number" : errorType(error);
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private boolean isFastBailThrowable(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable cur = t;
        while (cur != null) {
            String cn = cur.getClass().getName();
            if (cn != null && cn.endsWith("LlmFastBailoutException")) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.contains("LLM timeout fast-bail")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean isTomcatSseClientDisconnect(String loggerName, String format, Throwable t) {
        if (loggerName == null || format == null || t == null) {
            return false;
        }
        if (!loggerName.contains("dispatcherServlet")) {
            return false;
        }
        if (!format.contains("Servlet.service()")) {
            return false;
        }
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof IOException
                    && hasStackFrame(
                    cur,
                    "org.springframework.web.servlet.mvc.method.annotation.ReactiveTypeHandler$SseEmitterSubscriber",
                    "send")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean hasStackFrame(Throwable t, String className, String methodName) {
        if (t == null) {
            return false;
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            if (className.equals(frame.getClassName()) && methodName.equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
