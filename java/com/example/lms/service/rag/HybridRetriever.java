package com.example.lms.service.rag;
// 삭제: com.example.lms.service.rag.handler.RetrievalHandler (패키지 미존재
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryComplexityGate;   // ← 추가
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import com.example.lms.service.NaverSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
// duplicate imports removed
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {

    private static final double GAME_SIM_THRESHOLD = 0.3;

    // 🔴 검색정책 메타키(필요 시 ChatService에서 Query.metadata에 넣어 전달)
    private static final String META_ALLOWED_DOMAINS = "allowedDomains";   // List<String>
    private static final String META_MAX_PARALLEL = "maxParallel";      // Integer
    private static final String META_DEDUPE_KEY = "dedupeKey";        // String ("text"|"url"|"hash")
    private static final String META_OFFICIAL_DOMAINS = "officialDomains";  // List<String>

    @Value("${rag.search.top-k:5}")
    private int topK;
    private final RetrievalHandler handlerChain;          // ★ 새 체인 엔트리
    private final ReciprocalRankFuser fuser;              // ★ RRF 융합기
    private final SelfAskWebSearchRetriever selfAskRetriever;
    private final AnalyzeWebSearchRetriever analyzeRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final QueryComplexityGate gate;   // ← 추가 (게이트 DI)
    /**
     * 리트리버 전용 병렬 실행 풀 (CPU 코어 수 ≥ 2)
     */
    private final ExecutorService retrieverExecutor =
            Executors.newFixedThreadPool(Math.max(2,
                    Runtime.getRuntime().availableProcessors()));

    private final LangChainRAGService ragService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> gameEmbeddingStore;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

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

        return finalizeResults(new ArrayList<>(mergedContents), dedupeKey, officialDomains);

}

        /** 다중 쿼리 병렬 검색  RRF 융합 */
    public List<Content> retrieveAll(List<String> queries, int limit) {
        if (queries == null || queries.isEmpty()) {
            return List.of(Content.from("[검색 결과 없음]"));
        }

        try {
            // 쿼리별로 handlerChain을 병렬 실행하여 후보 리스트 수집
            List<List<Content>> results = queries.parallelStream()
                    .map(q -> {
                        List<Content> acc = new ArrayList<>();
                        try {
                            handlerChain.handle(Query.from(q), acc);
                        } catch (Exception e) {
                            log.warn("[Hybrid] handler 실패: {}", q, e);
                        }
                        return acc;
                    })
                    .toList();

            // RRF 융합 후 상위 limit 반환
            return fuser.fuse(results, Math.max(1, limit));
        } catch (Exception e) {
            log.error("[Hybrid] retrieveAll 실패", e);
            return List.of(Content.from("[검색 오류]"));
        }
    }

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
                nq  += qVec[i] * qVec[i];
                nd  += dVec[i] * dVec[i];
            }
            if (nq == 0 || nd == 0) return 0d;
            return dot / (Math.sqrt(nq) * Math.sqrt(nd) + 1e-9);
        } catch (Exception e) {
            return 0d;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
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

    /* ------------------------------------------------------------------
     * ① putTyped ― 타입‑안전 Metadata put   (누락되어 컴파일 오류 발생)
     * ------------------------------------------------------------------ */
    private static void putTyped(Metadata md, String key, Object val) {
        if (md == null || key == null || val == null) return;
        if (val instanceof String s)       md.put(key, s);
        else if (val instanceof Integer i) md.put(key, i);
        else if (val instanceof Long l)    md.put(key, l);
        else if (val instanceof Double d)  md.put(key, d);
        else if (val instanceof Float f)   md.put(key, f);
        else if (val instanceof Boolean b) md.put(key, b.toString());
        else if (val instanceof Number n)  md.put(key, n.doubleValue());
        else                               md.put(key, String.valueOf(val));
    }


    // ➕ 타입 안전 put & 메타 복사 헬퍼
    private static void copyMetadata(Metadata src, Metadata dst) {
        if (src == null || dst == null) return;
        Map<String, Object> m = toMap(src);   // Metadata가 asMap/map 없으면 빈 맵 반환
        if (m == null || m.isEmpty()) return;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            putTyped(dst, e.getKey(), e.getValue());
        }
    }

    /* ──────────────────────────────
     * (-)  컴파일 오류 해소: 누락된 finalizeResults 구현
     * (+)  ‑ 중복 제거(dedupeKey=text|url|hash)
     *     ‑ 공식 출처 bonus(+0.2) 가산 후 스코어 내림차순
     *     ‑ topK 제한  및  메타데이터 안전 복사
     * ────────────────────────────── */
    private List<Content> finalizeResults(List<Content> raw,
                                          String dedupeKey,
                                          List<String> officialDomains) {

        /* 1) 중복 제거 */
        Map<String, Content> uniq = new LinkedHashMap<>();
        for (Content c : raw) {
            if (c == null) continue;

            /* 안전하게 본문 텍스트 확보 */
            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            String key = switch (dedupeKey) {
                case "url"  -> Optional.ofNullable(extractUrl(text)).orElse(text);
                case "hash" -> Integer.toHexString(text.hashCode());
                default     -> text;                             // "text"
            };
            uniq.putIfAbsent(key, c);   // 첫 등장만 유지
        }

        /* 2) 스코어 계산 + 공식 출처 가중치 */
        record Scored(Content content, double score) {}

        List<Scored> scored = new ArrayList<>();
        int rank = 0;
        for (Content c : uniq.values()) {
            rank++;                                // 낮은 rank = 높은 우선순위

            double base = 1.0 / rank;              // 기본 점수(역순위)
            Object sObj = Optional.ofNullable(c.metadata())
                    .map(m -> m.get("score"))
                    .orElse(null);
            if (sObj instanceof Number n) {
                base = n.doubleValue();            // 외부 점수 있으면 우선
            }

            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());
            String url  = extractUrl(text);
            if (isOfficial(url, officialDomains)) {
                base += 0.20;                      // (+) 공식 도메인 bonus
            }

            scored.add(new Scored(c, base));
        }

        /* 3) 점수 내림차순 정렬 후 top‑K 컷 */
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        return scored.stream()
                .limit(topK)                  // 클래스 필드 topK 사용
                .map(Scored::content)
                .collect(Collectors.toList());
    }
    public interface RetrievalHandler {
        void handle(Query query, List<Content> out);
    }
}
