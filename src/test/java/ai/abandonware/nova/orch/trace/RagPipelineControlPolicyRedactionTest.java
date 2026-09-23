package ai.abandonware.nova.orch.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineControlPolicyRedactionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void traceAnchorControlDoesNotPromoteRawSensitiveAnchorLabel() {
        String fakeSecret = "sk-" + "A".repeat(24);
        String rawAnchor = "ownertoken " + fakeSecret;
        TraceStore.put("ablation.traceAnchor.top", Map.of(
                "routeHint", "brave_mode",
                "anchorHash", rawAnchor,
                "matrixTile", 4,
                "expectedDelta", 0.80d));
        TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", 0.80d);

        Map<String, Object> control = RagPipelineControlPolicy.normalize(
                "search",
                "ok",
                Map.of(),
                Map.of(),
                Map.of());

        String rendered = String.valueOf(control);
        assertEquals("brave_mode", control.get("action"));
        assertFalse(rendered.contains(fakeSecret), rendered);
        assertFalse(rendered.toLowerCase().contains("ownertoken"), rendered);
        assertTrue(String.valueOf(control.get("anchorHash")).startsWith("hash_"), rendered);
    }

    @Test
    void traceSignalsHashCallerProvidedStageAndBreadcrumbText() {
        String rawStage = "private customer search stage";
        String rawBreadcrumb = "customer recovery breadcrumb alpha";

        RagPipelineControlPolicy.normalize(
                rawStage,
                "empty",
                Map.of("reasonCode", "zero_result", "failureClass", "zero_result"),
                Map.of("breadcrumbId", rawBreadcrumb),
                Map.of());

        Object storedStage = TraceStore.get("rag.control.last.stage");
        Object storedBreadcrumb = TraceStore.get("rag.control.last.breadcrumbId");
        String rendered = String.valueOf(TraceStore.getAll());

        assertTrue(String.valueOf(storedStage).startsWith("hash:"), rendered);
        assertTrue(String.valueOf(storedBreadcrumb).startsWith("hash:"), rendered);
        assertFalse(rendered.contains(rawStage), rendered);
        assertFalse(rendered.contains(rawBreadcrumb), rendered);
        assertFalse(rendered.contains("private customer"), rendered);
        assertFalse(rendered.contains("customer recovery"), rendered);
    }

    @Test
    void invalidTraceAnchorNumericPressureUsesStableReasonCode() {
        TraceStore.put("ablation.traceAnchor.top", Map.of(
                "routeHint", "brave_mode",
                "anchorHash", "hash:anchor",
                "routeCorrectionNeed", 0.80d,
                "expectedDelta", "not-a-double"));

        Map<String, Object> control = RagPipelineControlPolicy.normalize(
                "search",
                "ok",
                Map.of(),
                Map.of(),
                Map.of());

        assertEquals("brave_mode", control.get("action"));
        assertEquals("doubleValue", TraceStore.get("rag.control.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rag.control.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.get("rag.control.suppressed.errorType"))
                .contains("NumberFormatException"));
    }

    @Test
    void nonFiniteTraceAnchorNumericPressureUsesStableReasonCode() {
        TraceStore.put("ablation.traceAnchor.top", Map.of(
                "routeHint", "brave_mode",
                "anchorHash", "hash:anchor",
                "routeCorrectionNeed", Double.NaN,
                "expectedDelta", 0.80d));

        Map<String, Object> control = RagPipelineControlPolicy.normalize(
                "search",
                "ok",
                Map.of(),
                Map.of(),
                Map.of());

        assertEquals("brave_mode", control.get("action"));
        assertEquals("doubleValue", TraceStore.get("rag.control.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rag.control.suppressed.errorType"));
    }

    @Test
    void numericControlHelperOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/trace/RagPipelineControlPolicy.java"))
                .replace("\r\n", "\n");

        assertFalse(source.contains("catch (Exception ignore) {\n            return 0.0d;\n        }"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (NumberFormatException ignore)"));
        assertTrue(source.contains("traceSuppressed(\"applyTraceSignal\", e);"));
        assertTrue(source.contains("traceSuppressed(\"traceAnchorControl\", e);"));
        assertTrue(source.contains("traceSuppressed(\"doubleValue\", e);"));
        assertTrue(source.contains("TraceStore.put(\"rag.control.suppressed.stage\", stage);"));
        assertTrue(source.contains("traceSuppressedFallback(stage, error, traceFailure);"));
        assertFalse(source.contains("error.getMessage()"));
    }
}
