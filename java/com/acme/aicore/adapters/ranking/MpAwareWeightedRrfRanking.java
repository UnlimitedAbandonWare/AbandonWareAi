package com.acme.aicore.adapters.ranking;

import com.acme.aicore.adapters.ranking.WeightedRrfRanking;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.ports.RankingPort;
import com.example.lms.service.rag.mp.LowRankWhiteningStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.stream.Collectors;




/**
 * MP-aware wrapper for {@link WeightedRrfRanking} that boosts vector bundle
 * scores and optionally dampens web bundle scores once the low-rank
 * whitening statistics have accumulated sufficient observations.  When
 * enabled via the {@code rag.mp.fuser.enabled} property this bean becomes
 * the primary implementation of {@link RankingPort} and delegates all
 * reranking to the underlying weighted RRF implementation.
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "rag.mp.fuser", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MpAwareWeightedRrfRanking implements RankingPort {
    private static final Logger log = LoggerFactory.getLogger(MpAwareWeightedRrfRanking.class);

    private final @Nullable LowRankWhiteningStats stats;
    private final WeightedRrfRanking delegate;
    private final double vectorBoostReady;
    private final double webDampReady;
    private final long readySeenThreshold;

    public MpAwareWeightedRrfRanking(
            @Nullable LowRankWhiteningStats stats,
            WeightedRrfRanking delegate,
            @Value("${rag.mp.fuser.vector-boost:1.15}") double vectorBoostReady,
            @Value("${rag.mp.fuser.web-damp:1.00}") double webDampReady,
            @Value("${rag.mp.fuser.ready-seen:200}") long readySeenThreshold
    ) {
        this.stats = stats;
        this.delegate = delegate;
        this.vectorBoostReady = vectorBoostReady;
        this.webDampReady = webDampReady;
        this.readySeenThreshold = readySeenThreshold;
    }

    @Override
    public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
        if (bundles == null || bundles.isEmpty()) {
            return Mono.just(List.of());
        }
        // Determine if the whitening stats are sufficiently populated
        boolean mpReady = false;
        long seen = -1L;
        int dim = -1;
        if (stats != null) {
            try {
                seen = stats.seen();
                dim = stats.dimension();
                mpReady = (seen >= readySeenThreshold && dim > 0);
            } catch (Throwable ignore) {
                // ignore any exceptions and treat as not ready
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[MP-FUSER] mpReady={} seen={} dim={} boost={} damp={}",
                    mpReady, seen, dim, vectorBoostReady, webDampReady);
        }
        // Compute fused scores via RRF with adjusted weights
        // Snapshot mpReady into a final alias for lambda capture
        final boolean mpReadyFinal = mpReady;
        return Mono.fromCallable(() -> {
            int k = 60;
            try {
                // LangChain4j 1.0.1 exposes the RRF parameter via rrfK() instead of k().
                // Fall back to the default when params is null or the provided value is non-positive.
                if (params != null && params.rrfK() > 0) k = params.rrfK();
            } catch (Throwable ignore) {
            }
            int window = 50;
            try {
                if (params != null && params.windowM() > 0) window = params.windowM();
            } catch (Throwable ignore) {
            }
            Map<String, Double> scoreMap = new HashMap<>();
            for (SearchBundle b : bundles) {
                String type = b.type() == null ? "" : b.type();
                double baseW = 1.0;
                try {
                    if (params != null) baseW = params.weightOf(type);
                } catch (Throwable ignore) {
                }
                double w;
                if (mpReadyFinal) {
                    // When MP statistics are ready, apply the configured boost/damp factors.
                    if ("vector".equalsIgnoreCase(type)) {
                        w = baseW * vectorBoostReady;
                    } else if ("web".equalsIgnoreCase(type)) {
                        w = baseW * webDampReady;
                    } else {
                        w = baseW;
                    }
                } else {
                    // Before MP readiness, cap the vector weight to avoid over-amplifying
                    // synthetic or noisy vector matches.  Other bundle types retain their base weight.
                    if ("vector".equalsIgnoreCase(type)) {
                        w = Math.min(0.25, baseW);
                    } else {
                        w = baseW;
                    }
                }
                int r = 1;
                for (SearchBundle.Doc d : b.docs()) {
                    double score = w * (1.0 / (k + r));
                    scoreMap.merge(d.id(), score, Double::sum);
                    r++;
                }
            }
            return scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(window)
                    .map(e -> RankedDoc.of(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
        });
    }

    @Override
public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
        // Delegate any cross-encoder or diversity operations to the default implementation
        return delegate.rerank(topN, params)
      .doOnSuccess(list -> {
          try {
              String sid = org.slf4j.MDC.get("sessionId");
              String xrid = org.slf4j.MDC.get("x-request-id");
              log.info("RANK_LABEL authority={} freshness={} rerank={} evidenceCount={} sessionId={} xrid={}",
                      "rrf", "n/a", "mp-aware", (list != null ? list.size() : 0), sid, xrid);
          } catch (Exception ignore) {}
      });
    }
}