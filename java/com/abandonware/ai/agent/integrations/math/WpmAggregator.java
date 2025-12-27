package com.abandonware.ai.agent.integrations.math;


/**
 * Weighted Power Mean (placeholder; replace with legacy exact definition and constants).
 */
public class WpmAggregator {
    public double aggregate(double[] xs, double[] ws, double p){
        if (xs==null||ws==null||xs.length==0||xs.length!=ws.length) return 0.0;
        double num=0.0, den=0.0;
        for (int i=0;i<xs.length;i++){
            num += ws[i]*Math.pow(Math.max(xs[i], 0), p);
            den += Math.max(ws[i], 1e-9);
        }
        return Math.pow(num/den, 1.0/p);
    }
}