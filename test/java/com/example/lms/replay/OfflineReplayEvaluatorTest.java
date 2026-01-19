package com.example.lms.replay;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link OfflineReplayEvaluator}.  These tests
 * validate the correctness of the NDCG@10, MRR@10 and derived metrics on
 * small synthetic data sets.  The intention is to catch regressions in
 * metric calculation logic and provide documentary examples of expected
 * behaviour.
 */
public class OfflineReplayEvaluatorTest {

    @Test
    public void testEvaluateMetrics() {
        // First record: relevant docs d1,d2; ranking returns d1 at rank 1 and miss d2.
        ReplayRecord r1 = new ReplayRecord(
                "q1",
                Arrays.asList("d1", "d2"),
                Arrays.asList("d1", "d3", "d4"),
                100L);
        // Second record: relevant doc d5; ranking returns d5 at rank 2.
        ReplayRecord r2 = new ReplayRecord(
                "q2",
                List.of("d5"),
                Arrays.asList("d6", "d5"),
                200L);
        EvaluationMetrics metrics = OfflineReplayEvaluator.evaluate(Arrays.asList(r1, r2));
        // Expected NDCG: average of [1/log2(2) / (1/log2(2) + 1/log2(3)), (1/log2(3)) / (1/log2(2))]
        double expectedNdcg1 = 1.0 / (1.0 + (1.0 / (Math.log(3) / Math.log(2))));
        // Note: 1/log2(3) = 1/1.58496 = 0.63093/* ... */
        double ideal1 = 1.0 + 1.0 / (Math.log(3) / Math.log(2));
        expectedNdcg1 = 1.0 / ideal1;
        double expectedNdcg2 = (1.0 / (Math.log(3) / Math.log(2))) / 1.0;
        double expectedAvgNdcg = (expectedNdcg1 + expectedNdcg2) / 2.0;
        // Expected MRR: (1 + 1/2)/2 = 0.75
        double expectedMrr = (1.0 + 0.5) / 2.0;
        // Promotion: both records have at least one relevant doc retrieved
        double expectedPromotion = 1.0;
        // False promotion: second record's top result is irrelevant -> 0.5
        double expectedFalsePromotion = 0.5;
        // P95 latency: [100,200] -> 95th percentile = 200
        double expectedP95 = 200.0;
        // Check approximate equality with tolerance 1e-3 for NDCG
        assertThat(metrics.getNdcgAt10()).isCloseTo(expectedAvgNdcg, within(1e-3));
        assertThat(metrics.getMrrAt10()).isCloseTo(expectedMrr, within(1e-3));
        assertThat(metrics.getPromotionRate()).isEqualTo(expectedPromotion);
        assertThat(metrics.getFalsePromotionRate()).isEqualTo(expectedFalsePromotion);
        assertThat(metrics.getP95LatencyMs()).isEqualTo(expectedP95);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}