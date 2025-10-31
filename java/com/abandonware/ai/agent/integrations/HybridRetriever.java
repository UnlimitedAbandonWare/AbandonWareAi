package com.abandonware.ai.agent.integrations;

import com.example.rag.fusion.WeightedRRF;
import com.example.retrieval.KAllocator;
import com.example.moe.GateVector;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.springframework.stereotype.Component;




/**
 * HybridRetriever: local BM25 + Title + Recency -> MMR; (optional) Tavily + RRF; (optional) 2-pass rerank.
 * Returns the standard list of maps: [{id,title,snippet,source,score,rank}]
 */

@Component("hybridRetriever")
public class HybridRetriever {

    private final Bm25Index index;
    private final TavilyWebSearchRetriever tavily = new TavilyWebSearchRetriever();
    private final LruCache<String, List<Map<String,Object>>> cache = new LruCache<>(128);

    public HybridRetriever() {
        Path repo = Paths.get(".").toAbsolutePath().normalize();
        this.index = new Bm25Index(repo);
    }

    public List<Map<String,Object>> retrieve(String query, Integer topK, String domain) {
        try {
            index.ensureBuilt();
        } catch (IOException e) {
            // ignore and continue with empty index
        }
        int k = (topK == null ? 6 : topK);
        String cacheKey = TextUtils.normalizeQueryKey(query) + "||" + (domain==null?"":domain) + "||" + k;
        if (cache.containsKeyValue(cacheKey)) {
            
        return cache.getValue(cacheKey);
        }

        // 1) local search
        String domainFilter = deriveDomainFilter(domain);
        List<Bm25Index.SearchResult> localCandidates = index.search(query, domainFilter, Math.max(32, k*5));
        List<Map<String,Object>> local = toResultMaps(query, localCandidates);

        // 2) MMR over local
        List<Map<String,Object>> rerankedLocal = mmr(query, local, k);

        List<Map<String,Object>> fused = rerankedLocal;
        boolean useRrf = useRrf(domain);
        if (useRrf) {
            List<Map<String,Object>> web = tavily.search(query, k, domain);
            fused = RrfFusion.fuse(rerankedLocal, web);
        }

        
        // DBVM-X-RAG Tesseract fusion (safe insertion)
        try {
            com.abandonware.ai.agent.integrations.TesseractFusion fx = new com.abandonware.ai.agent.integrations.TesseractFusion();
            fused = fx.rerank(query, fused, k, domain, null);
        } catch (Throwable t) {
            // ignore fusion errors to keep retriever resilient
        }

// 3) second-pass rerank (optional)
        List<Map<String,Object>> stage2 = applySecondPass(query, fused);

        // 4) select topK & ensure ranks
        List<Map<String,Object>> finalList = new ArrayList<>();
        for (int i=0;i<Math.min(k, stage2.size()); i++) {
            Map<String,Object> m = new LinkedHashMap<>(stage2.get(i));
            m.put("rank", i+1);
            finalList.add(m);
        }
        cache.putValue(cacheKey, finalList);
        return finalList;
    }

    private List<Map<String,Object>> toResultMaps(String query, List<Bm25Index.SearchResult> localCandidates) {
        List<Map<String,Object>> out = new ArrayList<>();
        List<String> qToks = TextUtils.tokenize(query);
        int r = 1;
        for (Bm25Index.SearchResult sr : localCandidates) {
            Bm25Index.Chunk c = index.getChunk(sr.docId);
            String snippet = TextUtils.makeSnippet(c.body, qToks);
            String id = c.source + "::" + Integer.toHexString(Math.abs(c.id.hashCode()));
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", c.title == null || c.title.isBlank() ? c.source : c.title);
            m.put("snippet", snippet);
            m.put("source", c.source);
            m.put("score", sr.score);
            m.put("rank", r++);
            out.add(m);
        }
        return out;
    }

    private String deriveDomainFilter(String domain) {
        if (domain == null) return null;
        String d = domain.toLowerCase(Locale.ROOT);
        if (d.equals("local") || d.equals("web") || d.equals("web+local") || d.equals("rrf")) return null;
        return d; // treat as path fragment
    }

    private boolean useRrf(String domain) {
        String flag = System.getenv().getOrDefault("RAG_USE_RRF", "false");
        boolean envOn = "true".equalsIgnoreCase(flag);
        if (domain == null) return envOn;
        String d = domain.toLowerCase(Locale.ROOT);
        return envOn || d.equals("web+local") || d.equals("rrf");
    }

    private List<Map<String,Object>> mmr(String query, List<Map<String,Object>> items, int topK) {
        // MMR over 5-gram Jaccard; lambda=0.75; candidates from top max(32, k*5)
        int cand = Math.min(items.size(), Math.max(32, topK*5));
        List<Map<String,Object>> candidates = new ArrayList<>(items.subList(0, cand));
        List<Map<String,Object>> selected = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        double lambda = 0.75;

        while (selected.size() < Math.min(topK*3, candidates.size())) {
            int choose = -1;
            double best = -1e9;
            for (int i=0;i<candidates.size();i++) {
                if (used.contains(i)) continue;
                Map<String,Object> m = candidates.get(i);
                double rel = toDouble(m.get("score"));
                double div = 0.0;
                for (Map<String,Object> s : selected) {
                    String a = String.valueOf(m.getOrDefault("snippet",""));
                    String b = String.valueOf(s.getOrDefault("snippet",""));
                    div = Math.max(div, TextUtils.jaccard5Gram(a,b));
                }
                double mmr = lambda * rel - (1-lambda) * div;
                if (mmr > best) { best = mmr; choose = i; }
            }
            if (choose < 0) break;
            used.add(choose);
            selected.add(candidates.get(choose));
        }
        // re-score by original score to keep semantics
        selected.sort((a,b)-> Double.compare(toDouble(b.get("score")), toDouble(a.get("score"))));
        // and rebuild rank
        List<Map<String,Object>> out = new ArrayList<>();
        int r=1;
        for (Map<String,Object> m : selected) {
            Map<String,Object> x = new LinkedHashMap<>(m);
            x.put("rank", r++);
            out.add(x);
        }
        return out;
    }

    private List<Map<String,Object>> applySecondPass(String query, List<Map<String,Object>> items) {
        String mode = System.getenv().getOrDefault("RERANK_2PASS", "off").toLowerCase(Locale.ROOT);
        switch (mode) {
            case "heuristic-ce": {
                CrossEncoder ce = new HeuristicCrossEncoder();
                return applyCe(query, items, ce);
            }
            case "onnx-ce": {
                try {
                    CrossEncoder ce = new OnnxCrossEncoder();
                    return applyCe(query, items, ce);
                } catch (Throwable t) {
                    CrossEncoder ce = new HeuristicCrossEncoder();
                    return applyCe(query, items, ce);
                }
            }
            case "sbert": {
                Embedder embedder = AnnIndexer.selectEmbedder();
                EmbeddingReranker rr = new SbertReranker(embedder);
                return rr.rerank(query, items);
            }
            case "sbert-pre": {
                EmbeddingReranker rr = new SbertPreindexedReranker();
                return rr.rerank(query, items);
            }
            case "colbert-lite": {
                Embedder embedder = AnnIndexer.selectEmbedder();
                EmbeddingReranker rr = new ColbertLiteReranker(embedder);
                return rr.rerank(query, items);
            }
            case "colbert-t": {
                EmbeddingReranker rr = new ColbertReranker(new RemoteTokenEmbedder());
                return rr.rerank(query, items);
            }
            default:
                return items;
        }
    }

    private List<Map<String,Object>> applyCe(String query, List<Map<String,Object>> items, CrossEncoder ce) {
        List<Scored> list = new ArrayList<>();
        for (Map<String,Object> m : items) {
            String title = String.valueOf(m.getOrDefault("title",""));
            String snippet = String.valueOf(m.getOrDefault("snippet",""));
            double ceScore = ce.score(query, title, snippet); // [0,1]
            double base = toDouble(m.get("score"));
            double finalScore = 0.7 * ceScore + 0.3 * Math.log1p(Math.max(0.0, base));
            list.add(new Scored(m, finalScore));
        }
        list.sort((a,b)-> Double.compare(b.s, a.s));
        List<Map<String,Object>> out = new ArrayList<>();
        int rank = 1;
        for (Scored s : list) {
            Map<String,Object> m = new LinkedHashMap<>(s.m);
            m.put("score", s.s);
            m.put("rank", rank++);
            out.add(m);
        }
        return out;
    }

    private static class Scored {
        Map<String, Object> m;
        double s;
        Scored(Map<String, Object> m, double s) {
            this.m = m;
            this.s = s;
        }
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0.0;
        }
    }

    // Convenience overloads
    public List<Map<String,Object>> retrieve(String query) {
        return retrieve(query, null, "local");
    }
    public List<Map<String,Object>> retrieve(String query, Integer topK) {
        return retrieve(query, topK, "local");
    }
}