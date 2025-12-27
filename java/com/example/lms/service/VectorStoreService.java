// src/main/java/com/example/lms/service/VectorStoreService.java
package com.example.lms.service;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * VectorStoreService
 *
 * <p>
 * Hardened buffer/flush orchestration:
 * <ul>
 *   <li>Atomic swap on flush to prevent enqueue loss</li>
 *   <li>Time-based backoff to avoid permanent stall</li>
 *   <li>Stable ids (explicit) support to avoid duplicate accumulation</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final EmbeddingModel embeddingModel;

    @Qualifier("federatedEmbeddingStore")
    private final EmbeddingStore<TextSegment> embeddingStore;

    // MERGE_HOOK:PROJ_AGENT::VECTORSTORE_BUFFER_FLUSH_V2
    private final AtomicReference<ConcurrentHashMap<String, BufferEntry>> queueRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    private volatile long backoffUntilEpochMs = 0L;
    private volatile long backoffStepMs = 0L;
    private volatile long lastFlushAtEpochMs = 0L;
    private volatile long lastFlushAttemptEpochMs = 0L;
    private volatile String lastFlushError = null;

    @Value("${vectorstore.batch-size:512}")
    private int batchSize;

    @Value("${vectorstore.flush-interval-ms:5000}")
    private long flushIntervalMs;

    @Value("${vectorstore.max-backoff-ms:60000}")
    private long maxBackoffMs;

    @Value("${vectorstore.backoff.initial-ms:1000}")
    private long initialBackoffMs;

    /** Buffer entry for embeddings */
    private record BufferEntry(String id, String sessionId, String text, Map<String, Object> extraMeta) {}

    /** Shorthand: enqueue without extra meta. */
    public void enqueue(String sessionId, String text) {
        enqueue(sessionId, text, Map.of());
    }

    /**
     * Enqueue text into buffer (dedupe by (sid,text) hash).
     *
     * <p>Note: to force a stable id (upsert semantics) use {@link #enqueue(String, String, String, Map)}.</p>
     */
    public void enqueue(String sessionId, String text, Map<String, Object> extraMeta) {
        enqueue(null, sessionId, text, extraMeta);
    }

    /**
     * Enqueue with an explicit id.
     *
     * <p>If explicitId is blank, an id is generated from sid+sha256(text).</p>
     */
    public void enqueue(String explicitId, String sessionId, String text, Map<String, Object> extraMeta) {
        if (text == null || text.isBlank()) return;

        String sid = (sessionId == null || sessionId.isBlank()) ? "__TRANSIENT__" : sessionId;
        String id = (explicitId == null || explicitId.isBlank())
                ? (sid + ":" + DigestUtils.sha256Hex(text))
                : explicitId;

        queueRef.get().putIfAbsent(id, new BufferEntry(id, sid, text, extraMeta));

        if (pendingSize() >= batchSize) {
            flush();
        }
    }

    public int pendingSize() {
        return queueRef.get().size();
    }

    /** Buffer/flush diagnostics (safe to expose without secrets). */
    public VectorBufferStats bufferStats() {
        long now = System.currentTimeMillis();
        long remaining = now < backoffUntilEpochMs ? Math.max(0L, backoffUntilEpochMs - now) : 0L;
        return new VectorBufferStats(
                pendingSize(),
                lastFlushAttemptEpochMs,
                lastFlushAtEpochMs,
                remaining,
                lastFlushError
        );
    }

    public record VectorBufferStats(
            int queued,
            long lastAttemptEpochMs,
            long lastSuccessEpochMs,
            long backoffRemainingMillis,
            String lastError
    ) {
    }

    /**
     * Flush if there is data and enough time has elapsed since last successful flush.
     * Used by the scheduler to ensure batchSize 미만에서도 적재가 진행됩니다.
     */
    public void triggerFlushIfDue() {
        if (pendingSize() == 0) return;
        long now = System.currentTimeMillis();
        long last = lastFlushAtEpochMs;
        if (last == 0L || (now - last) >= flushIntervalMs) {
            flush();
        }
    }

    /** Backward compatible signature used by older schedulers. */
    public void triggerFlushIfDue(Duration ignored) {
        triggerFlushIfDue();
    }

    /**
     * Flush buffered segments into EmbeddingStore.
     *
     * <p>
     * - Uses atomic swap to avoid enqueue loss.
     * - Uses time-based backoff to avoid permanent stall.
     * - Uses ids in addAll(ids, embeddings, segments) to prevent duplicates.
     * </p>
     */
    public synchronized void flush() {
        long now = System.currentTimeMillis();
        lastFlushAttemptEpochMs = now;

        if (now < backoffUntilEpochMs) {
            long remain = backoffUntilEpochMs - now;
            log.debug("[VectorStore] flush suppressed by back-off {} ms remaining (queue={})", remain, pendingSize());
            return;
        }

        ConcurrentHashMap<String, BufferEntry> snapshotMap = queueRef.getAndSet(new ConcurrentHashMap<>());
        if (snapshotMap.isEmpty()) return;

        List<Map.Entry<String, BufferEntry>> snapshot = new ArrayList<>(snapshotMap.entrySet());

        try {
            for (int i = 0; i < snapshot.size(); i += batchSize) {
                List<Map.Entry<String, BufferEntry>> batch = snapshot.subList(i, Math.min(i + batchSize, snapshot.size()));
                List<String> ids = batch.stream().map(Map.Entry::getKey).toList();

                List<TextSegment> segments = batch.stream()
                        .map(e -> TextSegment.from(e.getValue().text(), buildMeta(e.getValue())))
                        .collect(Collectors.toList());

                var embeds = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(ids, embeds, segments);
            }

            backoffUntilEpochMs = 0L;
            backoffStepMs = 0L;
            lastFlushAtEpochMs = now;
            lastFlushError = null;

            log.debug("[VectorStore] flushed {} segments (store={})", snapshot.size(), embeddingStore.getClass().getSimpleName());
        } catch (Exception e) {
            // Restore snapshot to current queue (best-effort).
            ConcurrentHashMap<String, BufferEntry> q = queueRef.get();
            for (Map.Entry<String, BufferEntry> e2 : snapshot) {
                q.putIfAbsent(e2.getKey(), e2.getValue());
            }

            lastFlushError = e.toString();

            backoffStepMs = (backoffStepMs <= 0) ? initialBackoffMs : Math.min(backoffStepMs * 2, maxBackoffMs);
            backoffUntilEpochMs = System.currentTimeMillis() + backoffStepMs;

            log.warn("[VectorStore] batch insert failed; backoff={}ms; restored={} (queueNow={}) : {}",
                    backoffStepMs, snapshot.size(), q.size(), e.toString());
        }
    }

    /** 메타데이터 빌더 - 세션 키(sid) 통일 + extra 메타 병합 */
    private Metadata buildMeta(BufferEntry be) {
        Map<String, Object> meta = new LinkedHashMap<>();

        // required key
        meta.put(LangChainRAGService.META_SID, be.sessionId());

        // optional dynamic injection
        if (be.extraMeta() != null) {
            meta.putAll(be.extraMeta());
        }

        // enforce sid even if extraMeta tried to override
        meta.put(LangChainRAGService.META_SID, be.sessionId());

        // add summary/keywords for better search
        String text = be.text();
        if (text != null && text.length() > 100) {
            meta.put("summary", text.substring(0, 100) + "...");
        }
        meta.put("keywords", String.join(",", extractKeywords(text)));

        // LangChain4j Metadata: String/UUID/Integer/Long/Float/Double만 허용
        Map<String, Object> sanitized = sanitizeMetadata(meta);
        return Metadata.from(sanitized);
    }

    private static Map<String, Object> sanitizeMetadata(Map<String, Object> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (e == null || e.getKey() == null || e.getKey().isBlank()) continue;
            Object v = sanitizeMetaValue(e.getValue());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    private static Object sanitizeMetaValue(Object v) {
        if (v == null) return null;
        // 허용되는 스칼라
        if (v instanceof String || v instanceof java.util.UUID
                || v instanceof Integer || v instanceof Long
                || v instanceof Float || v instanceof Double) return v;
        // Boolean → String
        if (v instanceof Boolean b) return b ? "true" : "false";
        // Enum → name()
        if (v instanceof Enum<?> en) return en.name();
        // Number 정규화
        if (v instanceof Number n) {
            double d = n.doubleValue();
            long l = n.longValue();
            if (Math.abs(d - (double) l) < 1e-9) {
                return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
            }
            return d;
        }
        // Collection/Map → 압축 문자열
        if (v instanceof java.util.Collection<?> col) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            int i = 0;
            for (Object item : col) {
                Object sv = sanitizeMetaValue(item);
                if (sv != null) parts.add(String.valueOf(sv));
                if (++i >= 32) break;
            }
            return limitLen(String.join(",", parts), 512);
        }
        if (v instanceof java.util.Map<?, ?> m) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            int i = 0;
            for (java.util.Map.Entry<?, ?> me : m.entrySet()) {
                if (me == null || me.getKey() == null) continue;
                Object mv = sanitizeMetaValue(me.getValue());
                if (mv != null) parts.add(me.getKey() + "=" + mv);
                if (++i >= 32) break;
            }
            return limitLen(String.join(",", parts), 512);
        }
        // Temporal/Date
        if (v instanceof java.time.temporal.TemporalAccessor || v instanceof java.util.Date) {
            return limitLen(String.valueOf(v), 512);
        }
        // Fallback
        return limitLen(String.valueOf(v), 512);
    }

    private static String limitLen(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    // simple stop-words set (English + some common particles)
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "is", "are", "a", "an", "to", "and", "or", "of", "in", "on", "for", "with",
            "this", "that", "it", "as", "at", "be", "by", "from", "we", "you", "i",
            "그리고", "또는", "하지만", "그래서", "있다", "없다"
    );

    private static List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return List.of();

        String lower = text.toLowerCase(Locale.ROOT);
        String[] tokens = lower.split("[^a-z0-9가-힣_]+");

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : tokens) {
            if (t == null) continue;
            String w = t.trim();
            if (w.length() < 2) continue;
            if (STOP_WORDS.contains(w)) continue;
            out.add(w);
            if (out.size() >= 10) break;
        }
        return new ArrayList<>(out);
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
}
