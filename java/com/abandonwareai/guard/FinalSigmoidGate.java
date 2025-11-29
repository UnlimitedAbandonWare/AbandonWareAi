package com.abandonwareai.guard;

import org.springframework.stereotype.Component;

@Component
public class FinalSigmoidGate {
    public boolean pass(double x, double k, double x0){ double s=1.0/(1.0+Math.exp(-k*(x-x0))); return s>=0.9; }

}