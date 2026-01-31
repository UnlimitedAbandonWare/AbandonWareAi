package com.example.lms.replay;


/**
 * Simple container for aggregated replay evaluation metrics.  Instances of
 * this class are immutable and provide the most common offline metrics
 * used to assess retrieval quality.  Additional fields may be added in
 * the future as the evaluation pipeline evolves (e.g. P95 latency,
 * promotion and false promotion rates).
 */
public final class EvaluationMetrics {
    private final double ndcgAt10;
    private final double mrrAt10;
    private final double p95LatencyMs;
    private final double promotionRate;
    private final double falsePromotionRate;

    public EvaluationMetrics(double ndcgAt10, double mrrAt10, double p95LatencyMs,
                             double promotionRate, double falsePromotionRate) {
        this.ndcgAt10 = ndcgAt10;
        this.mrrAt10 = mrrAt10;
        this.p95LatencyMs = p95LatencyMs;
        this.promotionRate = promotionRate;
        this.falsePromotionRate = falsePromotionRate;
    }

    /**
     * Normalised discounted cumulative gain averaged across all replay
     * instances.  A value of 1.0 indicates that all relevant documents
     * appeared at the top of every ranking, whereas 0.0 indicates that no
     * relevant documents were retrieved.
     */
    public double getNdcgAt10() {
        return ndcgAt10;
    }

    /**
     * Mean reciprocal rank averaged across all replay instances.  Each
     * individual rank is computed as 1/(r) where r is the (1-based) position
     * of the first relevant document within the top 10.  A value of 1.0
     * indicates that every query's relevant document was retrieved at rank 1.
     */
    public double getMrrAt10() {
        return mrrAt10;
    }

    /**
     * The 95th percentile of latency values in milliseconds.  When no
     * latencies are present the value will be 0.0.
     */
    public double getP95LatencyMs() {
        return p95LatencyMs;
    }

    /**
     * The fraction of replays where at least one relevant document was
     * retrieved in the top 10.  This approximates the notion of a "promotion"
     * event in retrieval contexts: a non-zero value indicates that the
     * retrieval pipeline managed to elevate a relevant item into the top
     * positions.  A value of 1.0 means all queries had at least one
     * relevant hit, whereas 0.0 indicates that no relevant documents were
     * surfaced.
     */
    public double getPromotionRate() {
        return promotionRate;
    }

    /**
     * The fraction of replay entries where the top ranked result was not
     * relevant.  This provides a crude estimate of false promotion (or
     * "false positives") - lower values are better.  A value of 0.0
     * indicates that every top result was relevant when ground truth is
     * available.  When ground truth is empty the entry is ignored for
     * this metric.
     */
    public double getFalsePromotionRate() {
        return falsePromotionRate;
    }

    @Override
    public String toString() {
        return String.format("EvaluationMetrics{ndcgAt10=%.4f, mrrAt10=%.4f, p95LatencyMs=%.1f, promotionRate=%.4f, falsePromotionRate=%.4f}",
                ndcgAt10, mrrAt10, p95LatencyMs, promotionRate, falsePromotionRate);
    }
}