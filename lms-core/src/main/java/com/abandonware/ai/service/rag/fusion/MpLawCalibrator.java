package com.abandonware.ai.service.rag.fusion;

import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.MpLawCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.MpLawCalibrator
role: config
*/
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