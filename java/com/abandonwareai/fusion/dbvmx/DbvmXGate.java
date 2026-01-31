package com.abandonwareai.fusion.dbvmx;

import org.springframework.stereotype.Component;

@Component
public class DbvmXGate {
    public boolean accept(double sourceScore, double ocrConfidence){ return sourceScore>0.5 && ocrConfidence>0.6; }

}