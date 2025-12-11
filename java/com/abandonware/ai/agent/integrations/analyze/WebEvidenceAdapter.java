
package com.abandonware.ai.agent.integrations.analyze;

import java.util.*;
public class WebEvidenceAdapter {

    public static class ContextItem {
        public String id, title, snippet, source;
        public double score; public int rank;
    }

    public static class AliasEvidence {
        public String tileId; public String alias; public double weight; public String sourceTag;
        public AliasEvidence(String tileId, String alias, double weight, String sourceTag) {
            this.tileId = tileId; this.alias = alias; this.weight = weight; this.sourceTag = sourceTag;
        }
    }

    public List<AliasEvidence> from(List<ContextItem> fused, Map<String,Integer> domainTier) {
        List<AliasEvidence> out = new ArrayList<>();
        if (fused == null) return out;
        for (ContextItem c : fused) {
            double tier = 0.0;
            if (domainTier != null && c.source != null) {
                Integer t = domainTier.get(c.source);
                if (t != null) tier = Math.min(3, Math.max(0, t));
            }
            double hat = Math.max(0.0, Math.min(1.0, c.score));
            double w = 1.0 / (1.0 + Math.exp(-1.3 * (hat + 0.4 * tier)));
            String alias = (c.title != null ? c.title : c.id);
            out.add(new AliasEvidence("tile-" + (c.rank%9), alias, w, c.source));
        }
        return out;
    }
}
