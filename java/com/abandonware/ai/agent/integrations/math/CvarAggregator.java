package com.abandonware.ai.agent.integrations.math;


/**
 * CVaR aggregator @alpha (placeholder).
 */
public class CvarAggregator {
    public double aggregate(double[] xs, double alpha){
        if (xs==null||xs.length==0) return 0.0;
        double[] s = xs.clone();
        java.util.Arrays.sort(s);
        int k = (int)Math.floor((1.0 - alpha) * s.length);
        k = Math.max(1, Math.min(s.length, k));
        double sum=0.0;
        for (int i=s.length-k;i<s.length;i++) sum+=s[i];
        return sum / k;
    }
}