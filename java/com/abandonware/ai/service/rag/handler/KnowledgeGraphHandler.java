package com.abandonware.ai.service.rag.handler;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Minimal-but-working KG handler for com.abandonware.* chain.
 *
 * 목적:
 * - 이전에는 항상 empty 반환 -> RAG 파이프라인에서 KG 경로가 실질적으로 무력화
 * - 폐쇄망/오프라인에서도 동작할 수 있도록 "내부 정적 사전 + 간단 룰" 기반으로 최소 지식 제공
 *
 * 주의:
 * - 여기의 정적 KG는 '정확한 최신 사실'을 담는 것이 아니라, 시스템/도메인 개념어를 보조하는 수준입니다.
 * - 추후 Neo4j/DB/사내 KB 등으로 교체/확장 가능하도록 lookup(...) 시그니처와 return 타입은 유지합니다.
 */
@Component
public class KnowledgeGraphHandler {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final class KgEntry {
        final String key;
        final String title;
        final String description;
        final List<String> aliases;
        final List<String> related;

        KgEntry(String key, String title, String description, List<String> aliases, List<String> related) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.aliases = aliases;
            this.related = related;
        }

        String renderSnippet() {
            StringBuilder sb = new StringBuilder();
            sb.append(description == null ? "" : description.trim());
            if (related != null && !related.isEmpty()) {
                sb.append("\n\n관련 키워드: ");
                for (int i = 0; i < related.size(); i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(related.get(i));
                }
            }
            return sb.toString().trim();
        }
    }

    /**
     * 아주 작은 정적 KG: 운영/디버깅/설명에 도움 되는 개념어 중심.
     * (필요 시 자유롭게 추가)
     */
    private static final Map<String, KgEntry> STATIC = new LinkedHashMap<>();
    static {
        put("rag",
                "RAG (Retrieval-Augmented Generation)",
                "LLM 응답 생성 전에 관련 컨텍스트(문서/스니펫/메모리)를 검색해 붙여 주는 패턴.",
                List.of("rag", "retrieval augmented generation", "검색 증강", "검색증강"),
                List.of("bm25", "vector store", "rerank", "kg"));

        put("bm25",
                "BM25 (lexical retrieval)",
                "형태소/토큰 기반 키워드 검색 점수(BM25)를 이용한 로컬/전통적 검색.",
                List.of("bm25", "lexical", "키워드", "lucene"),
                List.of("rag", "index", "snippet"));

        put("vector-store",
                "Vector Store",
                "임베딩 벡터를 저장하고 유사도 기반 검색을 수행하는 저장소.",
                List.of("vector store", "벡터스토어", "pgvector", "redis", "upstash"),
                List.of("embedding", "rag", "rerank"));

        put("rerank",
                "Re-rank",
                "1차 검색 결과를 더 정확한 모델/룰로 재정렬해 상위 컨텍스트 품질을 높임.",
                List.of("rerank", "re-rank", "재정렬", "cross-encoder", "bi-encoder"),
                List.of("rag", "bm25", "vector store"));

        put("kg",
                "Knowledge Graph (KG)",
                "개념/엔티티/관계를 그래프로 표현해 탐색/확장 검색에 활용.",
                List.of("kg", "knowledge graph", "지식그래프", "그래프"),
                List.of("entity", "relation", "rag"));

        put("naver-search",
                "Naver Search",
                "웹 검색(네이버)을 통해 최신/외부 정보를 가져오는 경로.",
                List.of("naver", "네이버", "search", "웹검색"),
                List.of("web", "snippet", "rag"));

        put("circuit-breaker",
                "Circuit Breaker",
                "외부 호출 실패가 연쇄적으로 확산되는 것을 막는 패턴(Resilience4j 등으로 구현).",
                List.of("circuit breaker", "resilience4j", "cb", "서킷브레이커"),
                List.of("timeout", "bulkhead", "fallback"));

        put("sse",
                "SSE (Server-Sent Events)",
                "서버가 클라이언트로 이벤트를 스트리밍하는 방식(토큰/로그/상태 중간 보고 등).",
                List.of("sse", "server-sent", "event stream", "스트리밍"),
                List.of("telemetry", "logging"));

        put("abandonware-chain",
                "abandonware retrieval chain",
                "com.abandonware.* 네임스페이스의 간이 검색 체인(웹/BM25/KG 결합).",
                List.of("abandonware", "abandonware chain", "probe"),
                List.of("web", "bm25", "kg"));
    }

    private static void put(String key, String title, String desc, List<String> aliases, List<String> related) {
        STATIC.put(key, new KgEntry(key, title, desc, aliases == null ? List.of() : aliases,
                related == null ? List.of() : related));
    }

    private final Semaphore limiter;
    private final long timeoutMs;
    private final int topKDefault;

    public KnowledgeGraphHandler(
            @Value("${kg.max-concurrency:2}") int maxConcurrency,
            @Value("${kg.timeout-ms:1200}") long timeoutMs,
            @Value("${kg.top-k:5}") int topKDefault) {
        this.limiter = new Semaphore(Math.max(1, maxConcurrency));
        this.timeoutMs = timeoutMs;
        this.topKDefault = topKDefault;
    }

    public List<ContextSlice> lookup(String query, int topK) {
        final String qRaw = query == null ? "" : query.trim();
        if (qRaw.isEmpty()) {
            return Collections.emptyList();
        }

        boolean acquired = false;
        try {
            try {
                acquired = limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
            if (!acquired) {
                return Collections.emptyList();
            }

            final int k = topK > 0 ? topK : topKDefault;
            final String q = normalize(qRaw);
            final Set<String> tokens = tokenize(q);

            List<Scored<KgEntry>> scored = new ArrayList<>();
            for (KgEntry e : STATIC.values()) {
                double score = score(q, tokens, e);
                if (score > 0.0) {
                    scored.add(new Scored<>(e, score));
                }
            }

            if (scored.isEmpty()) {
                return Collections.emptyList();
            }

            scored.sort(Comparator.comparingDouble((Scored<KgEntry> s) -> s.score).reversed());
            int limit = Math.min(k, scored.size());

            List<ContextSlice> out = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                KgEntry e = scored.get(i).value;
                float s = (float) scored.get(i).score;

                ContextSlice cs = new ContextSlice();
                cs.setId("kg:" + e.key);
                cs.setTitle("KG: " + e.title);
                cs.setSnippet(e.renderSnippet());
                cs.setSource("kg");
                cs.setScore(s);
                cs.setRank(i + 1);
                out.add(cs);
            }
            return out;
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }

    private static final class Scored<T> {
        final T value;
        final double score;

        Scored(T value, double score) {
            this.value = value;
            this.score = score;
        }
    }

    private static double score(String normalizedQuery, Set<String> tokens, KgEntry e) {
        if (e.aliases == null || e.aliases.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;

        // 강한 매칭: alias 전체 문자열 포함
        for (String a : e.aliases) {
            String na = normalize(a);
            if (!na.isEmpty() && normalizedQuery.contains(na)) {
                score += 2.5;
            }
        }

        // 약한 매칭: 토큰 겹침
        if (!tokens.isEmpty()) {
            Set<String> aliasTokens = new HashSet<>();
            for (String a : e.aliases) {
                aliasTokens.addAll(tokenize(normalize(a)));
            }
            for (String t : tokens) {
                if (t.length() < 2)
                    continue;
                if (aliasTokens.contains(t)) {
                    score += 1.0;
                }
            }
        }

        // 소폭 보정: query 길이가 길고 1개만 걸리면 과대평가 방지
        if (normalizedQuery.length() > 40 && score > 0.0) {
            score *= 0.9;
        }

        return score;
    }

    private static String normalize(String s) {
        if (s == null)
            return "";
        String t = s.toLowerCase();
        // Keep letters/digits/whitespace. (Hangul is alphabetic in Unicode)
        t = t.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ");
        t = MULTI_SPACE.matcher(t).replaceAll(" ").trim();
        return t;
    }

    private static Set<String> tokenize(String normalized) {
        if (normalized == null || normalized.isBlank())
            return Collections.emptySet();
        String[] parts = normalized.split(" ");
        Set<String> out = new HashSet<>();
        for (String p : parts) {
            if (p == null)
                continue;
            String t = p.trim();
            if (t.isEmpty())
                continue;
            out.add(t);
        }
        return out;
    }
}
