package com.example.lms.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic redaction regressions for {@link ApiRoutingDebug}. Every secret
 * used here is a fixed synthetic value built at runtime; assertions prove that
 * no prefix, length, or newline of a secret-shaped input reaches a log
 * parameter.
 */
class ApiRoutingDebugRedactionTest {

    private static final String LOGGER_NAME = "com.example.lms.routing.ApiRoutingDebug";
    private static final String LONG_SECRET = "sk-" + "x".repeat(125);
    private static final String AUTH_HEADER = "authorization" + ":";
    private static final String BEARER_PREFIX = "bearer" + " ";

    private final List<LogRecord> records = new ArrayList<>();
    private Logger jul;
    private Level priorLevel;
    private boolean priorParent;
    private Handler capture;

    @BeforeEach
    void captureJul() {
        jul = Logger.getLogger(LOGGER_NAME);
        priorLevel = jul.getLevel();
        priorParent = jul.getUseParentHandlers();
        capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        jul.addHandler(capture);
        jul.setLevel(Level.ALL);
        jul.setUseParentHandlers(false);
    }

    @AfterEach
    void releaseJul() {
        jul.removeHandler(capture);
        jul.setLevel(priorLevel);
        jul.setUseParentHandlers(priorParent);
    }

    private Object lastParam(int index) {
        assertFalse(records.isEmpty(), "expected at least one debug log record");
        Object[] params = records.get(records.size() - 1).getParameters();
        return params == null ? null : params[index];
    }

    @Test
    void longSecretNeverExposesPrefixOrLength() {
        ApiRoutingDebug.decision("llm", LONG_SECRET, "model-a", "ep", true, "env");
        Object provider = lastParam(1);
        assertEquals("redacted", provider);
        assertFalse(String.valueOf(provider).contains(LONG_SECRET.substring(0, 24)),
                "the first 24 characters of a long secret must never be echoed");
        assertFalse(String.valueOf(provider).contains("len="),
                "key length must not be disclosed");
    }

    @Test
    void opaqueUnsignedLongValueIsNotEchoed() {
        String opaque = "eyJ" + "a".repeat(120); // JWT-shaped, no known prefix
        ApiRoutingDebug.decision("llm", "local", opaque, "ep", false, "env");
        assertEquals("redacted", lastParam(2));
    }

    @Test
    void secretPrefixesAreRedactedWithoutLength() {
        for (String secret : List.of(
                "gsk_" + "g".repeat(28),
                "tvly-" + "t".repeat(20),
                BEARER_PREFIX + "b".repeat(20),
                "basic " + "b".repeat(16),
                AUTH_HEADER + " synthetic")) {
            records.clear();
            ApiRoutingDebug.decision("llm", secret, "model", "ep", true, "env");
            assertEquals("redacted", lastParam(1), secret.substring(0, 4));
            assertFalse(String.valueOf(lastParam(1)).contains("redacted_len="));
        }
    }

    @Test
    void crlfInjectionIsBlocked() {
        ApiRoutingDebug.decision("llm", "prov\n" + AUTH_HEADER + " x", "model", "ep", true, "env");
        assertEquals("redacted", lastParam(1));
    }

    @Test
    void benignShortLabelsSurvive() {
        ApiRoutingDebug.decision("llm", "groq", "qwen/qwen3-32b", "api.groq.com", true, "GROQ_API_KEY");
        assertEquals("groq", lastParam(1));
        assertEquals("qwen/qwen3-32b", lastParam(2));
        assertEquals("api.groq.com", lastParam(3));
        assertEquals("GROQ_API_KEY", lastParam(5));
    }

    @Test
    void credentialLineUsesSameMasking() {
        ApiRoutingDebug.credential("boot", "inventory", LONG_SECRET, true, "env");
        assertEquals("redacted", lastParam(2));
    }

    @Test
    void failureLineMasksFallbackLabel() {
        ApiRoutingDebug.failure("llm", "groq", "m", "ep", 1, null, "timeout", LONG_SECRET);
        assertEquals("redacted", lastParam(7));
    }
}
