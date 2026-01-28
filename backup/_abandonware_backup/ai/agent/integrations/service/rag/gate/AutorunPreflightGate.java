package com.abandonware.ai.agent.integrations.service.rag.gate;

import java.util.*;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.rag.gate.AutorunPreflightGate
role: config
*/
public class AutorunPreflightGate {
    public static class ScoredDoc {
        public final String source;
        public final double score;
        public final Map<String,Object> meta;
        public ScoredDoc(String source, double score, Map<String,Object> meta) {
            this.source = source;
            this.score = score;
            this.meta = meta==null ? new HashMap<>() : meta;
        }
    }
    public static class PreflightContext {
        public final List<ScoredDoc> fused;
        public final Map<String,Object> attrs;
        public PreflightContext(List<ScoredDoc> fused, Map<String,Object> attrs) {
            this.fused = fused==null ? Collections.emptyList() : fused;
            this.attrs = attrs==null ? new HashMap<>() : attrs;
        }
    }
    public static class PreflightResult {
        public final boolean pass;
        public final String reason;
        public final Map<String,Object> metrics;
        public PreflightResult(boolean pass, String reason, Map<String,Object> metrics) {
            this.pass = pass;
            this.reason = reason;
            this.metrics = metrics==null ? new HashMap<>() : metrics;
        }
    }
    public static class Settings {
        public boolean enabled = false;
        public int minEvidence = 4;
        public double minAuthorityRatio = 0.6;
        public double rdiThreshold = -1; // disabled
    }

    private final Settings settings;
    public AutorunPreflightGate(Settings settings) {
        this.settings = settings==null ? new Settings() : settings;
    }

    public PreflightResult evaluate(PreflightContext ctx) {
        Map<String,Object> m = new HashMap<>();
        m.put("fusedCount", ctx.fused.size());
        int evidence = ctx.fused.size();
        if (evidence < settings.minEvidence) {
            m.put("fail", "evidence_lt_min");
            return new PreflightResult(false, "Not enough evidence: " + evidence, m);
        }
        // Authority ratio: count docs where meta.tier in [gov, edu, scholarly, news_high]
        int auth = 0;
        for (ScoredDoc d : ctx.fused) {
            Object tier = d.meta.get("tier");
            if (tier != null) {
                String t = String.valueOf(tier).toLowerCase(Locale.ROOT);
                if (t.contains("gov") || t.contains("edu") || t.contains("scholar") || t.contains("news_high")) auth++;
            }
        }
        double ratio = evidence == 0 ? 0.0 : (auth * 1.0 / evidence);
        m.put("authorityRatio", ratio);
        if (ratio < settings.minAuthorityRatio) {
            m.put("fail", "authority_ratio_lt_min");
            return new PreflightResult(false, "Authority ratio too low: " + ratio, m);
        }
        // Risk score (stub)
        double rdi = 0.0;
        m.put("rdi", rdi);
        if (settings.rdiThreshold >= 0 && rdi > settings.rdiThreshold) {
            m.put("fail", "rdi_gt_threshold");
            return new PreflightResult(false, "Risk too high: " + rdi, m);
        }
        m.put("pass", true);
        return new PreflightResult(true, "ok", m);
    }

    /** Revalidate after a degrade step (optional). */
    public PreflightResult revalidate(PreflightContext ctx) {
        return evaluate(ctx);
    }
}