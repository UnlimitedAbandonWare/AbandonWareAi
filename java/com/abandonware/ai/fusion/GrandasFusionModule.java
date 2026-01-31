package com.abandonware.ai.fusion;

import java.util.*;

/** GrandasFusionModule (GRA-NΔ-AS) -
 *  Combines Weighted Power Mean of scores + RRF ranks and applies delta projection weight.
 */
public class GrandasFusionModule {
    public static class Signal {
        public String id; public double score; public int rank; public String source;
        public Signal(String id, double score, int rank, String source) {
            this.id=id; this.score=score; this.rank=rank; this.source=source;
        }
    }
    public List<Signal> fuse(List<Signal> signals, double wPower, double wRrf, double delta) {
        // RRF: 1/(k + rank), k=60 default
        final double k = 60.0;
        Map<String, double[]> agg = new HashMap<>();
        for (Signal s : signals) {
            double wpm = Math.pow(Math.max(1e-9, s.score), wPower);
            double rrf = 1.0 / (k + Math.max(1, s.rank));
            double fused = 0.5*wpm + 0.5*wRrf*rrf;
            double adj = fused * (1.0 + delta); // delta projection
            agg.computeIfAbsent(s.id, k2 -> new double[]{0.0})[0] += adj;
        }
        List<Signal> out = new ArrayList<>();
        for (Signal s : signals) {
            double v = agg.get(s.id)[0];
            out.add(new Signal(s.id, v, s.rank, s.source));
        }
        out.sort((a,b)->Double.compare(b.score, a.score));
        return out;
    }
}