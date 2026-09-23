package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryDegradedAspectTest {

    @Test
    void memoryDegradedFailSoftCatchesUseSuppressionHelper() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/MemoryDegradedAspect.java"));

        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"memoryDegraded.pendingEvent\", ignore);"));
        assertTrue(source.contains("WebFailSoftTraceSuppressions.trace(\"memoryDegraded.sha256\", e);"));
    }
}
