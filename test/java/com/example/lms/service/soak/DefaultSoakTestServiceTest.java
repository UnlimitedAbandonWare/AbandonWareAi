package com.example.lms.service.soak;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefaultSoakTestServiceTest {
    @Test void aggregates_metrics() {
        SoakQueryProvider provider = topic -> List.of("q1", "q2");
        SearchOrchestrator orch = (q,k) -> List.of(sr(0.9,true), sr(0.7,false), sr(0.5,true));
        SoakTestService svc = new DefaultSoakTestService(provider, orch);
        SoakReport r = svc.run(3,"all");
        assertEquals(2, r.getRuns());
        assertTrue(r.getMetrics().nDCG10 > 0);
        assertTrue(r.getMetrics().evidenceRate >= 0.5);
    }
    private static SearchOrchestrator.SearchResult sr(double s, boolean ev){
        var r = new SearchOrchestrator.SearchResult();
        r.relScore = s; r.supportedByEvidence = ev; return r;
    }
}