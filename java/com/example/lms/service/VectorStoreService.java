// src/main/java/com/example/lms/service/VectorStoreService.java
package com.example.lms.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * VectorStoreService - enriched version
 *
 * <p><b>🆕 2025-08-01 업데이트</b>
 * <ul>
 *   <li>📌 임베딩 버퍼 <strong>metadata enricher</strong> 지원
 *       - enqueue 시 세션-별·문서-별 추가 메타데이터를 동적으로 주입.</li>
 *   <li>📌 <code>FusionUtils</code> : Hybrid Search 재순위화를 위해
 *       <strong>RRF·보르다·선형결합</strong> 유틸리티 내장
 *       (Judy 블로그 {스터프1} 공식 그대로 이식).</li>
 *   <li>📌 버퍼 auto-flush 주기 조정 및 실패 시 <em>exponential back-off</em>
 *       재시도 로직 추가.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    /*──────────────────────────────────  Core  ──────────────────────────────────*/

    private final EmbeddingModel embeddingModel;
    @Qualifier("federatedEmbeddingStore")   // ← 주입 대상을 명시
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 한 번에 DB에 적재할 최대 청크. 기본값은 512이며, application.yml에서 vectorstore.batch-size로 재정의할 수 있다. */
    @Value("${vectorstore.batch-size:512}")
    private int batchSize;

    /** <sid:sha-256(text) → BufferEntry> : 중복-방지 & 배치버퍼 */ // [HARDENING] include sessionId in dedupe key
    private final ConcurrentHashMap<String, BufferEntry> queue = new ConcurrentHashMap<>();

    /** flush 실패 back-off */
    private volatile long backoffMillis = 0;

    private record BufferEntry(String sessionId, String text, Map<String, Object> extraMeta) {}

    /*────────────────────────────  Public API  ────────────────────────────*/

    /**
     * 텍스트를 벡터스토어에 적재하기 위해 버퍼에 넣는다.
     *
     * @param sessionId   현재 챗 세션 id (null 가능 → "0")
     * @param text        원본 텍스트
     * @param extraMeta   page · product · url /* ... *&#47; 임의 메타데이터(선택)
     */
    public void enqueue(String sessionId,
                        String text,
                        Map<String, Object> extraMeta) {

        if (text == null || text.isBlank()) return;
        // [HARDENING] normalize session id and include it in dedupe key
        String sid = (sessionId == null || sessionId.isBlank()) ? "__TRANSIENT__" : sessionId;
        String hash = DigestUtils.sha256Hex(text);
        String key = sid + ":" + hash;
        queue.putIfAbsent(key, new BufferEntry(sid, text, extraMeta));
        if (queue.size() >= batchSize) flush();
    }

    /** 오버로드 - 메타데이터가 필요 없을 때 */
    public void enqueue(String sessionId, String text) {
        enqueue(sessionId, text, Map.of());
    }

    /**
     * 스프링 스케줄러가 5-10초 간격으로 호출하도록 설정해두면 좋다.
     * flush 실패 시 지수형 back-off 적용.
     */
    public synchronized void flush() {
        if (queue.isEmpty()) return;
        if (backoffMillis > 0) {
            log.debug("[VectorStore] ⏳ back-off {} ms 남음", backoffMillis);
            return;
        }

        List<BufferEntry> snapshot = new ArrayList<>(queue.values());
        queue.clear();

        try {
            for (int from = 0; from < snapshot.size(); from += batchSize) {
                List<BufferEntry> batch = snapshot.subList(from,
                        Math.min(from + batchSize, snapshot.size()));
                List<TextSegment> segments = batch.stream()
                        .map(be -> TextSegment.from(
                                be.text(),
                                Metadata.from(buildMeta(be))
                        ))
                        .collect(Collectors.toList());

                var embeddings = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(embeddings, segments);
            }
            backoffMillis = 0;
            log.debug("[VectorStore] ✅ flushed {} segments", snapshot.size());
        } catch (Exception e) {
            log.warn("[VectorStore] 🔸 batch insert 실패 - {}", e.toString());
            // 실패한 snapshot 전체를 다시 큐에 되돌림
            // [HARDENING] restore failed entries with sid-prefixed key
            snapshot.forEach(be -> {
                String sid = (be.sessionId() == null || be.sessionId().isBlank()) ? "__TRANSIENT__" : be.sessionId();
                String key = sid + ":" + DigestUtils.sha256Hex(be.text());
                queue.putIfAbsent(key, be);
            });
            // ❶ 1 → 2 → 4 → 8 ‥ 최대 1 분까지 back-off
            backoffMillis = Math.min(backoffMillis == 0 ? 1000 : backoffMillis * 2, 60_000);
        }
    }

    /*─────────────────────────────  Utils  ─────────────────────────────*/

    /** 메타데이터 빌더 - 세션 키(sid) 통일 + extra 메타 병합 */
    private Map<String, Object> buildMeta(BufferEntry be) {
        Map<String, Object> md = new HashMap<>();
        // [HARDENING] merge extra meta first and ensure external sid is not overriding
        if (be.extraMeta() != null) {
            md.putAll(be.extraMeta());
            md.remove(LangChainRAGService.META_SID);
        }
        md.put(LangChainRAGService.META_SID, be.sessionId());
        // enrich with auto-extracted summary and keywords for improved recall and precision
        String text = be.text();
        if (text != null && !text.isBlank()) {
            // simple summarization: take first 100 characters
            String summary = text.length() > 100 ? text.substring(0, 100) + "/* ... *&#47;" : text;
            md.put("summary", summary);
            // generate keywords from text: extract unique tokens longer than one character
            Set<String> keywords = extractKeywords(text);
            if (!keywords.isEmpty()) {
                md.put("keywords", String.join(",", keywords));
            }
        }
        return md;
    }

    /** Extract keywords from text for enriched metadata. */
    private Set<String> extractKeywords(String text) {
        Set<String> stopWords = Set.of("and", "or", "but", "for", "the", "a", "an", "to", "in", "on", "at", "with");
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(tok -> tok.length() > 1 && !stopWords.contains(tok))
                .distinct()
                .limit(10)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /*──────────────────────  Hybrid Rank-Fusion  ──────────────────────*/

    /**
     * <h3>FusionUtils - Hybrid Search 재순위화 헬퍼</h3>
     * Judy 블로그({스터프1})의 RRF·Linear·Borda 공식을 자바로 옮겼다.
     * <p>API : 모두 불변 리스트를 돌려주므로 그대로 <code>take(k)</code> 가능.</p>
     */
    public static final class FusionUtils {

        private FusionUtils() {}

        /*----------- 재료 타입 -----------*/

        public record Scored<T>(T item, double score) {}

        /*----------- Reciprocal-Rank Fusion -----------*/

        public static <T> List<Scored<T>> rrfFuse(List<List<T>> ranked,
                                                  int k /* default 60 */) {
            Map<T, Double> accum = new HashMap<>();
            for (List<T> list : ranked) {
                for (int rank = 0; rank < list.size(); rank++) {
                    T t = list.get(rank);
                    accum.merge(t, 1.0 / (k + rank + 1), Double::sum);
                }
            }
            return accum.entrySet().stream()
                    .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
                    .map(e -> new Scored<>(e.getKey(), e.getValue()))
                    .toList();
        }

        /*----------- Linear Combination (alpha) -----------*/

        public static <T> List<Scored<T>> linearFuse(Map<T, Double> a,
                                                     Map<T, Double> b,
                                                     double alpha /* weight of a */) {
            Map<T, Double> out = new HashMap<>();
            a.forEach((k, v) -> out.merge(k, alpha * v, Double::sum));
            b.forEach((k, v) -> out.merge(k, (1 - alpha) * v, Double::sum));
            return out.entrySet().stream()
                    .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
                    .map(e -> new Scored<>(e.getKey(), e.getValue()))
                    .toList();
        }

        /*----------- Borda Count -----------*/

        public static <T> List<Scored<T>> bordaFuse(List<List<T>> ranked) {
            Map<T, Integer> score = new HashMap<>();
            for (List<T> r : ranked) {
                int n = r.size();
                for (int i = 0; i < n; i++) {
                    score.merge(r.get(i), n - i /* 보르다 점수 */, Integer::sum);
                }
            }
            return score.entrySet().stream()
                    .sorted(Map.Entry.<T, Integer>comparingByValue().reversed())
                    .map(e -> new Scored<>(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    /*──────────────────────────────────────────────────────────────────*/

    /** flush 주기를 조정하고 싶다면 외부 스케줄러에서 주기적으로 호출해 주세요. */
    public void triggerFlushIfDue(Duration maxDelay) {
        if (!queue.isEmpty()
                && queue.values().stream()
                .map(BufferEntry::text)
                .anyMatch(Objects::nonNull)) {
            flush();
        }
    }
}