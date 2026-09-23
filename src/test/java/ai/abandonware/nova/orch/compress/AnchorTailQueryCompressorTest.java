package ai.abandonware.nova.orch.compress;

import com.example.lms.search.TraceStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AnchorTailQueryCompressorTest {

    @Test
    void anchorTailFallbackCatchesUseRedactedSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/compress/AnchorTailQueryCompressor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"anchorTail.keepAnchorAndTail\", e);"));
        assertTrue(source.contains("traceSkipped(\"anchorTail.queryAnalysis\", e);"));
        assertTrue(source.contains("traceSkipped(\"anchorTail.pickAnchor\", ignored);"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertFalse(source.contains("failure.getMessage()"));
        assertFalse(source.contains("failure.toString()"));
    }

    @Test
    void anchorTailSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        TraceStore.clear();
        String secret = com.example.lms.test.SecretFixtures.openAiKey();
        Method traceSkipped = AnchorTailQueryCompressor.class.getDeclaredMethod(
                "traceSkipped", String.class, Throwable.class);
        traceSkipped.setAccessible(true);

        traceSkipped.invoke(null, "anchorTail.pickAnchor " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("anchorTail.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("anchorTail.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("anchorTail.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("anchorTail.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(secret));
        TraceStore.clear();
    }
}
