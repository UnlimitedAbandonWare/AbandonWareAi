
    package com.abandonware.ai.agent.orchestrator;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.FinalSigmoidGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.FinalSigmoidGate
role: config
*/
public class FinalSigmoidGate {
        private final double k, x0, threshold;
        public FinalSigmoidGate(double k, double x0, double threshold) { this.k=k; this.x0=x0; this.threshold=threshold; }
        public boolean allow(double x) {
            double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
            return s >= threshold;
        }
    }
    