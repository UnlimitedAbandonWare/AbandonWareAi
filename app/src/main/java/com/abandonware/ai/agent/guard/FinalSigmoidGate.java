package com.abandonware.ai.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FinalSigmoidGate {
    private final double k;
    private final double x0;
    public FinalSigmoidGate(@Value("${gate.finalSigmoid.k:12.0}") double k,
                            @Value("${gate.finalSigmoid.x0:0.0}") double x0) {
        this.k = k; this.x0 = x0;
    }
    public boolean shouldBlock(double score) {
        double s = 1.0 / (1.0 + Math.exp(-k*(score - x0)));
        return s < 0.9; // pass threshold 0.9
    }
}
