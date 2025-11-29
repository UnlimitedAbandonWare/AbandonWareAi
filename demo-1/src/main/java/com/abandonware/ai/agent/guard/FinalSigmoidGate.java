package com.abandonware.ai.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gate.finalSigmoid.enabled", havingValue = "true", matchIfMissing = true)
public class FinalSigmoidGate {
    private final double k;
    private final double x0;
    public FinalSigmoidGate(@Value("{gate.finalSigmoid.k:12.0}") double k,
                            @Value("{gate.finalSigmoid.x0:0.0}") double x0) {
        this.k = k; this.x0 = x0;
    }
    public boolean pass(double s) {
        double y = 1.0 / (1.0 + Math.exp(-k * (s - x0)));
        return y >= 0.5;
    }
}
