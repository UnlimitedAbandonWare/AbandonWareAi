package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultMaskIrregularityCapAspectTest {

    @Test
    void failSoftFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/aop/FaultMaskIrregularityCapAspect.java"));

        assertTrue(source.contains("traceSuppressed(\"stage.args\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"guardContext.read\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"irregularity.cap\", ignore);"));
        assertTrue(source.contains("[nova][fault-mask-irregularity-cap] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }
}
