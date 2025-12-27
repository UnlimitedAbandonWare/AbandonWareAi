package com.abandonware.ai.agent.integrations.service.rag.kalloc;

import java.util.*;



/** Dynamic K-Allocation policy and planner.
 *  Toggle via application.yml: retrieval.kalloc.enabled
 */
public class KAllocator {
    public static class KPlan {
        public final int webK, vectorK, kgK, poolLimit;
        public KPlan(int webK, int vectorK, int kgK, int poolLimit) {
            this.webK = webK; this.vectorK = vectorK; this.kgK = kgK; this.poolLimit = poolLimit;
        }
        @Override public String toString() { return "KPlan{webK="+webK+",vectorK="+vectorK+",kgK="+kgK+",pool="+poolLimit+"}"; }
    }
    public static class Input {
        public final String intent;
        public final String queryText;
        public final boolean officialSourcesOnly;
        public Input(String intent, String queryText, boolean officialSourcesOnly) {
            this.intent = intent==null?"" : intent.toLowerCase(Locale.ROOT);
            this.queryText = queryText==null?"" : queryText.toLowerCase(Locale.ROOT);
            this.officialSourcesOnly = officialSourcesOnly;
        }
    }
    public static class Settings {
        public boolean enabled = false;
        public String policy = "balanced";
        public int maxTotalK = 24;
        public int minPerSource = 2;
        public int kStep = 4;
        public List<String> recencyKeywords = Arrays.asList("최근","오늘","업데이트","발표","발매","release");
    }
    private final Settings settings;
    public KAllocator(Settings s) { this.settings = s==null? new Settings() : s; }

    public KPlan decide(Input in) {
        int webK=8, vectorK=8, kgK=8;
        String p = settings.policy;
        String text = (in.intent + " " + in.queryText);
        boolean recency = containsAny(text, settings.recencyKeywords);
        if ("recency_first".equalsIgnoreCase(p) || recency) { webK = 15; vectorK = 5; kgK = 4; }
        else if ("kg_first".equalsIgnoreCase(p) || text.contains("정의") || text.contains("스키마") || text.contains("관계")) { kgK = 12; vectorK = 8; webK = 4; }
        else if ("vector_first".equalsIgnoreCase(p)) { vectorK = 14; webK = 6; kgK = 4; }
        else if ("cost_saver".equalsIgnoreCase(p)) { webK = vectorK = kgK = 4; }
        // Enforce min and pool limit
        webK = Math.max(webK, settings.minPerSource);
        vectorK = Math.max(vectorK, settings.minPerSource);
        kgK = Math.max(kgK, settings.minPerSource);
        int total = webK + vectorK + kgK;
        if (total > settings.maxTotalK) {
            // normalize down proportionally
            double f = settings.maxTotalK / (double) total;
            webK = Math.max(settings.minPerSource, (int)Math.round(webK * f));
            vectorK = Math.max(settings.minPerSource, (int)Math.round(vectorK * f));
            kgK = Math.max(settings.minPerSource, (int)Math.round(kgK * f));
        }
        return new KPlan(webK, vectorK, kgK, settings.maxTotalK);
    }
    private boolean containsAny(String text, List<String> keys) {
        for (String k : keys) if (text.contains(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}