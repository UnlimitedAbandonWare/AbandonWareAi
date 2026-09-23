package ai.abandonware.nova.orch.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceEventCanonicalizerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void invalidSequenceUsesStableReasonCodeWithoutRawValue() {
        TraceEventCanonicalizer.canonicalize(List.of(
                Map.of("seq", "ownerToken-not-a-long", "stage", "late"),
                Map.of("seq", "2", "stage", "early")),
                List.of("stage"));

        assertEquals("asLong", MDC.get("trace.canonicalizer.suppressed.stage"));
        assertEquals("invalid_number", MDC.get("trace.canonicalizer.suppressed.errorType"));
        String mdc = String.valueOf(MDC.getCopyOfContextMap());
        assertFalse(mdc.contains("ownerToken-not-a-long"));
        assertFalse(mdc.contains("NumberFormatException"));
    }

    @Test
    void nonFiniteSequenceUsesInvalidNumberFallback() {
        List<Map<String, Object>> out = TraceEventCanonicalizer.canonicalize(List.of(
                Map.of("seq", Double.POSITIVE_INFINITY, "stage", "nonfinite"),
                Map.of("seq", 2L, "stage", "finite")),
                List.of("stage"));

        assertEquals("nonfinite", out.get(0).get("stage"));
        assertEquals("asLong", MDC.get("trace.canonicalizer.suppressed.stage"));
        assertEquals("invalid_number", MDC.get("trace.canonicalizer.suppressed.errorType"));
    }

    @Test
    void asLongOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/trace/TraceEventCanonicalizer.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignore) {\n            return def;\n        }"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("traceSuppressed(\"asLong\", e);"));
        assertTrue(source.contains("MDC.put(\"trace.canonicalizer.suppressed.stage\", stage);"));
        assertFalse(source.contains("e.getMessage()"));
    }
}
