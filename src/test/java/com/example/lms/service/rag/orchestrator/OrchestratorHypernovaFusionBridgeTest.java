package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.properties.NovaNextProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class OrchestratorHypernovaFusionBridgeTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void passesDocAndRrfScoresAsSeparateScaleSignalsToHypernova() {
        UnifiedRagOrchestrator.Doc mixedScale = doc("mixed", 42.0d, "WEB");
        UnifiedRagOrchestrator.Doc calibrated = doc("calibrated", 0.70d, "VECTOR");
        Map<UnifiedRagOrchestrator.Doc, Double> rrfScores = new LinkedHashMap<>();
        rrfScores.put(mixedScale, 0.08d);
        rrfScores.put(calibrated, 0.04d);
        CapturingNovaNextFusionService service = new CapturingNovaNextFusionService();

        OrchestratorHypernovaFusionBridge.apply(List.of(mixedScale, calibrated), rrfScores, service);

        NovaNextFusionService.ScoredResult first = service.captured.get(0);
        assertEquals(0.08d, first.getBaseScore(), 1.0e-9d);
        assertEquals(List.of(42.0d, 0.08d), first.getSourceScores());
        assertEquals(2, first.getSourceCount());
        assertEquals(Boolean.TRUE, TraceStore.get("rag.orchestrator.hypernova.applied"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void reorderedScoresStayAttachedToTheOriginalDocBySyntheticId() {
        UnifiedRagOrchestrator.Doc a = doc("A", 0.20d, "WEB");
        UnifiedRagOrchestrator.Doc b = doc("B", 0.90d, "VECTOR");
        UnifiedRagOrchestrator.Doc c = doc("C", 0.50d, "KG");
        ReorderingNovaNextFusionService service = new ReorderingNovaNextFusionService();

        List<UnifiedRagOrchestrator.Doc> out = OrchestratorHypernovaFusionBridge.apply(
                List.of(a, b, c),
                Map.of(a, 0.01d, b, 0.02d, c, 0.03d),
                service);

        assertEquals(List.of("B", "C", "A"), out.stream().map(doc -> doc.id).toList());
        assertSame(b, out.get(0));
        assertSame(c, out.get(1));
        assertSame(a, out.get(2));
        assertEquals(0.90d, b.score, 1.0e-9d);
        assertEquals(0.50d, c.score, 1.0e-9d);
        assertEquals(0.20d, a.score, 1.0e-9d);
    }

    private static UnifiedRagOrchestrator.Doc doc(String id, double score, String source) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = id;
        doc.snippet = "safe snippet ownerToken=secret";
        doc.source = source;
        doc.score = score;
        doc.rank = 1;
        return doc;
    }

    private static final class CapturingNovaNextFusionService extends NovaNextFusionService {
        private List<ScoredResult> captured = List.of();

        private CapturingNovaNextFusionService() {
            super(new NovaNextProperties());
        }

        @Override
        public List<ScoredResult> fuse(List<ScoredResult> in) {
            captured = new ArrayList<>(in);
            return in;
        }
    }

    private static final class ReorderingNovaNextFusionService extends NovaNextFusionService {
        private ReorderingNovaNextFusionService() {
            super(new NovaNextProperties());
        }

        @Override
        public List<ScoredResult> fuse(List<ScoredResult> in) {
            for (ScoredResult result : in) {
                if ("orch-fusion-0".equals(result.getId())) result.setAdjustedScore(0.20d);
                if ("orch-fusion-1".equals(result.getId())) result.setAdjustedScore(0.90d);
                if ("orch-fusion-2".equals(result.getId())) result.setAdjustedScore(0.50d);
            }
            return List.of(in.get(1), in.get(2), in.get(0));
        }
    }
}
