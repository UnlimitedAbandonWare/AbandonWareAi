package com.abandonware.ai.agent.service.rag.fusion;

import org.springframework.stereotype.Service;
import com.abandonware.ai.service.rag.model.ContextSlice;
import java.util.List;
import java.util.stream.Collectors;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import com.abandonware.ai.agent.service.rag.calib.ScaleCalibrator;
import com.abandonware.ai.service.rag.fusion.WeightedRRF;
import com.abandonware.ai.service.rag.fusion.ScoreCalibrator;
import com.abandonware.ai.agent.service.rag.calib.RecencyBoostCalibrator;
import com.abandonware.ai.agent.service.rag.calib.AuthorityBoostCalibrator;

/**
 * Wrap existing fusion (Weighted-RRF) with calibrators.
 * Replace wiring in your chain to call this 'fuse' method.
 */
@Service
public class FusionService {

    private final com.abandonware.ai.service.rag.fusion.WeightedRRF rrf; // expect existing bean
    private final ScoreCalibrator scoreCalibrator;
    private final ScaleCalibrator scaleCalibrator;
    private final RecencyBoostCalibrator recencyCalibrator;
    private final AuthorityBoostCalibrator authorityCalibrator;

    public FusionService(WeightedRRF rrf, ScoreCalibrator scb, ScaleCalibrator sc, RecencyBoostCalibrator rc, AuthorityBoostCalibrator ac) {
        this.rrf = rrf;
        this.scoreCalibrator = scb;
        this.scaleCalibrator = sc;
        this.recencyCalibrator = rc;
        this.authorityCalibrator = ac;
    }

    public List<ContextSlice> fuse(List<List<ContextSlice>> buckets, Object ctx, RetrievalPlan plan) {
        List<List<ContextSlice>> scaled = buckets.stream()
                .map(list -> scaleCalibrator.apply(list, ctx, plan))
                .collect(Collectors.toList());
        java.util.Map<String, ContextSlice> fusedMap = rrf.fuse(
                scaled,
                (plan != null && plan.rrf() != null) ? plan.rrf().k : 60,
                (plan != null && plan.rrf() != null && plan.rrf().weight != null) ? plan.rrf().weight : java.util.Map.of(),
                scoreCalibrator,
                true);
        List<ContextSlice> fused = new java.util.ArrayList<>(fusedMap.values());
        fused = recencyCalibrator.apply(fused, ctx, plan);
        fused = authorityCalibrator.apply(fused, ctx, plan);
        // re-rank with rank fields
        int i=1; for (ContextSlice cs : fused) { cs.setRank(i++); }
        return fused;
    }
}