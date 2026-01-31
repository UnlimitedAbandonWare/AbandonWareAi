package com.abandonwareai.fusion;

import org.springframework.stereotype.Component;

@Component
public class DeltaProjector {
    public double applyDelta(double base, double delta){ return base + delta; }

}