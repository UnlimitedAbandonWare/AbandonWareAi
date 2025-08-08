package com.example.lms.service.rag;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryComplexityGate;   // ← 추가
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
import java.util.Map;              // ✅ Map.of 사용
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.rag.handler.RetrievalHandler;   // 🔹 missing import
/* 🔹 missing! – Chain SPI */
import com.example.lms.service.rag.handler.RetrievalHandler;
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {
    /* -------------- Chain-of-Responsibility -------------- */
    private final RetrievalHandler handlerChain;          // ★ 새 체인 엔트리

    /* 기존 개별 리트리버 필드는 핸들러 내부로 이동했으므로 제거합니다 */
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> gameEmbeddingStore;

    /** 리트리버 전용 병렬 실행 풀 (CPU 코어 수 ≥ 2) */
    /** 리트리버 전용 병렬 실행 풀 (CPU 코어 수 ≥ 2) */
    private final ExecutorService retrieverExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));

    @Value("${rag.search.top-k:5}")
    private int topK;
    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    // 🔴 검색정책 메타데이터 키 정의

    // 🔴 검색정책 메타데이터 키 정의  (중복 선언 제거 후 하나만 남김)
    private static final String META_MAX_PARALLEL     = "maxParallel";     // Integer
    private static final String META_DEDUPE_KEY       = "dedupeKey";       // "text"|"url"|"hash"
    private static final String META_OFFICIAL_DOMAINS = "officialDomains"; // List<String> ✅

    @Override
    public List<Content> retrieve(Query query) {
        try {
            List<Content> acc = new ArrayList<>();
            handlerChain.handle(query, acc);                 // 🎯 단일 호출
            return acc.isEmpty()
                    ? List.of(Content.from("[검색 결과 없음]"))
                    : acc;
        } catch (Exception e) {
            log.error("[Hybrid] chain 처리 실패", e);
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
            // 누적식(=)과 증감식(i) 빠져 있었던 부분 복구
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
    /* ------------------------------------------------------------
     * ⬇️ 추가: 간편 생성자 (RetrievalHandler만 필요)
     * ------------------------------------------------------------ */
    public HybridRetriever(RetrievalHandler handlerChain) {
        this(handlerChain, null, null);   // embeddingModel / store 없이도 생성
    }

    /* 원래 Lombok이 만든 full-args 생성자가 그대로 함께 존재합니다 ↑ */
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
            int s = a + 6, e = text.indexOf('\"', s);
            if (e > s) return text.substring(s, e);     // ✅ URL 반환
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
            if (d != null && !d.isBlank() && url.contains(d.trim())) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------
     * 타입 안전 Metadata put 및 메타데이터 복사 헬퍼 함수 추가
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

    private static void copyMetadata(Metadata src, Metadata dst) {
        if (src == null || dst == null) return;
        Map<String, Object> m = toMap(src);
        if (m == null || m.isEmpty()) return;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            putTyped(dst, e.getKey(), e.getValue());
        }
    }
    /**
     * 검색 결과를 최종 처리하여 반환:
     * - dedupeKey 기준 중복 제거 (텍스트/URL/hash)
     * - 공식 도메인 결과에 가중치 보정 (+0.2 점수)
     * - 점수 기준 정렬 후 topK만 선택
     */
    private List<Content> finalizeResults(List<Content> raw, String dedupeKey, List<String> officialDomains) {
        // 1) 중복 제거
        Map<String, Content> uniq = new LinkedHashMap<>();
        for (Content c : raw) {
            if (c == null) continue;
            // 본문 텍스트 추출 (안전하게)
            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());
            String key = switch (dedupeKey) {
                case "url"  -> Optional.ofNullable(extractUrl(text)).orElse(text);
                case "hash" -> Integer.toHexString(text.hashCode());
                default     -> text;
            };
            uniq.putIfAbsent(key, c); // 첫 등장 컨텐츠만 유지
        }
        // 2) 스코어 계산 및 공식 출처 보너스 적용
        record Scored(Content content, double score) {}
        List<Scored> scoredList = new ArrayList<>();
        int rank = 0;
        for (Content c : uniq.values()) {
            rank++;
            double score = 1.0 / rank; // 기본 역순위 점수
            Object sObj = Optional.ofNullable(c.metadata()).map(m -> m.get("score")).orElse(null);
            if (sObj instanceof Number n) {
                score = n.doubleValue(); // 외부 점수가 있으면 해당 값 사용
            }
            String text = Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
            String url = extractUrl(text);
            if (isOfficial(url, officialDomains)) {
                score += 0.20; // 공식 도메인 보너스 가산
            }
            scoredList.add(new Scored(c, score));
        }
        // 3) 점수 내림차순 정렬 및 Top-K 선택
        scoredList.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scoredList.stream()
                .limit(topK)
                .map(Scored::content)
                .collect(Collectors.toList());
    }
}
