package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordSelectionForceMinMustAspectTest {

    @Test
    void forceMinMustTraceCatchesUseSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/KeywordSelectionForceMinMustAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.effectiveMaxTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.seedOnEmptyOptionalUsedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.seedOnEmptyOptionalSkippedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.initialTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.seedOnEmptyOptionalFailedTrace\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"keywordSelectionForceMinMust.appliedTrace\", ignore);"));
    }
}
