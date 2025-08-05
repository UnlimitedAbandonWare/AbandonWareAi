package com.example.lms.service.rag;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

import com.example.lms.service.reinforcement.RewardScoringEngine;

@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {

    private static final double GAME_SIM_THRESHOLD = 0.3;

    // 🔴 검색정책 메타키(필요 시 ChatService에서 Query.metadata에 넣어 전달)
    private static final String META_ALLOWED_DOMAINS   = "allowedDomains";   // List<String>
    private static final String META_MAX_PARALLEL      = "maxParallel";      // Integer
    private static final String META_DEDUPE_KEY        = "dedupeKey";        // String ("text"|"url"|"hash")
    private static final String META_OFFICIAL_DOMAINS  = "officialDomains";  // List<String>

    @Value("${rag.search.top-k:5}")
    private int topK;

    private final SelfAskWebSearchRetriever selfAskRetriever;
    private final AnalyzeWebSearchRetriever analyzeRetriever;
    private final WebSearchRetriever webSearchRetriever;
    /** 리트리버 전용 병렬 실행 풀 (CPU 코어 수 ≥ 2) */
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
        /* ────────── 0️⃣  메타데이터 파싱 ────────── */
        String sessionKey = Optional.ofNullable(query.metadata())
                .map(HybridRetriever::toMap)
                .map(md -> md.get(LangChainRAGService.META_SID))
                .map(Object::toString)
                .orElse(null);

        // ➕ 검색정책 메타데이터 수신 (없으면 안전 기본값)
        Map<String, Object> md = Optional.ofNullable(query.metadata())
                .map(HybridRetriever::toMap)
                .orElse(Map.of());
        @SuppressWarnings("unchecked")
        List<String> allowedDomains = (List<String>) md.getOrDefault(META_ALLOWED_DOMAINS, List.of());
        @SuppressWarnings("unchecked")
        List<String> officialDomains = (List<String>) md.getOrDefault(META_OFFICIAL_DOMAINS, allowedDomains);
        int maxParallel = Optional.ofNullable((Integer) md.get(META_MAX_PARALLEL)).orElse(3);
        String dedupeKey = Optional.ofNullable((String) md.get(META_DEDUPE_KEY)).orElse("text");

        /* ────────── 1️⃣  모든 리트리버 병렬 호출 ────────── */
        List<CompletableFuture<List<Content>>> futures = List.of(
                CompletableFuture.supplyAsync(() -> selfAskRetriever.retrieve(query),  retrieverExecutor),
                CompletableFuture.supplyAsync(() -> analyzeRetriever.retrieve(query),  retrieverExecutor),
                CompletableFuture.supplyAsync(() -> webSearchRetriever.retrieve(query), retrieverExecutor),
                CompletableFuture.supplyAsync(() -> ragService                                   // Pinecone
                        .asContentRetriever(pineconeIndexName)
                        .retrieve(query), retrieverExecutor),
                CompletableFuture.supplyAsync(() -> {                                           // 게임 네임스페이스
                    ContentRetriever game = EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(gameEmbeddingStore)
                            .embeddingModel(embeddingModel)
                            .maxResults(3)
                            .build();
                    return game.retrieve(query).stream()
                            .filter(c -> cosineSimilarity(query.text(), c.textSegment().text()) >= GAME_SIM_THRESHOLD)
                            .toList();
                }, retrieverExecutor)
        );

        /* ────────── 2️⃣  결과 취합 & Borda-Fusion ────────── */
        List<List<Content>> rankedLists = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        var fused = RewardScoringEngine.bordaFuse(rankedLists);

        /* fused → 상위 topK 리스트 확보 */
        List<Content> merged = fused.stream()
                .map(Map.Entry::getKey)
                .limit(Math.max(topK * 2, 10))          // 여유 있게 확보 후 dedupe
                .collect(Collectors.toList());

        /* dedupe 준비 */
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        if (merged.isEmpty()) {
            return List.of(Content.from("[검색 결과 없음]"));
        }

        // 4) 디디플 & 출처태깅(OFFICIAL/COMMUNITY)
        List<Content> tagged = new ArrayList<>();
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        for (Content c : merged) {
            String key = switch (dedupeKey) {
                case "url"  -> extractUrl(c.toString());
                case "hash" -> Integer.toHexString(c.toString().hashCode());
                default     -> c.textSegment().text();
            };
            if (!dedupe.add(key)) continue;

            String url = extractUrl(c.toString());
            String sourceType = isOfficial(url, officialDomains) ? "OFFICIAL" : "COMMUNITY";

            try {
                Metadata metaNew = new Metadata();
                // 기존 메타 안전 복사
                copyMetadata(c.textSegment().metadata(), metaNew);
                // 새 필드 주입 (타입 안전)
                putTyped(metaNew, "sourceType", sourceType);
                putTyped(metaNew, "url", url == null ? "" : url);

                TextSegment ts = new TextSegment(c.textSegment().text(), metaNew);
                tagged.add(Content.from(ts));
            } catch (Throwable ignoreAll) {
                // 폴백: 메타 주입 실패하면 수동 태깅
                tagged.add(Content.from("[" + sourceType + "] "+  c.textSegment().text()));
            }
        }

        // 5) 최종 개수 제한 – tagged 기준
        List<Content> result;
        if (topK > 0 && tagged.size() > topK) {
            result = new ArrayList<>(tagged.subList(0, topK));
        } else {
            result = tagged;
        }

        log.debug("[HybridRetriever] 통합 검색 완료, 결과 수: {} (중복 제거 후, limit={})",
                result.size(), topK);
        return result;
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

    // ➕ 타입 안전 put & 메타 복사 헬퍼
    private static void copyMetadata(Metadata src, Metadata dst) {
        if (src == null || dst == null) return;
        Map<String, Object> m = toMap(src);   // Metadata가 asMap/map 없으면 빈 맵 반환
        if (m == null || m.isEmpty()) return;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            putTyped(dst, e.getKey(), e.getValue());
        }
    }

    private static void putTyped(Metadata md, String k, Object v) {
        if (v == null) return;
        if (v instanceof String s)          { md.put(k, s); return; }
        if (v instanceof java.util.UUID u)  { md.put(k, u); return; }
        if (v instanceof Integer i)         { md.put(k, i); return; }
        if (v instanceof Long l)            { md.put(k, l); return; }
        if (v instanceof Float f)           { md.put(k, f); return; }
        if (v instanceof Double d)          { md.put(k, d); return; }
        // 나머지는 문자열로 강제
        md.put(k, String.valueOf(v));
    }
}
