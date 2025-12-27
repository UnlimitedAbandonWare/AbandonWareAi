package com.abandonware.ai.service.rag.fusion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.IdentityCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.IdentityCalibrator
role: config
*/
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