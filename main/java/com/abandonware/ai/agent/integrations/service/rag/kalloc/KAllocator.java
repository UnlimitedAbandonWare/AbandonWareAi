package com.abandonware.ai.agent.integrations.service.rag.kalloc;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Dynamic K-allocation policy and planner. */
public class KAllocator {
    public static class KPlan {
        public final int webK;
        public final int vectorK;
        public final int kgK;
        public final int poolLimit;

        public KPlan(int webK, int vectorK, int kgK, int poolLimit) {
            this.webK = webK;
            this.vectorK = vectorK;
            this.kgK = kgK;
            this.poolLimit = poolLimit;
        }

        @Override
        public String toString() {
            return "KPlan{webK=" + webK + ",vectorK=" + vectorK + ",kgK=" + kgK + ",pool=" + poolLimit + "}";
        }
    }

    public static class Input {
        public final String intent;
        public final String queryText;
        public final boolean officialSourcesOnly;
        public final double webRisk;
        public final double vectorRisk;
        public final double kgRisk;
        public final double webTailSignal;
        public final double vectorTailSignal;
        public final double kgTailSignal;

        public Input(String intent, String queryText, boolean officialSourcesOnly) {
            this(intent, queryText, officialSourcesOnly, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        public Input(
                String intent,
                String queryText,
                boolean officialSourcesOnly,
                double webRisk,
                double vectorRisk,
                double kgRisk,
                double webTailSignal,
                double vectorTailSignal,
                double kgTailSignal) {
            this.intent = intent == null ? "" : intent.toLowerCase(Locale.ROOT);
            this.queryText = queryText == null ? "" : queryText.toLowerCase(Locale.ROOT);
            this.officialSourcesOnly = officialSourcesOnly;
            this.webRisk = clamp01(webRisk);
            this.vectorRisk = clamp01(vectorRisk);
            this.kgRisk = clamp01(kgRisk);
            this.webTailSignal = clamp01(webTailSignal);
            this.vectorTailSignal = clamp01(vectorTailSignal);
            this.kgTailSignal = clamp01(kgTailSignal);
        }
    }

    public static class Settings {
        public boolean enabled = false;
        public String policy = "balanced";
        public int maxTotalK = 24;
        public int minPerSource = 2;
        public int kStep = 4;
        public double maxSourceShare = 0.65d;
        public List<String> recencyKeywords = Arrays.asList("recent", "today", "update", "release", "2026");
    }

    private final Settings settings;

    public KAllocator(Settings s) {
        this.settings = s == null ? new Settings() : s;
    }

    public KPlan decide(Input in) {
        Input safe = in == null ? new Input("", "", false) : in;
        int webK = 8;
        int vectorK = 8;
        int kgK = 8;
        String policy = settings.policy == null ? "balanced" : settings.policy;
        String text = (safe.intent + " " + safe.queryText);
        boolean recency = containsAny(text, settings.recencyKeywords);

        if ("recency_first".equalsIgnoreCase(policy) || recency) {
            webK = 15;
            vectorK = 5;
            kgK = 4;
        } else if ("kg_first".equalsIgnoreCase(policy)
                || text.contains("definition")
                || text.contains("schema")
                || text.contains("ontology")
                || text.contains("relationship")) {
            kgK = 12;
            vectorK = 8;
            webK = 4;
        } else if ("vector_first".equalsIgnoreCase(policy)) {
            vectorK = 14;
            webK = 6;
            kgK = 4;
        } else if ("cost_saver".equalsIgnoreCase(policy)) {
            webK = 4;
            vectorK = 4;
            kgK = 4;
        }

        int[] ks = { webK, vectorK, kgK };
        double[] risks = { safe.webRisk, safe.vectorRisk, safe.kgRisk };
        double[] tails = { safe.webTailSignal, safe.vectorTailSignal, safe.kgTailSignal };
        enforceMin(ks);
        applyRiskAndTail(ks, risks, tails);
        normalizeToBudget(ks);
        capSourceShare(ks);
        normalizeToBudget(ks);
        return new KPlan(ks[0], ks[1], ks[2], settings.maxTotalK);
    }

    private void applyRiskAndTail(int[] ks, double[] risks, double[] tails) {
        int step = Math.max(1, settings.kStep);
        for (int i = 0; i < ks.length; i++) {
            if (risks[i] >= 0.70d && ks[i] > settings.minPerSource) {
                int delta = Math.min(step, ks[i] - settings.minPerSource);
                ks[i] -= delta;
                ks[bestRecipient(risks, tails, i)] += delta;
            }
        }
        int bestTail = bestTailOpportunity(risks, tails);
        if (bestTail >= 0 && tails[bestTail] >= 0.70d && risks[bestTail] <= 0.35d) {
            int donor = highestRiskOrLargest(risks, bestTail, ks);
            if (donor >= 0 && ks[donor] > settings.minPerSource) {
                int delta = Math.min(step, ks[donor] - settings.minPerSource);
                ks[donor] -= delta;
                ks[bestTail] += delta;
            }
        }
    }

    private int bestRecipient(double[] risks, double[] tails, int excluded) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < risks.length; i++) {
            if (i == excluded) {
                continue;
            }
            double score = tails[i] - risks[i];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best < 0 ? 0 : best;
    }

    private int bestTailOpportunity(double[] risks, double[] tails) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tails.length; i++) {
            double score = tails[i] - risks[i];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private int highestRiskOrLargest(double[] risks, int excluded, int[] ks) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < risks.length; i++) {
            if (i == excluded || ks[i] <= settings.minPerSource) {
                continue;
            }
            double score = risks[i] + (ks[i] / Math.max(1.0d, settings.maxTotalK));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private void capSourceShare(int[] ks) {
        int total = sum(ks);
        int max = Math.max(settings.minPerSource, (int) Math.floor(total * clamp(settings.maxSourceShare, 0.34d, 1.0d)));
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < ks.length; i++) {
                if (ks[i] > max) {
                    int extra = ks[i] - max;
                    ks[i] = max;
                    distribute(ks, extra, i);
                    changed = true;
                }
            }
        } while (changed);
    }

    private void distribute(int[] ks, int extra, int excluded) {
        int idx = 0;
        while (extra > 0) {
            int target = idx % ks.length;
            if (target != excluded) {
                ks[target]++;
                extra--;
            }
            idx++;
        }
    }

    private void normalizeToBudget(int[] ks) {
        enforceMin(ks);
        int maxTotal = Math.max(settings.minPerSource * ks.length, settings.maxTotalK);
        while (sum(ks) > maxTotal) {
            int largest = largestAboveMin(ks);
            if (largest < 0) {
                break;
            }
            ks[largest]--;
        }
        while (sum(ks) < maxTotal) {
            ks[smallest(ks)]++;
        }
    }

    private void enforceMin(int[] ks) {
        for (int i = 0; i < ks.length; i++) {
            ks[i] = Math.max(ks[i], Math.max(0, settings.minPerSource));
        }
    }

    private int largestAboveMin(int[] ks) {
        int best = -1;
        for (int i = 0; i < ks.length; i++) {
            if (ks[i] > settings.minPerSource && (best < 0 || ks[i] > ks[best])) {
                best = i;
            }
        }
        return best;
    }

    private static int smallest(int[] ks) {
        int best = 0;
        for (int i = 1; i < ks.length; i++) {
            if (ks[i] < ks[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int sum(int[] ks) {
        int total = 0;
        for (int k : ks) {
            total += k;
        }
        return total;
    }

    private static boolean containsAny(String text, List<String> keys) {
        if (text == null || keys == null) {
            return false;
        }
        for (String k : keys) {
            if (k != null && !k.isBlank() && text.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
