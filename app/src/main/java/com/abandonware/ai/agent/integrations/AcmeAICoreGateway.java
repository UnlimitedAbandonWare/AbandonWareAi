package com.abandonware.ai.agent.integrations;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.acme.aicore.domain.ports.RankingPort;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import java.util.*;
import java.util.stream.Collectors;





@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.AcmeAICoreGateway
 * Role: config
 * Dependencies: com.acme.aicore.domain.ports.WebSearchProvider, com.acme.aicore.domain.ports.RankingPort, com.acme.aicore.domain.model.WebSearchQuery, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.AcmeAICoreGateway
role: config
*/
public class AcmeAICoreGateway implements WebSearchGateway {

    private final List<WebSearchProvider> providers;
    private final RankingPort ranking;

    @Autowired
    public AcmeAICoreGateway(List<WebSearchProvider> providers, RankingPort ranking) {
        this.providers = providers;
        this.ranking = ranking;
    }

    @Override
    public List<Map<String, Object>> searchAndRank(String query, int topK, String lang) {
        List<SearchBundle> bundles = new ArrayList<>();
        for (WebSearchProvider p : providers) {
            try {
                var bundle = p.search(new WebSearchQuery(query)).block();
                if (bundle != null) bundles.add(bundle);
            } catch (Exception e) {
                // skip provider on error
            }
        }
        if (bundles.isEmpty()) return List.of();

        List<RankedDoc> ranked = ranking.fuseAndRank(bundles, RankingParams.defaults()).block();
        if (ranked == null) return List.of();

        Map<String, SearchBundle.Doc> byId = bundles.stream()
                .flatMap(b -> b.docs().stream())
                .collect(Collectors.toMap(SearchBundle.Doc::id, d -> d, (a,b)->a));

        List<Map<String,Object>> out = new ArrayList<>();
        for (RankedDoc rd : ranked) {
            var doc = byId.get(rd.id());
            if (doc != null) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", doc.id());
                m.put("title", doc.title());
                m.put("snippet", doc.snippet());
                m.put("url", doc.url());
                m.put("publishedAt", doc.publishedAt());
                m.put("score", rd.score());
                out.add(m);
                if (out.size() >= Math.max(1, topK)) break;
            }
        }
        return out;
    }
}