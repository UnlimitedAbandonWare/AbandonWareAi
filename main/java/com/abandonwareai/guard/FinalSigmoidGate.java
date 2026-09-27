package com.abandonwareai.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FinalSigmoidGate {
    @Value("${gate.finalSigmoid.threshold:0.70}")
    private double threshold;

    public boolean pass(double x, double k, double x0){ double s=1.0/(1.0+Math.exp(-k*(x-x0))); return s>=threshold; }

}
