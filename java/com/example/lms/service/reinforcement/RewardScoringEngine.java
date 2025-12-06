package com.example.lms.service.reinforcement;

import com.example.lms.entity.TranslationMemory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;




/**
 * <h2>RewardScoringEngine - Unified Edition ({스터프18}+{스터프19})</h2>
 *
 * <p>🔧 2025-08-01 Hot-Patch (보르다 결합 지원)
 * <ul>
 *   <li>✅ Borda Count rank-fusion utility 추가 ({스터프1}).</li>
 *   <li>✅ `Similarity / Hit / Recency` 외에 <b>가변 정책</b>을 주입할 수 있는 Builder 확장.</li>
 *   <li>✅ `sigmoid()` 미분 안정화 (large hit count 시 overflow 방지).</li>
 *   <li>♻️ 자잘한 NPE 가드 및 generic 정리.</li>
 * </ul>
 */
//검색
public final class RewardScoringEngine {

    /* ────── Constants ────── */

    /** Minimum cosine similarity accepted from vector RAG (Roadmap ②). */
    public static final double SIMILARITY_FLOOR = 0.25;

    /* ────── Public API ────── */

    /* ────── Factory ────── */
    /** Returns a fresh {@link Builder}. */
    public static Builder builder() { return new Builder(); }   // ← NEW

    /** Default engine with re-tuned hyper-parameters (Roadmap ③). */
    public static final RewardScoringEngine DEFAULT = builder().build();

    /**
     * Re-scores the given {@link TranslationMemory} snippet and updates its
     * <code>rewardMean</code>/<code>hitCount</code> in-place.
     */
    public double reinforce(TranslationMemory mem, String queryText, double similarity) {
        double reward = policy.compute(mem, queryText, similarity);
        int n = mem.getHitCount();
        mem.setRewardMean((mem.getRewardMean() * n + reward) / (n + 1));
        mem.setHitCount(n + 1);
        return reward;
    }

    /* ────── Policy Plumbing ────── */

    /** Pure-function contract - trivial to unit-test. */
    public interface RewardPolicy {
        double compute(TranslationMemory mem, String queryText, double similarity);
    }

    /** Composite with per-delegate weights. */
    public static class CompositePolicy implements RewardPolicy {
        private final List<Weighted> delegates;
        public CompositePolicy(List<Weighted> delegates) { this.delegates = List.copyOf(delegates); }
        @Override public double compute(TranslationMemory m, String q, double s) {
            double num = 0, den = 0;
            for (Weighted w : delegates) {
                double r = w.policy.compute(m, q, s);
                num += r * w.weight;
                den += w.weight;
            }
            return den == 0 ? 0 : num / den;
        }
        /** record-like helper */
        public static final class Weighted {
            final RewardPolicy policy; final double weight;
            public Weighted(RewardPolicy p, double w){ this.policy=p; this.weight=w; }
        }
    }

    /* ────── Concrete Policies ────── */

    /** 1️⃣ Similarity - clamps to [SIMILARITY_FLOOR, 1]. */
    public static class SimilarityPolicy implements RewardPolicy {
        @Override public double compute(TranslationMemory m, String q, double sim) {
            if (sim < 0) return 0;                         // similarity unknown
            double adjusted = Math.max(sim, SIMILARITY_FLOOR);
            return clamp(adjusted, 0, 1);
        }
    }

    /** 2️⃣ Hit-Count - logistic popularity curve. */
    public static class HitCountPolicy implements RewardPolicy {
        private final double k;
        public HitCountPolicy(double k) { this.k = k; }
        @Override public double compute(TranslationMemory m, String q, double s) {
            return sigmoid(m.getHitCount(), k);
        }
    }

    /** 3️⃣ Recency - exponential decay with configurable half-life. */
    public static class RecencyPolicy implements RewardPolicy {
        private final double lambdaPerDay;
        public RecencyPolicy(Duration halfLife) {
            this.lambdaPerDay = Math.log(2) / Math.max(1, halfLife.toDays());
        }
        @Override public double compute(TranslationMemory m, String q, double s) {
            LocalDateTime created = m.getCreatedAt();
            if (created == null) return 1.0; // treat as brand-new
            long days = Math.max(0, Duration.between(created, LocalDateTime.now()).toDays());
            return Math.exp(-lambdaPerDay * days);
        }
    }

    /* ────── Borda Count Rank-Fusion ({스터프1}) ────── */

    /**
     * <p>Reciprocal Rank Fusion(RRF) 대비 구현 간단 & 파라미터-free.</p>
     * <p>두 개 이상의 랭킹 리스트를 받아 Borda 점수를 합산한 뒤 내림차순으로 정렬해 돌려줍니다.</p>
     *
     * <pre>{@code
     * List<List<String>> lists = List.of(bm25Ranked, faissRanked);
     * List<Map.Entry<String,Integer>> fused = RewardScoringEngine.bordaFuse(lists);
     * }</pre>
     * @param rankedLists 랭킹 리스트 컬렉션(0 index = 1등)
     */
    public static <T> List<Map.Entry<T,Integer>> bordaFuse(List<List<T>> rankedLists){
        Objects.requireNonNull(rankedLists, "rankedLists");
        Map<T,Integer> scores = new HashMap<>();
        for (List<T> list : rankedLists){
            int size = list.size();
            for (int rank = 0; rank < size; rank++){
                T item = list.get(rank);
                int point = size - rank - 1;              // 1등 → size-1 점
                scores.merge(item, point, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }

    /* ────── Builder & Internals ────── */

    public static class Builder {
        /* ── Core hyper-parameters ── */
        private double wSim = 0.55, wHit = 0.30, wRec = 0.15;
        private double kSig = 0.25;
        private Duration halfLife = Duration.ofDays(14);

        /* ✨ NEW - allow the tuner to disable weight normalisation temporarily */
        private boolean normaliseWeights = true;

        private final List<CompositePolicy.Weighted> extraPolicies = new ArrayList<>();
        /** Relative weights (normalised automatically). */
        public Builder weights(double similarity, double hit, double recency) {
            this.wSim = similarity; this.wHit = hit; this.wRec = recency; return this;
        }
        public Builder halfLifeDays(long days) { this.halfLife = Duration.ofDays(days); return this; }
        public Builder hitSigmoidK(double k) { this.kSig = k; return this; }


        /* --- Tuning helpers (RewardHyperparameterTuner 요구) --- */
        public Builder normaliseWeights(boolean flag) { this.normaliseWeights = flag; return this; }
        public double getWeightSim() { return wSim; }
        public double getWeightHit() { return wHit; }
        public double getWeightRec() { return wRec; }
        /**
         * <p>Extra custom policy injection point.<br>
         * 예) RRF, Category boost 등.</p>
         */
        public Builder addPolicy(RewardPolicy policy, double weight){
            extraPolicies.add(new CompositePolicy.Weighted(policy, weight));
            return this;
        }

        public RewardScoringEngine build() {
                       /* 정규화 비활성화 시, 무게 합계를 1로 고정해 스케일링 생략 */
                                double total = normaliseWeights
                                        ? (wSim + wHit + wRec + extraPolicies.stream().mapToDouble(w->w.weight).sum())
                                       : 1.0;
            List<CompositePolicy.Weighted> delegates = new ArrayList<>(List.of(
                    new CompositePolicy.Weighted(new SimilarityPolicy(), wSim),
                    new CompositePolicy.Weighted(new HitCountPolicy(kSig), wHit),
                    new CompositePolicy.Weighted(new RecencyPolicy(halfLife), wRec)
            ));
            delegates.addAll(extraPolicies);
            if (normaliseWeights) {
                delegates = delegates.stream()
                        .map(w -> new CompositePolicy.Weighted(w.policy, w.weight / total))
                        .collect(Collectors.toList());
            }
            return new RewardScoringEngine(new CompositePolicy(delegates));
        }
    }

    private final RewardPolicy policy;
    private RewardScoringEngine(RewardPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "Policy must not be null");
    }

    /* ────── Helpers ────── */

    private static double sigmoid(int hit, double k) {
        double z = -k * (hit - 7);
        if (z > 60) return 0;           // overflow guard
        if (z < -60) return 1;
        return 1.0 / (1.0 + Math.exp(z));
    }
    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return 0; return Math.max(min, Math.min(max, v));
    }

}