package com.acme.aicore.domain.ranking;

import com.acme.aicore.adapters.ranking.WeightedRrfRanking;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankingParams;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;




import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WeightedRrfRanking}.  Ensures that vector results
 * receive higher weight when configured so that their documents outrank
 * equally ranked web results.
 */
public class WeightedRrfRankingTest {
    @Test
    void fuseGivesMoreWeightToVector() {
        // Create bundles: one from web and one from vector with identical docs
        var webDoc1 = new SearchBundle.Doc("doc1", "title", "snippet", "url", "2025-01-01");
        var webBundle = new SearchBundle("web", List.of(webDoc1));
        var vectorBundle = new SearchBundle("vector", List.of(webDoc1));
        // Configure ranking params: vector weight > web weight
        RankingParams params = new RankingParams();
        params.weights().put("vector", 2.0);
        params.weights().put("web", 1.0);
        params.setWindowM(1);
        // Fuse and rank
        WeightedRrfRanking rrf = new WeightedRrfRanking();
        List<RankedDoc> ranked = rrf.fuseAndRank(List.of(webBundle, vectorBundle), params).block();
        assertThat(ranked).isNotNull();
        assertThat(ranked).hasSize(1);
        // The single doc should have a score equal to the sum of both contributions
        double expected = (1.0 / (params.rrfK() + 1)) * (params.weightOf("web") + params.weightOf("vector"));
        assertThat(ranked.get(0).score()).isEqualTo(expected);
    }
}