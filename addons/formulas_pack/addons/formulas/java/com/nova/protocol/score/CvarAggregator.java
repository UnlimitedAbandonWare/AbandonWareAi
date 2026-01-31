package com.nova.protocol.score;

import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.score.CvarAggregator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.score.CvarAggregator
role: config
*/
public class CvarAggregator {

    public double cvar(List<Double> scores, double alpha) {
        if (scores == null || scores.isEmpty()) return 0.0;
        var sorted = scores.stream().sorted((a,b)->Double.compare(b,a)).toList();
        int k = Math.max(1, (int)Math.ceil(sorted.size() * alpha));
        double sum = 0.0;
        for (int i = 0; i < k; i++) sum += sorted.get(i);
        return sum / k;
    }

    /** Blend CVaR with a base score using a mixing weight. */
    public double fuse(double baseScore, List<Double> scores, double alpha, double mix) {
        double tail = cvar(scores, alpha);
        return (1.0 - mix) * baseScore + mix * tail;
    }
}