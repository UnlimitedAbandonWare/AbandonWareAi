package com.example.guard;

import org.springframework.stereotype.Component;
import java.util.Set;



@Component
public class RiskScorer {
    public record Features(String domain, String text, boolean stale, double novelty, double authorityTier) { }
    public record RiskProps(double alpha, Set<String> redFlags, double stalePenalty, double lowAuthorityPenalty) { }

    /** Returns RDI in [0,1] (higher = more risky) */
    public double score(Features f, RiskProps p){
        double r = 0.0;
        if (f == null) return 1.0;
        if (p.redFlags() != null) {
            String txt = f.text() == null ? "" : f.text().toLowerCase();
            for (String flag: p.redFlags()) {
                if (txt.contains(flag.toLowerCase())) { r += 0.4; break; }
            }
        }
        if (f.stale()) r += p.stalePenalty();
        if (f.authorityTier() < 0.4) r += p.lowAuthorityPenalty();
        if (f.novelty() > 0.85 && f.authorityTier() < 0.5) r += 0.2;
        return Math.min(1.0, Math.max(0.0, r));
    }
}