package com.abandonware.ai.orchestrator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.orchestrator.FinalSigmoidGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.orchestrator.FinalSigmoidGate
role: config
*/
public class FinalSigmoidGate {

    @Value("${orchestrator.pass9x.k:8.0}")
    private double k;

    @Value("${orchestrator.pass9x.x0:0.72}")
    private double x0;

    @Value("${orchestrator.pass9x.threshold:0.90}")
    private double threshold;

    public boolean allow(double x) {
        double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
        return s >= threshold;
    }
}