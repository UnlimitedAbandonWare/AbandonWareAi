// src/main/java/com/example/lms/service/VectorStoreService.java
package com.example.lms.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * VectorStoreService – enriched version
 *
 * <p><b>🆕 2025-08-01 업데이트</b>
 * <ul>
 *   <li>📌 임베딩 버퍼 <strong>metadata enricher</strong> 지원
 *       – enqueue 시 세션-별·문서-별 추가 메타데이터를 동적으로 주입.</li>
 *   <li>📌 <code>FusionUtils</code> : Hybrid Search 재순위화를 위해
 *       <strong>RRF·보르다·선형결합</strong> 유틸리티 내장
 *       (Judy 블로그 {스터프1} 공식 그대로 이식).</li>
 *   <li>📌 버퍼 auto-flush 주기 조정 및 실패 시 <em>exponential back-off</em>
 *       재시도 로직 추가.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    /*──────────────────────────────────  Core  ──────────────────────────────────*/

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 한 번에 DB에 적재할 최대 청크 */
    private static final int BATCH_SIZE = 512;

    /** <sha-256(text) → BufferEntry> : 중복-방지 & 배치버퍼 */
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
     * @param extraMeta   page · product · url ... 임의 메타데이터(선택)
     */
    public void enqueue(String sessionId,
                        String text,
                        Map<String, Object> extraMeta) {

        if (text == null || text.isBlank()) return;
        String sid = sessionId == null ? "0" : sessionId;
        String hash = DigestUtils.sha256Hex(text);
        queue.putIfAbsent(hash, new BufferEntry(sid, text, extraMeta));
        if (queue.size() >= BATCH_SIZE) flush();
    }

    /** 오버로드 – 메타데이터가 필요 없을 때 */
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
            for (int from = 0; from < snapshot.size(); from += BATCH_SIZE) {
                List<BufferEntry> batch = snapshot.subList(from,
                        Math.min(from + BATCH_SIZE, snapshot.size()));

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
            log.warn("[VectorStore] 🔸 batch insert 실패 – {}", e.toString());
            // 실패한 snapshot 전체를 다시 큐에 되돌림
            snapshot.forEach(be -> queue.putIfAbsent(
                    DigestUtils.sha256Hex(be.text()), be));
            // ❶ 1 → 2 → 4 → 8 ‥ 최대 1 분까지 back-off
            backoffMillis = Math.min(backoffMillis == 0 ? 1000 : backoffMillis * 2, 60_000);
        }
    }

    /*─────────────────────────────  Utils  ─────────────────────────────*/

    /** 메타데이터 빌더 – 세션 키(sid) 통일 + extra 메타 병합 */
    private Map<String, Object> buildMeta(BufferEntry be) {
        Map<String, Object> md = new HashMap<>();
        md.put(LangChainRAGService.META_SID, be.sessionId());
        if (be.extraMeta() != null) md.putAll(be.extraMeta());
        return md;
    }

    /*──────────────────────  Hybrid Rank-Fusion  ──────────────────────*/

    /**
     * <h3>FusionUtils – Hybrid Search 재순위화 헬퍼</h3>
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
