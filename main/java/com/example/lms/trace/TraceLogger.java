// src/main/java/com/example/lms/trace/TraceLogger.java
package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;




/**
 * Centralised logger for emitting structured trace events as NDJSON.
 *
 * <p>This class encapsulates logic around sampling, safe redaction, and
 * formatting of trace events.  It writes one JSON object per log
 * statement to a dedicated logger named {@code TRACE_JSON}.  The
 * underlying logback configuration is expected to route these events to
 * a rolling file appender configured as NDJSON.</p>
 */
public final class TraceLogger {
    /**
     * The backing SLF4J logger used for writing trace events.  This name
     * deliberately avoids the standard application loggers to allow
     * separate configuration via logback.
     */
    private static final Logger LOG = LoggerFactory.getLogger("TRACE_JSON");

    /** Jackson object mapper for serialising events. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** Whether tracing is globally enabled.  Controlled by system property
     * {@code lms.trace.enabled}, defaults to true. */
    public static boolean enabled = Boolean.parseBoolean(System.getProperty("lms.trace.enabled", "true"));

    /** Sampling ratio for emitting trace events.  A value of 1.0 logs all
     * events, 0.0 disables all.  Controlled by system property
     * {@code lms.trace.sample}. */
    public static double sample = parseDoubleProperty(System.getProperty("lms.trace.sample", "1.0"), 1.0d);

    /** Maximum preview length for prompt and response snippets.  Controlled
     * by system property {@code lms.trace.preview}. */
    public static int PREVIEW = parseIntProperty(System.getProperty("lms.trace.preview", "240"), 240);

    private TraceLogger() {}

    /**
     * Emit a structured trace event.  The event inherits the current MDC
     * values for {@code sid} and {@code trace}.  When tracing is
     * disabled or the event is dropped by sampling, this method is a
     * no-op.
     *
     * @param type  the event type (e.g. search_decision)
     * @param stage the pipeline stage (search, prompt, llm, post, summary)
     * @param kv    structured key/value data to include with the event
     */
    public static void emit(String type, String stage, Map<String, Object> kv) {
        if (!enabled) return;
        if (sample < 1.0 && Math.random() > sample) return;
        String sid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"));
        String trace = firstNonBlank(MDC.get("traceId"), MDC.get("trace"));
        String requestId = firstNonBlank(MDC.get("x-request-id"), trace);
        try {
            Map<String, Object> safeKv = sanitizeKv(kv);
            TraceEvent ev = new TraceEvent(Instant.now(), safeLabel(type), safeLabel(stage),
                    hashOrEmpty(sid),
                    hashOrEmpty(trace),
                    hashOrEmpty(requestId),
                    safeKv);
            LOG.info(MAPPER.writeValueAsString(ev));
        } catch (Exception ignore) {
            TraceStore.put("trace.logger.suppressed.emit", true);
            TraceStore.put("trace.logger.suppressed.emit.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            // suppress any logging exceptions to avoid interfering with core logic
        }
    }

    private static Map<String, Object> sanitizeKv(Map<String, Object> kv) {
        if (kv == null || kv.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : kv.entrySet()) {
            if (e == null || e.getKey() == null || e.getKey().isBlank()) continue;
            String key = e.getKey();
            out.put(safeLabel(key), SafeRedactor.diagnosticValue("trace." + key, e.getValue(), 2048));
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String safeLabel(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    static int parseIntProperty(String raw, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            TraceStore.put("trace.logger.suppressed.parseIntProperty", true);
            TraceStore.put("trace.logger.suppressed.parseIntProperty.errorType", "invalid_number");
            return fallback;
        }
    }

    static double parseDoubleProperty(String raw, double fallback) {
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            TraceStore.put("trace.logger.suppressed.parseDoubleProperty", true);
            TraceStore.put("trace.logger.suppressed.parseDoubleProperty.errorType", "invalid_number");
            return fallback;
        }
    }

    /**
     * Return a redacted preview of the given string.  The returned value
     * will be no longer than {@link #PREVIEW} characters.  Sensitive
     * substrings are masked via {@link SafeRedactor#redact(String)}.
     *
     * @param s the string to preview
     * @return the preview string, or null if the input was null
     */
    public static String preview(String s) {
        if (s == null) return null;
        String redacted = SafeRedactor.redact(s);
        int limit = Math.max(0, PREVIEW);
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit);
    }
}
