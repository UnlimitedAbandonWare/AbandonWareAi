package com.acme.aicore.adapters.ranking;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import com.acme.aicore.domain.ports.RankingPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.stream.Collectors;




/**
 * Implements a simple weighted Reciprocal Rank Fusion (RRF) algorithm.  Each
 * input bundle is assigned a weight according to its type via
 * {@link RankingParams#weightOf(String)}.  The final score for a document
 * is computed by summing {@code weight / (k + rank)} across all bundles.
 * Documents are then sorted by descending score.  The number of returned
 * documents is controlled by {@link RankingParams#windowM()}.
 */
@Component
public class WeightedRrfRanking implements RankingPort {
    @Override
    public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
        return Mono.fromSupplier(() -> {
            Map<String, Double> scoreMap = new HashMap<>();
            for (SearchBundle b : bundles) {
                double w = params.weightOf(b.type());
                int k = params.rrfK();
                int r = 1;
                for (SearchBundle.Doc d : b.docs()) {
                    double score = w * (1.0 / (k + r++));
                    scoreMap.merge(d.id(), score, Double::sum);
                }
            }
            return scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(params.windowM())
                    .map(e -> RankedDoc.of(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
        // Default implementation returns the input without modification.  A
        // sophisticated cross-encoder or similarity model can be integrated
        // here as needed.
        return Mono.just(topN);
    }

    /** Additional helper: fuse pre-ranked lists from multiple sources using canonical keys. */
    public static <T> Map<String, Double> fuseWithCanonicalizer(
            Map<String, List<T>> bySource,
            java.util.function.Function<T, String> idExtractor,
            java.util.function.Function<String, String> keyFn,
            int k, double defaultWeight) {

        final int K = Math.max(1, k);
        final int RRF_K = 60;
        Map<String, Double> score = new java.util.HashMap<>();
        for (Map.Entry<String, List<T>> e : bySource.entrySet()) {
            double w = defaultWeight;
            List<T> L = e.getValue();
            int r = 1;
            for (T t : L) {
                String id = idExtractor.apply(t);
                if (id == null) continue;
                String key = keyFn.apply(id);
                double s = w * (1.0 / (RRF_K + r));
                score.merge(key, s, Double::sum);
                if (r++ >= K) break;
            }
        }
        return score;
    }

}