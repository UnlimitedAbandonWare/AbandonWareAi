package com.example.lms.service.rag;

import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.util.SoftmaxUtil;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
// imports
import com.example.lms.service.rag.rerank.LightWeightRanker;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ForkJoinPool;

import com.example.lms.service.rag.auth.AuthorityScorer;

@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {
    // fields (다른 final 필드들과 같은 위치)
    private final LightWeightRanker lightWeightRanker;
    private final AuthorityScorer authorityScorer;
    private static final double GAME_SIM_THRESHOLD = 0.3;

    // 메타키 (필요 시 Query.metadata에 실어 전달)
    private static final String META_ALLOWED_DOMAINS = "allowedDomains";   // List<String>
    private static final String META_MAX_PARALLEL = "maxParallel";      // Integer
    private static final String META_DEDUPE_KEY = "dedupeKey";        // "text" | "url" | "hash"
    private static final String META_OFFICIAL_DOMAINS = "officialDomains";  // List<String>

    @Value("${rag.search.top-k:5}")
    private int topK;

    // 체인 & 융합기
    private final RetrievalHandler handlerChain;
    private final ReciprocalRankFuser fuser;
    private final AnswerQualityEvaluator qualityEvaluator;
    private final SelfAskPlanner selfAskPlanner;
    private final RelevanceScoringService relevanceScoringService;
    // 리트리버들
    private final SelfAskWebSearchRetriever selfAskRetriever;
    private final AnalyzeWebSearchRetriever analyzeRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final QueryComplexityGate gate;

    // RAG/임베딩
    private final LangChainRAGService ragService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> gameEmbeddingStore;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${hybrid.debug.sequential:false}")
    private boolean debugSequential;
    @Value("${hybrid.progressive.quality-min-docs:4}")
    private int qualityMinDocs;
    @Value("${hybrid.progressive.quality-min-score:0.62}")
    private double qualityMinScore;
    @Value("${hybrid.max-parallel:3}")
    private int maxParallel;

    @Value("${hybrid.min-relatedness:0.4}")  //  관련도 필터 컷오프
    private double minRelatedness;
    // ★ 융합 모드: rrf(기본) | softmax
    private String fusionMode;
    // ★ softmax 융합 온도
    @Value("${retrieval.fusion.softmax.temperature:1.0}")
    private double fusionTemperature;

    @Override
    public List<Content> retrieve(Query query) {

        // 0) 메타 파싱
        String sessionKey = Optional.ofNullable(query)
                .map(Query::metadata)
                .map(HybridRetriever::toMap)
                .map(md -> md.get(LangChainRAGService.META_SID))
                .map(Object::toString)
                .orElse(null);

        Map<String, Object> md = Optional.ofNullable(query)
                .map(Query::metadata)
                .map(HybridRetriever::toMap)
                .orElse(Map.of());

        @SuppressWarnings("unchecked")
        List<String> allowedDomains =
                (List<String>) md.getOrDefault(META_ALLOWED_DOMAINS, List.of());
        @SuppressWarnings("unchecked")
        List<String> officialDomains =
                (List<String>) md.getOrDefault(META_OFFICIAL_DOMAINS, allowedDomains);

        int maxParallel = Optional.ofNullable((Integer) md.get(META_MAX_PARALLEL)).orElse(3);
        String dedupeKey = (String) md.getOrDefault(META_DEDUPE_KEY, "text");

        LinkedHashSet<Content> mergedContents = new LinkedHashSet<>();

        // 1) 난이도 게이팅
        final String q = (query != null && query.text() != null) ? query.text().strip() : "";
        QueryComplexityGate.Level level = gate.assess(q);
        log.debug("[Hybrid] level={} q='{}'", level, q);

        switch (level) {
            case SIMPLE -> {
                // 웹 우선, 부족하면 벡터
                mergedContents.addAll(webSearchRetriever.retrieve(query));
                if (mergedContents.size() < topK) {
                    ContentRetriever pine = ragService.asContentRetriever(pineconeIndexName);
                    mergedContents.addAll(pine.retrieve(query));
                }
            }
            case AMBIGUOUS -> {
                mergedContents.addAll(analyzeRetriever.retrieve(query));
                if (mergedContents.size() < topK) mergedContents.addAll(webSearchRetriever.retrieve(query));
                if (mergedContents.size() < topK) {
                    ContentRetriever pine = ragService.asContentRetriever(pineconeIndexName);
                    mergedContents.addAll(pine.retrieve(query));
                }
            }
            case COMPLEX -> {
                mergedContents.addAll(selfAskRetriever.retrieve(query));
                if (mergedContents.size() < topK) mergedContents.addAll(analyzeRetriever.retrieve(query));
                if (mergedContents.size() < topK) mergedContents.addAll(webSearchRetriever.retrieve(query));
                if (mergedContents.size() < topK) {
                    ContentRetriever pine = ragService.asContentRetriever(pineconeIndexName);
                    mergedContents.addAll(pine.retrieve(query));
                }
            }
        }

        // 최종 정제 후 반환
        return finalizeResults(new ArrayList<>(mergedContents), dedupeKey, officialDomains, q);
    }

    /**
     * Progressive retrieval:
     * 1) Local RAG 우선 → 품질 충분 시 조기 종료
     * 2) 미흡 시 Self‑Ask(1~2개)로 정제된 웹 검색만 수행
     */
    public List<Content> retrieveProgressive(String question, String sessionKey, int limit) {
        if (question == null || question.isBlank()) {
            return List.of(Content.from("[빈 질의]"));
        }
        final int top = Math.max(1, limit);

        try {
            // 1) 로컬 RAG 우선
            ContentRetriever pine = ragService.asContentRetriever(pineconeIndexName);
            List<Content> local = pine.retrieve(Query.from(question));

            if (qualityEvaluator != null && qualityEvaluator.isSufficient(question, local, qualityMinDocs, qualityMinScore)) {
                log.info("[Hybrid] Local RAG sufficient → skip web (sid={}, q='{}')", sessionKey, question);
                List<Content> out = finalizeResults(new ArrayList<>(local), "text", java.util.Collections.emptyList(), question);
                return out.size() > top ? out.subList(0, top) : out;
            }

            // 2) Self‑Ask로 1~2개 핵심 질의 생성 → 위생 필터
            List<String> planned = (selfAskPlanner != null) ? selfAskPlanner.plan(question, 2) : List.of(question);
            List<String> queries = QueryHygieneFilter.sanitize(planned, 2, 0.80);

            if (queries.isEmpty()) queries = List.of(question);

            // 3) 필요한 쿼리만 순차 처리 → 융합
            List<List<Content>> buckets = new ArrayList<>();
            for (String q : queries) {
                List<Content> acc = new ArrayList<>();
                try {
                    handlerChain.handle(Query.from(q), acc);
                } catch (Exception e) {
                    log.warn("[Hybrid] handler 실패: {}", e.toString());
                }
                buckets.add(acc);
            }


            // 융합 및 최종 정제 후 상위 top 반환
            List<Content> fused = "softmax".equalsIgnoreCase(fusionMode)
                    ? fuseWithSoftmax(buckets, top, question) // ★ 대안 융합
                    : fuser.fuse(buckets, top);               // 기본 RRF
            List<Content> combined = new ArrayList<>(local); // 'local'은 이 메소드 상단에서 이미 정의되어 있어야 합니다.
            combined.addAll(fused);

            List<Content> out = finalizeResults(combined, "text", java.util.Collections.emptyList(), question);
            return out.size() > top ? out.subList(0, top) : out;

        } catch (Exception e) {
            log.error("[Hybrid] retrieveProgressive 실패(sid={}, q='{}')", sessionKey, question, e);
            return List.of(Content.from("[검색 오류]"));
        }
    }


/**
 * 다중 쿼리 병렬 검색  RRF 융합
 */

    /**
     * 다중 쿼리 병렬 검색 + RRF 융합
     */
    public List<Content> retrieveAll(List<String> queries, int limit) {
        if (queries == null || queries.isEmpty()) {
            return List.of(Content.from("[검색 결과 없음]"));
        }

        try {
            List<List<Content>> results;
            if (debugSequential) {
                log.warn("[Hybrid] debug.sequential=true → handlerChain 순차 실행");
                results = new ArrayList<>();
                for (String q : queries) {
                    List<Content> acc = new ArrayList<>();
                    try {
                        handlerChain.handle(Query.from(q), acc);
                    } catch (Exception e) {
                        log.warn("[Hybrid] handler 실패: {}", q, e);
                    }
                    results.add(acc);
                }
            } else {
                // 기본: 제한 병렬 실행 (공용 풀 사용 금지)
                ForkJoinPool pool = new ForkJoinPool(Math.max(1, maxParallel));
                try {
                    results = pool.submit(() ->
                            queries.parallelStream()
                                    .map(q -> {
                                        List<Content> acc = new ArrayList<>();
                                        try {
                                            handlerChain.handle(Query.from(q), acc);
                                        } catch (Exception e) {
                                            log.warn("[Hybrid] handler 실패: {}", q, e);
                                        }
                                        return acc;
                                    })
                                    .toList()
                    ).join();
                } finally {
                    pool.shutdown();
                }
            }
            // RRF or Softmax 융합 후 상위 limit 반환
            if ("softmax".equalsIgnoreCase(fusionMode)) {
                String q0 = queries.get(0); // 대표 질의(간단 근사)
                return fuseWithSoftmax(results, Math.max(1, limit), q0);
            }
            return fuser.fuse(results, Math.max(1, limit));
        } catch (Exception e) { // retrieveAll 종료
            log.error("[Hybrid] retrieveAll 실패", e);
            return List.of(Content.from("[검색 오류]"));
        }
        // 클래스 종료는 파일 말미로 이동 (헬퍼 메서드 포함)

    } // retrieveAll 끝

    // ───────────────────────────── 헬퍼들 ─────────────────────────────


    // ───────────────────────────── 헬퍼들 ─────────────────────────────

    /**
     * (옵션) 코사인 유사도 — 필요 시 사용
     */
    private double cosineSimilarity(String q, String doc) {
        try {
            var qVec = embeddingModel.embed(q).content().vector();
            var dVec = embeddingModel.embed(doc).content().vector();
            if (qVec.length != dVec.length) {
                throw new IllegalArgumentException("Embedding dimension mismatch");
            }
            double dot = 0, nq = 0, nd = 0;
            for (int i = 0; i < qVec.length; i++) {
                dot += qVec[i] * dVec[i];
                nq += qVec[i] * qVec[i];
                nd += dVec[i] * dVec[i];
            }
            if (nq == 0 || nd == 0) return 0d;
            return dot / (Math.sqrt(nq) * Math.sqrt(nd) + 1e-9);
        } catch (Exception e) {
            return 0d;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
        if (meta == null) return Map.of();
        try {
            Method m = meta.getClass().getMethod("asMap");
            return (Map<String, Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                Method m = meta.getClass().getMethod("map");
                return (Map<String, Object>) m.invoke(meta);
            } catch (Exception ex) {
                return Map.of();
            }
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        int a = text.indexOf("href=\"");
        if (a >= 0) {
            int s = a + 6, e = text.indexOf('"', s);
            if (e > s) return text.substring(s, e);
        }
        int http = text.indexOf("http");
        if (http >= 0) {
            int sp = text.indexOf(' ', http);
            return sp > http ? text.substring(http, sp) : text.substring(http);
        }
        return null;
    }

    private static boolean isOfficial(String url, List<String> officialDomains) {
        if (url == null || officialDomains == null) return false;
        for (String d : officialDomains) {
            if (d != null && !d.isBlank() && url.contains(d.trim())) return true;
        }
        return false;
    }

    /**
     * 최종 정제:
     * - dedupeKey 기준 중복 제거
     * - 공식 도메인 보너스(+0.20)
     * - 점수 내림차순 정렬 후 topK 반환
     */
    private List<Content> finalizeResults(List<Content> raw,
                                          String dedupeKey,
                                          List<String> officialDomains,
                                          String queryText) {

        // 1) 중복 제거 + 저관련 필터
        Map<String, Content> uniq = new LinkedHashMap<>();
        for (Content c : raw) {
            if (c == null) continue;

            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            double rel = 0.0;
            try {
                rel = relevanceScoringService.relatedness(
                        Optional.ofNullable(queryText).orElse(""),
                        text
                );
            } catch (Exception ignore) { }
            if (rel < minRelatedness) continue;

            String key;
            switch (dedupeKey) {
                case "url" -> key = Optional.ofNullable(extractUrl(text)).orElse(text);
                case "hash" -> key = Integer.toHexString(text.hashCode());
                default -> key = text; // "text"
            }
            uniq.putIfAbsent(key, c);
        }

        // 2) 경량 1차 랭킹 (없으면 candidates 그대로 사용)
        List<Content> candidates = new ArrayList<>(uniq.values());
        List<Content> firstPass = (lightWeightRanker != null)
                ? lightWeightRanker.rank(
                candidates,
                Optional.ofNullable(queryText).orElse(""),
                Math.max(topK * 2, 20)
        )
                : candidates;

        // 3) 정밀 스코어링 + 정렬
        class Scored {
            final Content content;
            final double score;
            Scored(Content content, double score) { this.content = content; this.score = score; }
        }
        List<Scored> scored = new ArrayList<>();
        int rank = 0;

        for (Content c : firstPass) {
            rank++;
            double base = 1.0 / rank;

            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            String url = extractUrl(text);
            // NEW: Authority weight (0.0 ~ 1.0), 도메인 테이블  +휴리스틱
            double authority = authorityScorer != null ? authorityScorer.weightFor(url) : 0.5;

            double rel = 0.0;
            try {
                rel = relevanceScoringService.relatedness(
                        Optional.ofNullable(queryText).orElse(""),
                        text
                );
            } catch (Exception ignore) { }

                        // NEW: 최종 점수 = 0.6*관련도 + 0.3*기본랭크 + 0.1*Authority
                               double finalScore = (0.6 * rel) + (0.3 * base) + (0.1 * authority);
            scored.add(new Scored(c, finalScore));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream()
                .limit(topK)
                .map(s -> s.content)
                .collect(Collectors.toList());
    }
    // ───────────────────────────── NEW: Softmax 융합(단일 정의만 유지) ─────────────────────────────
    /** 여러 버킷의 결과를 하나로 모아 점수(logit)를 만들고 softmax로 정규화한 뒤 상위 N을 고른다. */
    private List<Content> fuseWithSoftmax(List<List<Content>> buckets, int limit, String queryText) {
        Map<String, Content> keeper = new LinkedHashMap<>();
        Map<String, Double>  logit  = new LinkedHashMap<>();

        int bIdx = 0;
        for (List<Content> bucket : buckets) {
            if (bucket == null) continue;
            int rank = 0;
            for (Content c : bucket) {
                rank++;
                String text = Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
                String key  = Integer.toHexString(text.hashCode()); // 간단 dedupe
                String url  = extractUrl(text);
                double authority = (authorityScorer != null) ? authorityScorer.weightFor(url) : 0.5;
                double related   = 0.0;
                try { related = relevanceScoringService.relatedness(Optional.ofNullable(queryText).orElse(""), text); } catch (Exception ignore) {}
                double base      = 1.0 / (rank + 0.0);           // 상위 랭크 가중
                double bucketW   = 1.0 / (bIdx + 1.0);           // 앞선 버킷 약간 우대
                double l = (0.6 * related) + (0.1 * authority) + (0.3 * base * bucketW);

                keeper.putIfAbsent(key, c);
                logit.merge(key, l, Math::max); // 같은 문서는 가장 높은 logit만 유지
            }
            bIdx++;
        }
        if (logit.isEmpty()) return List.of();

        // softmax 정규화(수치 안정화 포함) 후 확률 높은 순으로 정렬
        String[] keys = logit.keySet().toArray(new String[0]);
        double[] arr  = logit.values().stream().mapToDouble(d -> d).toArray();
        double[] p    = SoftmaxUtil.softmax(arr, fusionTemperature);

        // 확률 내림차순 상위 limit
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < p.length; i++) idx.add(i);
        idx.sort((i, j) -> Double.compare(p[j], p[i]));

        java.util.List<Content> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(limit, idx.size()); i++) {
            out.add(keeper.get(keys[idx.get(i)]));
        }
        return out;
    }


}