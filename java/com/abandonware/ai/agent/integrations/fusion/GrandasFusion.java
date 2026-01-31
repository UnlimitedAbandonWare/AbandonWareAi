package com.abandonware.ai.agent.integrations.fusion;


import java.util.*;
/**
 * Grandas fusion: WPM -> Δ-projection (placeholder) -> BodeClamp.
 */
public class GrandasFusion {
    private final com.abandonware.ai.agent.integrations.math.WpmAggregator wpm = new com.abandonware.ai.agent.integrations.math.WpmAggregator();
    private final com.abandonware.ai.agent.integrations.math.BodeClamp bode = new com.abandonware.ai.agent.integrations.math.BodeClamp();
    private final com.abandonware.ai.agent.integrations.math.MpLawNormalizer mp = new com.abandonware.ai.agent.integrations.math.MpLawNormalizer();

    public double fuse(double[] scores, double[] weights, double p, double bodeW){
        double s = wpm.aggregate(scores, weights, p);
        // Δ-projection placeholder: identity
        s = mp.normalize(s);
        return bode.clamp(s, bodeW);
    }
}