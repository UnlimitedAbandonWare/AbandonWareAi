package com.abandonware.ai.agent.service.rag.calib;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.calib.ScaleCalibrator
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.agent.service.plan.RetrievalPlan
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.calib.ScaleCalibrator
role: config
*/
public class ScaleCalibrator implements ScoreCalibrator {
    @Override
    public List<ContextSlice> apply(List<ContextSlice> in, Object ctx, RetrievalPlan plan) {
        if (in == null || in.isEmpty()) return in;
        String method = plan.calibration().scale.method;
        DoubleSummaryStatistics stats = in.stream().mapToDouble(ContextSlice::getScore).summaryStatistics();
        double min = stats.getMin();
        double max = stats.getMax();
        double denom = (max - min) == 0.0 ? 1.0 : (max - min);
        return in.stream().map(cs -> {
            double norm = (cs.getScore() - min) / denom;
            cs.setScore(norm);
            return cs;
        }).collect(Collectors.toList());
    }
}