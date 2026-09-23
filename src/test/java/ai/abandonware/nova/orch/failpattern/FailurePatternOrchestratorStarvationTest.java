package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FailurePatternOrchestratorStarvationTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void webStarvationRecordsLowCardinalityTraceCountersWithoutRawProvider() {
        FailurePatternOrchestrator orchestrator = newOrchestrator();
        String rawProvider = "private provider ownerToken=provider-secret";

        orchestrator.recordWebStarvation(rawProvider, "hybridEmptyFallback->strict_filter_starvation");
        orchestrator.recordWebStarvation("naver", "cacheOnly");
        orchestrator.recordWebStarvation("brave", "NOFILTER_SAFE");

        assertEquals("WEB_STARVATION", TraceStore.get("failpattern.web.starvation.kind"));
        assertEquals("brave", TraceStore.get("failpattern.web.starvation.provider"));
        assertEquals("nofilter_safe", TraceStore.get("failpattern.web.starvation.ladderStage"));
        assertEquals(3L, TraceStore.get("failpattern.web.starvation.count"));

        List<FailurePatternMatch> recent = orchestrator.recentMatchesSince(0L, null);
        assertEquals(3, recent.size());
        assertEquals("unknown", recent.get(0).source());
        assertEquals(FailurePatternKind.WEB_STARVATION, recent.get(2).kind());
        assertEquals("brave", recent.get(2).source());
        assertEquals("nofilter_safe", recent.get(2).key());

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawProvider), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("provider-secret"), trace);
    }

    private static FailurePatternOrchestrator newOrchestrator() {
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        props.getJsonl().setReadEnabled(false);
        props.getJsonl().setWriteEnabled(false);

        return new FailurePatternOrchestrator(
                new FailurePatternDetector(),
                new FailurePatternMetrics(null, props),
                new FailurePatternJsonlWriter(new ObjectMapper(), props),
                new FailurePatternCooldownRegistry(),
                new ObjectMapper(),
                props);
    }
}
