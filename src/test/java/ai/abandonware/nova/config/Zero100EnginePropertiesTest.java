package ai.abandonware.nova.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Zero100EnginePropertiesTest {

    @Test
    void timeBudgetsClampToPositiveValues() {
        Zero100EngineProperties props = new Zero100EngineProperties();

        props.setMaxMinutes(0);
        props.setSliceMs(-10);
        props.setSessionIdleTtlMs(0);
        props.setWebCallTimeboxMs(-1);
        props.setWebCallTimeboxMsWhenRateLimited(0);
        props.setBackoffHardCapMs(-50);

        assertEquals(1L, props.getMaxMinutes());
        assertEquals(1L, props.getSliceMs());
        assertEquals(1L, props.getSessionIdleTtlMs());
        assertEquals(1L, props.getWebCallTimeboxMs());
        assertEquals(1L, props.getWebCallTimeboxMsWhenRateLimited());
        assertEquals(1L, props.getBackoffHardCapMs());
    }

    @Test
    void cacheProbeCandidateCountsClampToSafeBounds() {
        Zero100EngineProperties props = new Zero100EngineProperties();

        props.setCacheProbeMaxCandidates(0);
        props.setCacheProbeExtraAnchorCandidates(-1);

        assertEquals(1, props.getCacheProbeMaxCandidates());
        assertEquals(0, props.getCacheProbeExtraAnchorCandidates());
    }
}
