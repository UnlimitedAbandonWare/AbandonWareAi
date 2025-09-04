package com.example.lms.service.rag;

import com.example.lms.moe.ExpertDefinition;
import com.example.lms.moe.ExpertsConfig;
import com.example.lms.moe.ExpertsRegistry;
import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 전문가 라우팅 및 가중 결합을 수행하는 하이브리드 래퍼.
 * <p>
 * 활성화된 전문가가 없으면 내부 baseChain을 그대로 호출합니다. 활성화된 경우
 * 각 전문가별로 baseChain.retrieveProgressive를 호출하여 콘텐츠를 수집하고,
 * 각 전문가의 스코어를 RRF 가중치로 적용하여 결과를 하나로 병합합니다.
 */
@Component
public class MoeHybridRetriever implements HybridRetriever {

    private final HybridRetriever baseChain;
    private final ExpertsRegistry experts;

    public MoeHybridRetriever(@Lazy HybridRetriever baseChain, ExpertsRegistry experts) {
        this.baseChain = baseChain;
        this.experts = experts;
    }

    @Override
    public List<Content> retrieveAll(List<String> queries, int topK) {
        // 전문가 게이팅은 retrieveProgressive에서 처리
        List<Content> acc = new ArrayList<>();
        if (queries == null) return acc;
        for (String q : queries) {
            acc.addAll(retrieveProgressive(q, null, topK, Map.of()));
        }
        return acc;
    }

    @Override
    public List<Content> retrieveProgressive(String query, String sessionId, int topK, Map<String, Object> meta) {
        if (!experts.enabled()) {
            return baseChain.retrieveProgressive(query, sessionId, topK, meta);
        }
        ExpertsConfig cfg = experts.get();
        if (cfg == null || cfg.experts == null || cfg.experts.isEmpty()) {
            return baseChain.retrieveProgressive(query, sessionId, topK, meta);
        }
        Set<String> toks = tokenize(query);
        List<Route> routes = scoreRoutes(cfg.experts, toks, cfg.gate);
        if (routes.isEmpty()) {
            return baseChain.retrieveProgressive(query, sessionId, topK, meta);
        }
        Map<String, List<Content>> byExpert = new LinkedHashMap<>();
        for (Route r : routes) {
            Map<String,Object> childMeta = new HashMap<>(meta == null ? Map.of() : meta);
            childMeta.put("expert.id", r.expert.id);
            childMeta.put("expert.index", r.expert.index);
            childMeta.put("expert.web.allow", r.expert.web.domain_allow);
            childMeta.put("expert.web.deny", r.expert.web.domain_deny);
            childMeta.put("weights.chain.selfAsk", r.expert.weights.chain.self_ask);
            childMeta.put("weights.chain.web", r.expert.weights.chain.web);
            childMeta.put("weights.chain.vector", r.expert.weights.chain.vector);
            List<Content> docs = baseChain.retrieveProgressive(query, sessionId, topK, childMeta);
            byExpert.put(r.expert.id, docs);
        }
        return blendRrf(routes, byExpert, topK, cfg.blend);
    }

    private static Set<String> tokenize(String q) {
        if (q == null) return Set.of();
        return Arrays.stream(q.toLowerCase().split("\\s+"))
                .map(s -> s.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private static List<Route> scoreRoutes(List<ExpertDefinition> experts, Set<String> tokenSet, ExpertsConfig.Gate gate) {
        List<Route> out = new ArrayList<>();
        for (var ex : experts) {
            List<String> tags = (ex.tags == null) ? List.of() : ex.tags;
            long hits = tags.stream().filter(t -> tokenSet.contains(t.toLowerCase())).count();
            double aliasHits = tags.isEmpty() ? 0.0 : (double) hits / (double) tags.size();
            double tokOverlap = aliasHits; // 단순 휴리스틱
            double recency = 0.0;
            double score = aliasHits * gate.alias_hits_weight
                         + tokOverlap * gate.token_overlap_weight
                         + recency * gate.recency_weight;
            if (score >= gate.min_route_score) out.add(new Route(ex, Math.min(score, 1.0)));
        }
        out.sort(java.util.Comparator.comparingDouble(Route::score).reversed());
        return out;
    }

    private static List<Content> blendRrf(List<Route> routes,
                                          Map<String, List<Content>> byExpert,
                                          int topK,
                                          ExpertsConfig.Blend blend) {
        Map<String, Agg> acc = new HashMap<>();
        for (Route r : routes) {
            double ew = Math.min(r.score, blend.expert_weight_cap);
            List<Content> docs = byExpert.getOrDefault(r.expert.id, List.of());
            for (int rank = 0; rank < docs.size(); rank++) {
                Content d = docs.get(rank);
                String key = safeKey(d);
                double rrf = ew * (1.0 / (blend.rrf_k + rank + 1.0));
                acc.computeIfAbsent(key, k -> new Agg(d)).score += rrf;
            }
        }
        double norm = routes.stream().mapToDouble(Route::score).max().orElse(1.0);
        // Convert the aggregated scores into a ranked list of content.  Apply
        // normalisation if requested, then sort by descending score.
        java.util.List<Content> ranked = acc.values().stream()
                .peek(a -> a.score = "sum".equalsIgnoreCase(blend.normalize) ? a.score : a.score / norm)
                .sorted(java.util.Comparator.comparingDouble((Agg a) -> a.score).reversed())
                .map(a -> a.doc)
                .toList();
        // Perform a simple source‑aware interleave of the ranked list.  When
        // content items carry a metadata entry with key "source" equal to
        // "WEB" or "VECTOR" the lists are interleaved to balance evidence
        // from both sources.  If no source metadata is present the ranked
        // order is retained.  Limit the output to topK items.
        return interleaveBySource(ranked, Math.max(1, topK));
    }

    private static String safeKey(Content d) {
        // Use ContentCompat to derive a stable key.  Previous versions of LangChain4j
        // exposed id() on Content but this is no longer present.  Fallback to a
        // metadata based identifier or identity hash.
        return com.example.lms.service.rag.support.ContentCompat.idOf(d);
    }

    private record Route(ExpertDefinition expert, double score) {}
    private static class Agg { Content doc; double score; Agg(Content d){ this.doc = d; } }

    /**
     * Interleave the ranked documents by their source metadata.  Documents
     * annotated with a metadata entry {@code source=WEB} are taken from the
     * web search, {@code source=VECTOR} from the vector search and any
     * others are placed into an "other" category.  The algorithm alternates
     * between web and vector lists to ensure a balanced distribution of
     * evidence when both lists are non-empty.  If no source metadata is
     * available the original ranking is returned unchanged.
     *
     * @param ranked the ranked list of content
     * @param topK   the maximum number of items to return
     * @return an interleaved list of at most {@code topK} items
     */
    private static java.util.List<Content> interleaveBySource(java.util.List<Content> ranked, int topK) {
        if (ranked == null || ranked.isEmpty()) {
            return ranked;
        }
        java.util.List<Content> web = new java.util.ArrayList<>();
        java.util.List<Content> vec = new java.util.ArrayList<>();
        java.util.List<Content> other = new java.util.ArrayList<>();
        for (Content c : ranked) {
            try {
                java.lang.reflect.Method m = c.getClass().getMethod("metadata");
                Object metaObj = m.invoke(c);
                Object mapObj = null;
                if (metaObj != null) {
                    try {
                        java.lang.reflect.Method asMap = metaObj.getClass().getMethod("asMap");
                        mapObj = asMap.invoke(metaObj);
                    } catch (Exception ignore) {
                        // ignore; metadata may not have asMap
                    }
                }
                if (mapObj instanceof java.util.Map<?, ?> mm) {
                    Object src = mm.get("source");
                    if ("WEB".equals(src)) {
                        web.add(c);
                        continue;
                    } else if ("VECTOR".equals(src)) {
                        vec.add(c);
                        continue;
                    }
                }
            } catch (Exception ignore) {
                // If metadata cannot be inspected treat as "other"
            }
            other.add(c);
        }
        // If no source labels were found return the original order
        if (web.isEmpty() && vec.isEmpty()) {
            return ranked.subList(0, Math.min(topK, ranked.size()));
        }
        java.util.List<Content> out = new java.util.ArrayList<>(Math.min(topK, ranked.size()));
        int i = 0, j = 0, k = 0;
        int limit = Math.min(topK, ranked.size());
        while (out.size() < limit && (i < web.size() || j < vec.size() || k < other.size())) {
            if (i < web.size()) {
                out.add(web.get(i++));
                if (out.size() >= limit) {
                    break;
                }
            }
            if (j < vec.size()) {
                out.add(vec.get(j++));
                if (out.size() >= limit) {
                    break;
                }
            }
            if (k < other.size()) {
                out.add(other.get(k++));
            }
        }
        return out;
    }
}