package com.acme.aicore.domain.ports;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import reactor.core.publisher.Mono;
import java.util.List;




/**
 * RankingPort defines fusion and reranking operations over one or more
 * {@link SearchBundle}s.  The fuse step combines multiple bundles (web,
 * vector, etc.) into a single list of ranked documents according to
 * weights and algorithm parameters.  The rerank step is optional and may
 * apply a more expensive reranking algorithm such as a cross encoder.
 */
public interface RankingPort {
    Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params);
    Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params);
}