package com.abandonware.ai.service.rag.fusion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Minimal identity calibrator with [0,1] clamp as a safety net. */
@Component
@ConditionalOnMissingBean(ScoreCalibrator.class)
public class IdentityCalibrator implements ScoreCalibrator {
    @Override
    public double normalize(double raw, String source) {
        if (Double.isNaN(raw)) return 0.0;
        double s = raw;
        if (s < 0) s = 0.0;
        if (s > 1.0) s = 1.0;
        return s;
    }
}