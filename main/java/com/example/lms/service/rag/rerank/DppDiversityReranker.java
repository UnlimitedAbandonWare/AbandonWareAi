package com.example.lms.service.rag.rerank;

import com.example.lms.search.TraceStore;
import org.apache.commons.codec.digest.DigestUtils;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Drop-in diversity reranker with greedy determinantal-kernel selection.
 * Designed to be resilient to missing dependencies. Constructor accepts varargs for optional deps.
 *
 * <p>The kernel diagonal is relevance quality and the off-diagonal terms are
 * text-similarity affinities damped by {@link Config#lambda}. Greedy MAP
 * selection then maximizes the determinant of the selected candidate kernel.
 */
@Component
public class DppDiversityReranker {

    public static class Config {
        public final double lambda; // relevance-diversity tradeoff (0..1)
        public final int defaultK;

        public Config(double lambda, int defaultK) {
            this.lambda = Math.max(0.0, Math.min(1.0, lambda));
            this.defaultK = Math.max(1, defaultK);
        }
    }

    private final Config cfg;
    @SuppressWarnings("unused")
    private final Object[] deps;

    public DppDiversityReranker() {
        this(new Config(0.7d, 8));
    }

    public DppDiversityReranker(Config cfg, Object... deps) {
        this.cfg = cfg;
        this.deps = deps;
    }

    /** Generic rerank: in-place safe (returns new list). */
    public <T> List<T> rerank(List<T> in, String query, int k) {
        return rerank(in, query, k, Objects::toString, null);
    }

    /** Generic rerank with a per-call config for Spring-managed reuse. */
    public <T> List<T> rerank(Config callConfig,
                              List<T> in,
                              String query,
                              int k,
                              Function<? super T, String> textOf,
                              ToDoubleFunction<? super T> relevanceOf) {
        return rerankInternal(callConfig, in, query, k, textOf, relevanceOf, null);
    }

    public <T> List<T> rerank(Config callConfig,
                              List<T> in,
                              String query,
                              int k,
                              Function<? super T, String> textOf,
                              ToDoubleFunction<? super T> relevanceOf,
                              Function<? super T, String> stableKeyOf) {
        return rerankInternal(callConfig, in, query, k, textOf, relevanceOf, stableKeyOf);
    }

    /** Generic rerank with typed extractors for hot runtime paths. */
    public <T> List<T> rerank(List<T> in,
                              String query,
                              int k,
                              Function<? super T, String> textOf,
                              ToDoubleFunction<? super T> relevanceOf) {
        return rerankInternal(cfg, in, query, k, textOf, relevanceOf, null);
    }

    private <T> List<T> rerankInternal(Config effectiveConfig,
                                       List<T> in,
                                       String query,
                                       int k,
                                       Function<? super T, String> textOf,
                                       ToDoubleFunction<? super T> relevanceOf,
                                       Function<? super T, String> stableKeyOf) {
        if (in == null || in.isEmpty()) {
            traceRerank(0, 0, Math.max(0, k), 0.0d, true, "empty_input");
            return Collections.emptyList();
        }
        double lambda = effectiveConfig != null ? effectiveConfig.lambda : 0.7;
        Function<? super T, String> extractor =
                textOf != null ? textOf : Objects::toString;
        Map<T, String> textCache = new IdentityHashMap<>();
        Function<T, String> cachedText = item ->
                textCache.computeIfAbsent(item, it -> safeText(extractor, it));
        Map<T, String> stableKeys = new IdentityHashMap<>();
        List<T> eligible = new ArrayList<>(in.size());
        for (T item : in) {
            String stableKey = stableKeyOf == null
                    ? defaultStableKey(cachedText.apply(item))
                    : explicitStableKey(stableKeyOf, item);
            if (stableKey == null || stableKey.isBlank()) {
                continue;
            }
            stableKeys.put(item, stableKey);
            eligible.add(item);
        }
        if (eligible.isEmpty()) {
            traceRerank(in.size(), 0, Math.max(0, k), 0.0d, true, "stable_key_missing");
            return Collections.emptyList();
        }
        eligible.sort(Comparator.comparing(stableKeys::get));
        k = Math.min(Math.max(1, k), eligible.size());
        Map<T, Set<String>> shingleCache = new IdentityHashMap<>();
        Function<T, Set<String>> cachedShingles = item ->
                shingleCache.computeIfAbsent(item, it -> shingles(cachedText.apply(it), 3));

        // Relevance: basic position prior (higher = better) with light query overlap bonus
        Map<T, Double> rel = new IdentityHashMap<>();
        for (int i=0;i<eligible.size();i++) {
            T t = eligible.get(i);
            double base = 1.0 - (i * 1.0 / Math.max(1, eligible.size()-1)); // 1..0
            double bonus = overlapScore(cachedText.apply(t), query);
            rel.put(t, relevance(relevanceOf, t, clamp01(0.85*base + 0.15*bonus)));
        }

        List<T> out = greedyDeterminantalSelect(
                eligible, k, lambda, rel, cachedShingles, stableKeys);
        traceRerank(in.size(), out.size(), k, diversityScore(out, cachedShingles), false, "");
        return out;
    }

    /** Convenience select overloads (for compatibility). */
    public <T> List<T> select(List<T> in, int k) {
        return rerank(in, "", k);
    }
    public <T> List<T> select(List<T> in, int k, Function<T,String> textOf, double lambda) {
        return rerankInternal(new Config(lambda, k), in, "", k, textOf, null, null);
    }

    private static <T> List<T> greedyDeterminantalSelect(List<T> in,
                                                         int k,
                                                         double lambda,
                                                         Map<T, Double> relevance,
                                                         Function<T, Set<String>> shinglesOf,
                                                         Map<T, String> stableKeys) {
        List<T> chosen = new ArrayList<>();
        Set<T> chosenSet = Collections.newSetFromMap(new IdentityHashMap<>());
        while (chosen.size() < k) {
            T best = null;
            double bestScore = -1.0d;
            for (T cand : in) {
                if (chosenSet.contains(cand)) {
                    continue;
                }
                double score = determinantScore(chosen, cand, lambda, relevance, shinglesOf);
                double scoreTolerance = 1.0e-12d * Math.max(Math.abs(score), Math.abs(bestScore));
                if (score > bestScore + scoreTolerance
                        || (best != null
                        && Math.abs(score - bestScore) <= scoreTolerance
                        && stableKeys.get(cand).compareTo(stableKeys.get(best)) < 0)) {
                    bestScore = score;
                    best = cand;
                }
            }
            if (best == null) {
                break;
            }
            chosen.add(best);
            chosenSet.add(best);
        }
        return chosen;
    }

    private static <T> double determinantScore(List<T> chosen,
                                               T candidate,
                                               double lambda,
                                               Map<T, Double> relevance,
                                               Function<T, Set<String>> shinglesOf) {
        List<T> trial = new ArrayList<>(chosen.size() + 1);
        trial.addAll(chosen);
        trial.add(candidate);
        return determinant(kernelMatrix(trial, lambda, relevance, shinglesOf));
    }

    private static <T> double diversityScore(List<T> items, Function<T, Set<String>> shinglesOf) {
        if (items == null || items.size() < 2 || shinglesOf == null) {
            return 0.0d;
        }
        double sum = 0.0d;
        int pairs = 0;
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                sum += 1.0d - setSimilarity(shinglesOf.apply(items.get(i)), shinglesOf.apply(items.get(j)));
                pairs++;
            }
        }
        return pairs <= 0 ? 0.0d : round4(clamp01(sum / pairs));
    }

    private static void traceRerank(int inputCount,
                                     int outputCount,
                                     int k,
                                     double diversityScore,
                                     boolean skipped,
                                     String skipReason) {
        int safeInputCount = Math.max(0, inputCount);
        int safeOutputCount = Math.max(0, outputCount);
        int safeK = Math.max(0, k);
        String safeSkipReason = skipReason == null ? "" : skipReason;
        boolean applied = !skipped && safeOutputCount > 0;
        TraceStore.put("dpp.rerank.inputCount", safeInputCount);
        TraceStore.put("dpp.rerank.outputCount", safeOutputCount);
        TraceStore.put("dpp.rerank.diversityScore", clamp01(diversityScore));
        TraceStore.put("dpp.rerank.skipped", skipped);
        TraceStore.put("dpp.rerank.skipReason", safeSkipReason);
        TraceStore.put("hypernova.dppApplied", applied);
        TraceStore.put("hypernova.dppInputCount", safeInputCount);
        TraceStore.put("hypernova.dppOutputCount", safeOutputCount);
        TraceStore.put("hypernova.dppK", safeK);
        TraceStore.put("hypernova.dppDisabledReason", applied ? "" :
                (safeSkipReason.isBlank() ? "no_output" : safeSkipReason));
    }

    private static <T> double[][] kernelMatrix(List<T> items,
                                               double lambda,
                                               Map<T, Double> relevance,
                                               Function<T, Set<String>> shinglesOf) {
        int n = items.size();
        double[][] matrix = new double[n][n];
        double safeLambda = clamp01(lambda);
        // Cubic lambda falloff is intentional: high lambda sharply suppresses
        // duplicate pressure while low/mid lambda still rewards diversity.
        double diversityWeight = 1.0d - (safeLambda * safeLambda * safeLambda);
        for (int i = 0; i < n; i++) {
            T left = items.get(i);
            double leftQuality = quality(relevance.getOrDefault(left, 0.5d));
            for (int j = i; j < n; j++) {
                T right = items.get(j);
                double rightQuality = quality(relevance.getOrDefault(right, 0.5d));
                double value;
                if (i == j) {
                    value = leftQuality * leftQuality + 1.0e-9d;
                } else {
                    double similarity = setSimilarity(shinglesOf.apply(left), shinglesOf.apply(right));
                    value = leftQuality * rightQuality * clamp01(similarity * diversityWeight);
                }
                matrix[i][j] = value;
                matrix[j][i] = value;
            }
        }
        return matrix;
    }

    private static double determinant(double[][] matrix) {
        int n = matrix.length;
        if (n == 0) {
            return 0.0d;
        }
        double[][] work = new double[n][n];
        for (int i = 0; i < n; i++) {
            work[i] = Arrays.copyOf(matrix[i], n);
        }
        double det = 1.0d;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(work[row][col]) > Math.abs(work[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(work[pivot][col]) < 1.0e-12d) {
                return 0.0d;
            }
            if (pivot != col) {
                double[] tmp = work[pivot];
                work[pivot] = work[col];
                work[col] = tmp;
                det = -det;
            }
            double pivotValue = work[col][col];
            det *= pivotValue;
            for (int row = col + 1; row < n; row++) {
                double factor = work[row][col] / pivotValue;
                for (int c = col; c < n; c++) {
                    work[row][c] -= factor * work[col][c];
                }
            }
        }
        return det < 0.0d && Math.abs(det) < 1.0e-9d ? 0.0d : Math.max(0.0d, det);
    }

    private static double quality(double relevance) {
        return Math.max(1.0e-6d, clamp01(relevance));
    }

    private static <T> String safeText(Function<? super T, String> textOf, T item) {
        try {
            String text = textOf != null ? textOf.apply(item) : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
        } catch (RuntimeException ex) {
            TraceStore.put("dpp.extractor.fallback", true);
            TraceStore.put("dpp.extractor.errorType", safeExceptionName(ex));
        }
        return String.valueOf(item);
    }

    private static String defaultStableKey(String text) {
        String normalized = normalizeStableText(text);
        return DigestUtils.sha256Hex(normalized);
    }

    private static <T> String explicitStableKey(
            Function<? super T, String> stableKeyOf,
            T item) {
        try {
            String key = stableKeyOf.apply(item);
            return key == null ? null : key.trim();
        } catch (RuntimeException ex) {
            TraceStore.put("dpp.stableKey.excluded", true);
            TraceStore.put("dpp.stableKey.errorType", safeExceptionName(ex));
            return null;
        }
    }

    private static String normalizeStableText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static <T> double relevance(ToDoubleFunction<? super T> relevanceOf, T item, double fallback) {
        if (relevanceOf == null) {
            return fallback;
        }
        try {
            return clamp01(relevanceOf.applyAsDouble(item));
        } catch (RuntimeException ex) {
            TraceStore.put("dpp.relevance.fallback", true);
            TraceStore.put("dpp.relevance.errorType", safeExceptionName(ex));
            return fallback;
        }
    }

    private static String safeExceptionName(RuntimeException ex) {
        return ex == null ? "RuntimeException" : ex.getClass().getSimpleName();
    }

    private static double textSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<String> A = shingles(a, 3);
        Set<String> B = shingles(b, 3);
        return setSimilarity(A, B);
    }

    private static double setSimilarity(Set<String> A, Set<String> B) {
        if (A.isEmpty() || B.isEmpty()) return 0.0;
        int inter = 0;
        for (String x : A) if (B.contains(x)) inter++;
        return inter / Math.sqrt((double)A.size() * (double)B.size());
    }

    private static Set<String> shingles(String s, int n) {
        Set<String> set = new HashSet<>();
        if (s == null) return set;
        s = s.toLowerCase(Locale.ROOT);
        for (int i=0;i<=s.length()-n;i++) set.add(s.substring(i, i+n));
        return set;
    }

    private static double overlapScore(String text, String query) {
        if (text == null || query == null || query.isBlank()) return 0.0;
        String[] qs = query.toLowerCase(Locale.ROOT).split("\\s+");
        int hit = 0;
        for (String q : qs) if (text.toLowerCase(Locale.ROOT).contains(q)) hit++;
        return clamp01(hit * 1.0 / Math.max(1, qs.length));
        }

    private static double clamp01(double x){
        if (!Double.isFinite(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
