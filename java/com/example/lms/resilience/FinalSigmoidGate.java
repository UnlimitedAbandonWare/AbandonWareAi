
package com.example.lms.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;



@Component
public class FinalSigmoidGate {
    private final double threshold;

    public FinalSigmoidGate(@Value("${final.gate.pass9x.threshold:0.90}") double threshold) {
        this.threshold = threshold;
    }

    public boolean allow(double compositeScore) {
        double s = 1.0 / (1.0 + Math.exp(-12*(compositeScore-0.5))); // k=12 steepness
        return s >= threshold;
    }
}