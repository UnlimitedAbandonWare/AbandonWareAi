package com.abandonware.ai.service.rag.fusion;

import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit Marchenko-Pastur style clamp-as-calibrator.
 * Works as a defensive normalizer on heterogeneous scores.
 */
@Component
@Primary
@ConditionalOnProperty(value="fusion.mplaw.enabled", havingValue="true", matchIfMissing=false)
public class MpLawCalibrator implements ScoreCalibrator {

    @Override
    public double normalize(double raw, String source) {
        if (Double.isNaN(raw)) return 0.0;
        double s = raw;
        if (s < 0) s = 0.0;
        // conservative lambda+ soft cap (~1.5)
        if (s > 1.5) s = 1.5;
        // map to [0,1]
        return s / 1.5;
    }
}