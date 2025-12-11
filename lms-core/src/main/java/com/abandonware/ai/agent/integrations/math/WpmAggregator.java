package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.WpmAggregator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.WpmAggregator
role: config
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