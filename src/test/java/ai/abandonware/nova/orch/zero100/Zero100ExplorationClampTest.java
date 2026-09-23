package ai.abandonware.nova.orch.zero100;

import ai.abandonware.nova.config.Zero100EngineProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Zero100ExplorationClampTest {

    @Test
    void explorationRateUsesConservativeUpperClampAcrossSessions() {
        Zero100EngineProperties props = new Zero100EngineProperties();
        Zero100SessionRegistry registry = new Zero100SessionRegistry(props);

        for (int i = 0; i < 24; i++) {
            Zero100SessionRegistry.Slice slice = registry.touch(
                    "stable-session-" + i,
                    "zero100 intent",
                    1L,
                    60_000L,
                    500L,
                    500L);

            assertTrue(slice.getExplorationRate() >= 0.05d);
            assertTrue(slice.getExplorationRate() <= 0.22d);
        }
    }
}
