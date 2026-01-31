/**
//* [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
//* Module: Unknown
//* Role: class
//* Thread-Safety: appears stateless.
//*/
/* agent-hint:
id: Unknown
role: class
//*/
package com.abandonware.ai.addons.flow;


public record FlowHealthScore(
        double pPlan, double pRetrieve, double pCriticize, double pSynthesize, double pDeliver,
        double safeScore
) {
    public boolean below(double threshold) { return safeScore < threshold; }
}