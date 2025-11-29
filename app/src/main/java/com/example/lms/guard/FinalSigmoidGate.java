package com.example.lms.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FinalSigmoidGate {

    @Value("${gate.sigmoid.k:8.0}")
    private double k;

    @Value("${gate.sigmoid.x0:0.75}")
    private double x0;

    @Value("${gate.sigmoid.pass:0.90}")
    private double pass;

    public boolean allow(double quality){
        double s = 1.0 / (1.0 + Math.exp(-k * (quality - x0)));
        return s >= pass;
    }
}