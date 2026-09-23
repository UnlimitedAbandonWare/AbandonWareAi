package ai.abandonware.nova.orch.failpattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FailurePatternInfrastructureTraceTest {

    @Test
    void infrastructureFallbackCatchesUseRedactedSuppressionBreadcrumbs() throws Exception {
        String aspect = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/aop/FailurePatternCooldownDiagnosticsAspect.java"));
        String appender = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/log/FailurePatternLogAppender.java"));
        String installer = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/log/FailurePatternLogAppenderInstaller.java"));
        String helper = Files.readString(Path.of(
                "main/java/ai/abandonware/nova/orch/failpattern/FailurePatternTrace.java"));

        assertTrue(aspect.contains("FailurePatternTrace.traceSkipped(\"failurePattern.cooldownDiagnostics\", ignored);"));
        assertTrue(appender.contains("FailurePatternTrace.traceSkipped(\"failurePattern.logAppender\", ignored);"));
        assertTrue(installer.contains("FailurePatternTrace.traceSkipped(\"failurePattern.logAppenderInstall\", ignored);"));
        assertTrue(helper.contains("public final class FailurePatternTrace"));
        assertTrue(helper.contains("public static void traceSkipped(String stage, Throwable failure)"));
        assertFalse(helper.contains("failure.getMessage()"));
        assertFalse(helper.contains("failure.toString()"));
    }
}
