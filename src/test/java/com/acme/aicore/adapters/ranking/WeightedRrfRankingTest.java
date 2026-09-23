package com.acme.aicore.adapters.ranking;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.SearchBundle;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedRrfRankingTest {

    @Test
    void nullParamsUseDefaultRankingParams() {
        WeightedRrfRanking ranking = new WeightedRrfRanking();
        SearchBundle bundle = new SearchBundle("web", List.of(
                new SearchBundle.Doc("doc-a", "A", "Snippet", "https://example.test/a", "")));

        List<RankedDoc> ranked = ranking.fuseAndRank(List.of(bundle), null).block();

        assertEquals(1, ranked.size());
        assertEquals("doc-a", ranked.get(0).id());
    }

    @Test
    void nullBundlesAreIgnoredInsteadOfFailingFusion() {
        WeightedRrfRanking ranking = new WeightedRrfRanking();

        List<RankedDoc> ranked = ranking.fuseAndRank(Collections.singletonList(null), null).block();

        assertTrue(ranked.isEmpty());
    }

    @Test
    void negativeWindowReturnsEmptyInsteadOfFailingFusion() {
        WeightedRrfRanking ranking = new WeightedRrfRanking();
        SearchBundle bundle = new SearchBundle("web", List.of(
                new SearchBundle.Doc("doc-a", "A", "Snippet", "https://example.test/a", "")));
        var params = new com.acme.aicore.domain.model.RankingParams();
        params.setWindowM(-1);

        List<RankedDoc> ranked = ranking.fuseAndRank(List.of(bundle), params).block();

        assertTrue(ranked.isEmpty());
    }
}
