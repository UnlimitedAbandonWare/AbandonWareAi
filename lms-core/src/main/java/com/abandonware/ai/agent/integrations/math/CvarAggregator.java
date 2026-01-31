package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.CvarAggregator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.CvarAggregator
role: config
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