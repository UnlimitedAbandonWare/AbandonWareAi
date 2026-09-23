package infra.cache;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightCacheTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void loaderFailureLeavesRedactedBreadcrumbBeforeRethrow() {
        SingleFlightCache<String, String> cache = new SingleFlightCache<>();

        assertThrows(RuntimeException.class,
                () -> cache.getOrCompute("private-cache-key", () -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals("loader", TraceStore.get("infra.singleFlight.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("infra.singleFlight.suppressed.errorType"));
        assertEquals(true, TraceStore.get("infra.singleFlight.suppressed.loader"));
        assertEquals(17, TraceStore.get("infra.singleFlight.suppressed.keyLength"));
        assertTrue(TraceStore.get("infra.singleFlight.suppressed.keyHash") instanceof String);
        assertNull(TraceStore.get("infra.singleFlight.suppressed.rawKey"));
    }
}
