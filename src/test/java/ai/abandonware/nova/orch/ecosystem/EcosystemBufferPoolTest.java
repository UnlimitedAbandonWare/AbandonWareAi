package ai.abandonware.nova.orch.ecosystem;

import com.example.lms.search.TraceStore;
import ai.abandonware.nova.orch.web.WebSnippet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EcosystemBufferPoolTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void recirculateReturnsBoundedSlidingWindowWithoutMutatingPool() {
        EcosystemBufferPool pool = new EcosystemBufferPool(3, 300_000L, 2);
        pool.charge("rid-test", List.of(
                WebSnippet.parse("one https://docs.example.com/one"),
                WebSnippet.parse("two https://docs.example.com/two"),
                WebSnippet.parse("three https://docs.example.com/three"),
                WebSnippet.parse("four https://docs.example.com/four")));

        List<WebSnippet> first = pool.recirculate(2);
        List<WebSnippet> second = pool.recirculate(5);

        assertEquals(3, pool.poolSize());
        assertEquals(2, first.size());
        assertEquals("https://docs.example.com/two", first.get(0).url());
        assertEquals(3, second.size());
        assertEquals(5L, pool.recycledCount());
        assertEquals(2, pool.defaultRecirculateMax());
    }

    @Test
    void recirculationScanPublishesCanonicalPoolSafeEmptyTraceKey() {
        EcosystemBufferPool pool = new EcosystemBufferPool(3, 300_000L, 2);
        pool.charge("rid-test", List.of(WebSnippet.parse("low trust https://namu.wiki/w/runtime")));

        EcosystemBufferPool.Recirculation scan = pool.recirculateSafe(2, snippet -> true);
        pool.recordRecirculationScan(scan);

        assertEquals(Boolean.TRUE, TraceStore.get("poolSafeEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("starvationFallback.poolSafeEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty"));
    }

    @Test
    void recirculationSelectionPublishesCanonicalPoolSafeEmptyTraceKey() {
        EcosystemBufferPool pool = new EcosystemBufferPool(3, 300_000L, 2);

        pool.recordRecirculationSelection(1);
        assertEquals(Boolean.TRUE, TraceStore.get("starvationFallback.used"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.failsoft.starvationFallback.used"));
        assertEquals(1, TraceStore.get("starvationFallback.added"));
        assertEquals(1, TraceStore.get("web.failsoft.starvationFallback.added"));
        assertEquals(Boolean.FALSE, TraceStore.get("poolSafeEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("starvationFallback.poolSafeEmpty"));

        pool.recordRecirculationSelection(0);
        assertEquals(Boolean.TRUE, TraceStore.get("poolSafeEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("starvationFallback.poolSafeEmpty"));
    }
}
