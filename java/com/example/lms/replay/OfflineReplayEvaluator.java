package com.example.lms.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;



/**
 * Offline replay evaluator for retrieval pipelines.  Given a list of
 * {@link ReplayRecord} instances this utility computes a suite of common
 * information retrieval metrics such as NDCG@10, MRR@10, promotion rate,
 * false promotion rate and the 95th percentile of latencies.  These
 * metrics can be used to enforce regression guards in continuous
 * integration environments - for example by failing a build when NDCG
 * drops below a configured threshold.
 */
public final class OfflineReplayEvaluator {

    private OfflineReplayEvaluator() {
        // utility class
    }

    /**
     * Evaluate a collection of replay records.  Each record provides the
     * ground truth relevant identifiers and the ranked results returned
     * by the system.  The evaluation assumes that identifiers are unique
     * within the ranked list.  Results beyond the 10th position are
     * ignored for NDCG and MRR calculations in accordance with standard IR
     * practice.
     *
     * @param records the replay records to evaluate
     * @return an aggregated set of metrics summarising the performance
     */
    public static EvaluationMetrics evaluate(List<ReplayRecord> records) {
        if (records == null || records.isEmpty()) {
            return new EvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double ndcgSum = 0.0;
        double mrrSum = 0.0;
        int ndcgCount = 0;
        int mrrCount = 0;
        int promotionCount = 0;
        int falsePromotionCount = 0;
        List<Long> latencies = new ArrayList<>();
        for (ReplayRecord rec : records) {
            List<String> truth = rec.getGroundTruth();
            List<String> ranked = rec.getRankedResults();
            if (!ranked.isEmpty()) {
                latencies.add(rec.getLatencyMs());
            }
            // Compute DCG and IDCG for NDCG@10.  Skip if no ground truth.
            if (truth != null && !truth.isEmpty()) {
                double dcg = 0.0;
                int topK = Math.min(10, ranked.size());
                for (int i = 0; i < topK; i++) {
                    String doc = ranked.get(i);
                    if (truth.contains(doc)) {
                        int rankIndex = i + 1; // 1-based index
                        dcg += 1.0 / log2(rankIndex + 1);
                    }
                }
                // Compute ideal DCG.  The best possible ranking is all relevant docs at top.
                int idealK = Math.min(10, truth.size());
                double idcg = 0.0;
                for (int i = 0; i < idealK; i++) {
                    int rankIndex = i + 1;
                    idcg += 1.0 / log2(rankIndex + 1);
                }
                if (idcg > 0.0) {
                    ndcgSum += dcg / idcg;
                    ndcgCount++;
                }
                // MRR: rank of first relevant document
                double reciprocal = 0.0;
                for (int i = 0; i < topK; i++) {
                    String doc = ranked.get(i);
                    if (truth.contains(doc)) {
                        int rankIndex = i + 1;
                        reciprocal = 1.0 / rankIndex;
                        break;
                    }
                }
                mrrSum += reciprocal;
                mrrCount++;
                // Promotion: at least one relevant doc surfaced in top 10
                if (reciprocal > 0.0) {
                    promotionCount++;
                }
                // False promotion: top result is not relevant
                if (!ranked.isEmpty()) {
                    String top = ranked.get(0);
                    // ignore entries with empty truth
                    if (!truth.contains(top)) {
                        falsePromotionCount++;
                    }
                }
            }
        }
        double ndcg = ndcgCount > 0 ? ndcgSum / ndcgCount : 0.0;
        double mrr = mrrCount > 0 ? mrrSum / mrrCount : 0.0;
        double promoRate = ndcgCount > 0 ? (double) promotionCount / ndcgCount : 0.0;
        double falsePromoRate = ndcgCount > 0 ? (double) falsePromotionCount / ndcgCount : 0.0;
        double p95 = computeP95(latencies);
        return new EvaluationMetrics(ndcg, mrr, p95, promoRate, falsePromoRate);
    }

    private static double computeP95(List<Long> latencies) {
        if (latencies == null || latencies.isEmpty()) {
            return 0.0;
        }
        // Remove zero or negative latencies as they don't contribute to meaningful percentile.
        List<Long> filtered = new ArrayList<>();
        for (Long l : latencies) {
            if (l != null && l > 0L) {
                filtered.add(l);
            }
        }
        if (filtered.isEmpty()) {
            return 0.0;
        }
        Collections.sort(filtered);
        int n = filtered.size();
        // 95th percentile index (rounded up to nearest integer) minus one for zero-based index
        int index = (int) Math.ceil(0.95 * n) - 1;
        index = Math.min(Math.max(index, 0), n - 1);
        return filtered.get(index);
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2.0);
    }
}