package com.example.lms.llm;

import com.example.lms.service.rag.mp.LowRankWhiteningStats;



/**
 * Applies a low-rank whitening transformation to query embedding vectors.
 *
 * <p>This adapter delegates to an underlying {@link LowRankWhiteningStats}
 * to perform the actual transform.  It is intentionally immutable and
 * lightweight; dependency injection of the stats object determines the
 * operational state.</p>
 */
public final class LowRankWhiteningTransform implements QueryTransform {
    private final LowRankWhiteningStats stats;

    /**
     * Constructs a new transform wrapper.
     *
     * @param stats the whitening statistics provider
     */
    public LowRankWhiteningTransform(LowRankWhiteningStats stats) {
        this.stats = stats;
    }

    @Override
    public float[] apply(float[] vec) {
        return stats.transform(vec);
    }
}