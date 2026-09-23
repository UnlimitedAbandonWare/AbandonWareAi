package ai.abandonware.nova.orch.aop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCallTraceAspectTest {

    @Test
    void llmCallTraceAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmCallTraceAspect.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "LLM call trace fail-soft paths need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void llmCallTraceHelperCatchesUseSuppressionBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/LlmCallTraceAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("logSuppressed(\"config.get\", ignore);"));
        assertTrue(source.contains("logSuppressed(\"hostOf\", ignored);"));
    }
}
