package com.example.lms.service.soak;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SoakTest에서 사용할 실제 SearchOrchestrator 구현.
 *
 * - 기존 SoakConfig의 fallback(SearchOrchestrator returning empty)을 대체하여
 *   soak 테스트가 의미있는 결과를 얻을 수 있게 한다.
 * - UnifiedRagOrchestrator를 재사용하므로, Probe/Chat 경로와 동일한
 *   검색/퓨전/재랭크 정책을 공유한다(조합성/재현성).
 */
@Component
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true")
public class UnifiedRagSearchOrchestrator implements SearchOrchestrator {

    private final UnifiedRagOrchestrator orchestrator;
    private final NightmareBreaker nightmareBreaker;

    public UnifiedRagSearchOrchestrator(UnifiedRagOrchestrator orchestrator,
                                        NightmareBreaker nightmareBreaker) {
        this.orchestrator = orchestrator;
        this.nightmareBreaker = nightmareBreaker;
    }

    @Override
    public List<SearchResult> search(String query, int k) {
        int topK = Math.max(1, k);

        UnifiedRagOrchestrator.QueryRequest req = new UnifiedRagOrchestrator.QueryRequest();
        req.query = query == null ? "" : query;
        req.topK = topK;

        // Soak은 기본적으로 web+vector를 함께 사용하되,
        // kg/bm25는 환경에 따라 없을 수 있어 off로 두어 플래키를 줄인다.
        req.useWeb = true;
        req.useVector = true;
        req.useKg = false;
        req.useBm25 = false;

        // Soak은 안정적인 비교가 중요하므로 aggressive는 끄고, planId만 태깅.
        req.aggressive = false;
        req.planId = "soak.search.v1";

        // Ranking 토글: 컴포넌트 없으면 no-op
        req.enableDiversity = true;
        req.enableBiEncoder = true;
        req.enableOnnx = true;

        UnifiedRagOrchestrator.QueryResponse r;
        if (nightmareBreaker != null) {
            r = nightmareBreaker.execute(
                    "soak:search",
                    req.query,
                    () -> orchestrator.query(req),
                    rr -> (rr == null || rr.results == null),
                    () -> {
                        UnifiedRagOrchestrator.QueryResponse fb = new UnifiedRagOrchestrator.QueryResponse();
                        fb.requestId = "soak-fallback";
                        fb.planApplied = req.planId;
                        fb.debug.put("fallback", "nightmareBreaker_open_or_error");
                        return fb;
                    }
            );
        } else {
            r = orchestrator.query(req);
        }

        List<SearchResult> out = new ArrayList<>();
        if (r != null && r.results != null) {
            for (UnifiedRagOrchestrator.Doc d : r.results) {
                if (d == null) continue;
                SearchResult sr = new SearchResult();

                // Soak은 doc.id 기반 비교. UnifiedRagOrchestrator가 url/meta 기반 stableId를
                // 세팅하도록 보강했기 때문에 매 요청마다 변동이 줄어든다.
                sr.id = d.id;

                // evidence 여부는 최소 기준: snippet이 비어있지 않거나 url이 존재
                boolean hasSnippet = d.snippet != null && !d.snippet.isBlank();
                boolean hasUrl = d.meta != null && d.meta.get("url") != null && !String.valueOf(d.meta.get("url")).isBlank();
                sr.supportedByEvidence = hasSnippet || hasUrl;

                sr.relScore = d.score;
                sr.snippet = d.snippet;
                sr.source = d.source;
                if (d.meta != null && d.meta.get("url") != null) {
                    sr.url = String.valueOf(d.meta.get("url"));
                }
                out.add(sr);

                if (out.size() >= topK) {
                    break;
                }
            }
        }
        return out;
    }
}
