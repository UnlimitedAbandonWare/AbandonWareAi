package ai.abandonware.nova.orch.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class RollingSummaryAspectRedactionContractTest {

    @Test
    void rollingSummaryAspectLogsDoNotUseRawThrowableMessagesOrSessionIds() throws Exception {
        String chunkSource = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java"));
        String historySource = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/RollingSummaryHistoryAspect.java"));
        String source = chunkSource + "\n" + historySource;

        assertFalse(source.contains("sessionId, e.toString()"));
        assertFalse(source.contains("createdSessionId, e.toString()"));
        assertFalse(source.contains("e.toString());"));

        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(sessionId))"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void chunkRollingSummaryAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "chunk rolling summary fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void chunkRollingSummaryMetaSerializationFallbackLeavesRedactedBreadcrumb() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/ChunkRollingSummaryAspect.java"));
        int serializerCall = source.indexOf("objectMapper.writeValueAsString(meta)");

        assertTrue(serializerCall >= 0, "meta serialization call should remain visible");
        String window = source.substring(serializerCall, Math.min(source.length(), serializerCall + 420));
        assertTrue(window.contains("RSUM meta serialization fallback (sessionHash={} errorHash={} errorLength={})"));
        assertTrue(window.contains("SafeRedactor.hashValue(String.valueOf(sessionId))"));
        assertTrue(window.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertFalse(window.contains("String.valueOf(meta)"));
    }
}
