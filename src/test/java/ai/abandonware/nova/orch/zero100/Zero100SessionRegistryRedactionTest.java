package ai.abandonware.nova.orch.zero100;

import ai.abandonware.nova.config.Zero100EngineProperties;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Zero100SessionRegistryRedactionTest {

    @Test
    void touchReturnsRedactedSessionLabelForSensitiveInputAndKeepsLookupStable() {
        Zero100EngineProperties props = new Zero100EngineProperties();
        Zero100SessionRegistry registry = new Zero100SessionRegistry(props);
        String rawSessionId = "sid-token=" + "sk-" + "A".repeat(24) + "-ownerToken=private-session";

        Zero100SessionRegistry.Slice slice = registry.touch(
                rawSessionId,
                "zero100 intent",
                1L,
                1_000L,
                500L,
                500L);

        assertTrue(slice.getSessionId().startsWith("hash:"));
        assertFalse(slice.getSessionId().contains("sk-"));
        assertFalse(slice.getSessionId().contains("ownerToken"));
        assertTrue(registry.isActive(rawSessionId));
        assertEquals("zero100 intent", registry.mpIntentOrNull(rawSessionId));
    }

    @Test
    void explorationRateStaysBoundedAndDeterministicPerSessionSlice() {
        Zero100EngineProperties props = new Zero100EngineProperties();
        Zero100SessionRegistry registry = new Zero100SessionRegistry(props);

        Zero100SessionRegistry.Slice first = registry.touch(
                "stable-session",
                "zero100 intent",
                1L,
                60_000L,
                500L,
                500L);
        Zero100SessionRegistry.Slice second = registry.touch(
                "stable-session",
                "zero100 intent",
                1L,
                60_000L,
                500L,
                500L);

        assertEquals(first.getExplorationRate(), second.getExplorationRate());
        assertTrue(first.getExplorationRate() >= 0.05d);
        assertTrue(first.getExplorationRate() <= 0.40d);
    }

    @Test
    void zero100AspectPublishesExplorationTraceKey() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"zero100.explorationRate\", slice.getExplorationRate());"));
    }
}
