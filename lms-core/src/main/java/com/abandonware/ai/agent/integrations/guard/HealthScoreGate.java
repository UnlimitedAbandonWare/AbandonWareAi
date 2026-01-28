package com.abandonware.ai.agent.integrations.guard;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.guard.HealthScoreGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.guard.HealthScoreGate
role: config
*/
public class HealthScoreGate {
    private final double threshold;
    public HealthScoreGate(double threshold){ this.threshold = threshold; }
    public boolean allow(double P,double R,double C,double Y,double K){
        double x = 0.2*(P+R+C+Y+K) - 2.0; // center baseline
        double s = 1.0 / (1.0 + Math.exp(-x));
        return s >= threshold;
    }
}