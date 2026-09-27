// src/main/java/com/example/lms/trace/HotspotHeuristics.java
package com.example.lms.trace;

import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.verification.EvidenceSnapshot;
import java.util.*;
import java.util.stream.Collectors;




final class HotspotHeuristics {
    private HotspotHeuristics() {}

    static Map<String, Object> fromRouteSignal(RouteSignal s) {
        Map<String, Object> m = new LinkedHashMap<>();
        double demand = demandScore(s);
        double riskHint = 1.0 - clamp(s.theta(), 0.0, 1.0);
        double verbosity = (s.verbosity() != null && s.verbosity().name().equalsIgnoreCase("VERBOSE")) ? 0.12 : 0.0;
        double suspicion = sigmoid(demand + riskHint + verbosity - 0.68);

        m.put("demandScore", round4(demand));
        m.put("suspicionScore", round4(suspicion));
        m.put("flags", computeFlags(demand, suspicion, s));
        m.put("tokenBucket", tokenBucket(s.maxTokens()));

        return m;
    }

    static Map<String, Object> fromCandidates(List<String> candidates, String selectedId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty()) {
            m.put("entropy", 0.0);
            m.put("gini", 0.0);
            m.put("top1Gap", 0.0);
            m.put("bins", Collections.emptyMap());
            m.put("flags", List.of("no_candidates"));
            return m;
        }
        double[] weights = pseudoWeights(candidates);
        double sum = Arrays.stream(weights).sum();
        double[] p = Arrays.stream(weights).map(w -> w / (sum <= 0 ? 1.0 : sum)).toArray();

        double entropy = 0.0;
        for (double pi : p) {
            if (pi > 0) entropy += -pi * (Math.log(pi) / Math.log(2)); // log2
        }
        double gini = gini(p);
        double[] sorted = Arrays.copyOf(p, p.length);
        Arrays.sort(sorted);
        double top1 = sorted[sorted.length - 1];
        double top2 = sorted.length >= 2 ? sorted[sorted.length - 2] : 0.0;
        double top1Gap = top1 - top2;
        Map<String, Integer> bins = histogram(p);

        List<String> flags = new ArrayList<>();
        if (top1Gap >= 0.65) flags.add("sharp_routing");
        if (gini >= 0.5) flags.add("dominant_expert");
        if (entropy <= 0.7) flags.add("low_entropy");
        if (selectedId != null) {
            // selectedId가 상위 1 후보인지 여부
            int idxTop1 = indexOf(p, top1);
            int idxSel = indexOf(candidates, selectedId);
            if (idxTop1 != -1 && idxSel != -1 && idxTop1 != idxSel) flags.add("non_top1_selected");
        }

        m.put("entropy", round4(entropy));
        m.put("gini", round4(gini));
        m.put("top1Gap", round4(top1Gap));
        m.put("bins", bins);
        m.put("flags", flags);
        return m;
    }

    static Map<String, Object> fromEvidence(DisambiguationResult dr, EvidenceSnapshot ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean resolved = dr != null;
        boolean contradictory = ev != null && ev.isContradictory();
        double suspicion = contradictory ? 0.82 : (resolved ? 0.22 : 0.58);
        m.put("suspicionScore", round4(suspicion));
        List<String> flags = new ArrayList<>();
        if (!resolved) flags.add("unresolved_entity");
        if (contradictory) flags.add("contradictory");
        if (flags.isEmpty()) flags.add("ok");
        m.put("flags", flags);
        return m;
    }

    // ────────────────────── Utilities ──────────────────────

    static int tokenBucket(int maxTokens) {
        int[] buckets = {256, 512, 1024, 2048, 4096, 8192};
        int best = buckets[0];
        int bestDiff = Math.abs(maxTokens - best);
        for (int b : buckets) {
            int d = Math.abs(maxTokens - b);
            if (d < bestDiff) { best = b; bestDiff = d; }
        }
        return best;
    }

    static double demandScore(RouteSignal s) {
        double complexity = nz(s.complexity());
        double uncertainty = nz(s.uncertainty());
        double gamma = nz(s.gamma());
        double tokenTerm = Math.tanh(Math.max(0, s.maxTokens()) / 1000.0);
        return 0.35 * complexity + 0.25 * uncertainty + 0.20 * gamma + 0.20 * tokenTerm;
    }

    private static double nz(double v) { return Double.isFinite(v) ? v : 0.0; }

    static double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-x)); }

    static double gini(double[] p) {
        // Gini based on Lorenz curve approximation
        double[] sorted = Arrays.copyOf(p, p.length);
        Arrays.sort(sorted);
        double cum = 0.0, lorenzArea = 0.0;
        for (double v : sorted) {
            cum += v;
            lorenzArea += cum - v / 2.0;
        }
        double total = cum;
        if (total <= 0) return 0.0;
        lorenzArea /= total;
        double B = lorenzArea / sorted.length;
        double A = 0.5 - B;
        return 1 - 2 * B;
    }

    static Map<String, Integer> histogram(double[] p) {
        Map<String, Integer> bins = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            String key = String.format("%d-%d", i*10, i*10 + 9);
            bins.put(key, 0);
        }
        for (double v : p) {
            int pct = (int)Math.floor(v * 100);
            if (pct > 99) pct = 99;
            int idx = pct / 10;
            String key = String.format("%d-%d", idx*10, idx*10 + 9);
            bins.put(key, bins.get(key) + 1);
        }
        return bins;
    }

    static int indexOf(List<String> xs, String s) {
        if (xs == null || s == null) return -1;
        for (int i = 0; i < xs.size(); i++) {
            if (s.equalsIgnoreCase(xs.get(i))) return i;
        }
        return -1;
    }

    static int indexOf(double[] xs, double v) {
        for (int i = 0; i < xs.length; i++) if (xs[i] == v) return i;
        return -1;
    }

    static double[] pseudoWeights(List<String> ids) {
        if (ids == null) return new double[]{};
        double[] w = new double[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).toLowerCase(Locale.ROOT);
            double s = 0.5;
            if (id.contains("xl") || id.contains("ultra")) s = 0.85;
            else if (id.contains("moe") || id.contains("pro")) s = 0.75;
            else if (id.contains("plus")) s = 0.65;
            else if (id.contains("lite") || id.contains("mini")) s = 0.35;
            w[i] = s;
        }
        return w;
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static List<String> computeFlags(double demand, double suspicion, RouteSignal s) {
        List<String> flags = new ArrayList<>();
        double att = sigmoid(3.2 * (demand - 0.5)) * (1.0 - sigmoid(3.2 * (suspicion - 0.5)));
        if (demand >= 0.8) flags.add("high_demand");
        if (demand <= 0.2) flags.add("low_demand");
        if (suspicion >= 0.7) flags.add("high_risk");
        if (suspicion <= 0.3) flags.add("low_risk");
        if (att >= 0.7) flags.add("strong_signal");
        if (att <= 0.3) flags.add("weak_signal");
        if (flags.isEmpty()) flags.add("ok");
        return flags;
    }

}