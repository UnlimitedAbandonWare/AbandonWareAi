
    package com.abandonware.ai.agent.orchestrator;
    public class FinalSigmoidGate {
        private final double k, x0, threshold;
        public FinalSigmoidGate(double k, double x0, double threshold) { this.k=k; this.x0=x0; this.threshold=threshold; }
        public boolean allow(double x) {
            double s = 1.0 / (1.0 + Math.exp(-k * (x - x0)));
            return s >= threshold;
        }
    }
    