
package com.example.lms.fusion;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;




@Component
@ConditionalOnProperty(name="fusion.wpm.enabled", havingValue = "true", matchIfMissing = true)
public class GrandasFusionPipeline {

    public static class SourceScores {
        public final Map<String,Double> scores = new LinkedHashMap<>(); // key: source id
        public final Map<String,Double> weights = new LinkedHashMap<>();
        public Map<String,Object> meta = new HashMap<>();
    }

    private final IsotonicRegressionCalibrator calibrator = new IsotonicRegressionCalibrator();

    public void calibrate(double[] s, double[] t) {
        calibrator.fit(s,t);
    }

    public double fuse(SourceScores ss, double p) {
        // 1) Calibrate per-source scores
        Map<String,Double> cal = new LinkedHashMap<>();
        for (Map.Entry<String,Double> e: ss.scores.entrySet()) {
            cal.put(e.getKey(), calibrator.predict(e.getValue()));
        }
        double fused = WeightedPowerMeanFusion.fuse(cal, ss.weights, p);
        // 2) Δ-projection
        double adjusted = DeltaProjection.apply(fused, ss.meta);
        return Math.max(0.0, Math.min(1.0, adjusted));
    }
}
// Hypernova patch hint: Insert DPP diversity reranking between BiEncoder and Cross-Encoder.