// src/main/java/com/example/lms/service/VectorStoreService.java
package com.example.lms.service;

import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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

    @Autowired(required = false)
    private VectorPoisonGuard vectorPoisonGuard;

    @Autowired(required = false)
    private VectorScopeGuard vectorScopeGuard;

    @Autowired(required = false)
    private VectorSidService vectorSidService;

    @Autowired(required = false)
    private VectorIngestProtectionService ingestProtectionService;

    @Autowired(required = false)
    private VectorQuarantineDlqService vectorQuarantineDlqService;

    @Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

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

    @Value("${vector.ingest-audit.max:500}")
    private int ingestAuditMax;

    @Value("${vectorstore.require-non-empty-embedding:true}")
    private boolean requireNonEmptyEmbedding;

    @Value("${embedding.provider:ollama}")
    private String embeddingProvider;

    @Value("${embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${embedding.base-url-fallback:}")
    private String embeddingBaseUrlFallback;

    /**
     * Lightweight ingest audit ring-buffer (recent N events).
     * Useful for debugging scope/poison guard routing decisions.
     */
    public record IngestAuditEvent(
            long tsMs,
            String logicalSid,
            String chosenSid,
            String docType,
            String id,
            String explicitId,
            boolean allow,
            String poisonReason,
            String scopeReason,
            String scopeAnchorKey,
            String scopeKind,
            String scopePartKey,
            Double scopeConf
    ) {
    }

    private final Deque<IngestAuditEvent> ingestAudit = new ConcurrentLinkedDeque<>();

    public List<IngestAuditEvent> getIngestAudit(int limit) {
        int n = Math.max(1, Math.min(limit, Math.max(1, ingestAuditMax)));
        List<IngestAuditEvent> out = new ArrayList<>(n);
        int i = 0;
        for (IngestAuditEvent e : ingestAudit) {
            out.add(e);
            if (++i >= n) break;
        }
        return out;
    }

    private void recordAudit(IngestAuditEvent e) {
        if (e == null) return;
        ingestAudit.addFirst(e);
        while (ingestAudit.size() > Math.max(1, ingestAuditMax)) {
            ingestAudit.pollLast();
        }
    }


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

        String sid = (sessionId == null || sessionId.isBlank()) ? "__TRANSIENT__" : sessionId.trim();
        final String logicalSid = sid;
        final String quarantineSid = (vectorSidService != null)
                ? vectorSidService.quarantineSid()
                : "Q";

        Map<String, Object> meta = (extraMeta == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(extraMeta);

        // Normalize legacy keys (camelCase -> snake_case)
        if (!meta.containsKey(VectorMetaKeys.META_SOURCE_TAG) && meta.containsKey("sourceTag")) {
            meta.put(VectorMetaKeys.META_SOURCE_TAG, meta.get("sourceTag"));
        }
        if (!meta.containsKey(VectorMetaKeys.META_DOC_TYPE) && meta.containsKey("docType")) {
            meta.put(VectorMetaKeys.META_DOC_TYPE, meta.get("docType"));
        }
        // Default doc_type for legacy callers
        meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "LEGACY");

        // [VECTOR_POISON] Ingest guard (best-effort)
        // NOTE: allow=false is routed to quarantine sid instead of dropped (phase2 운영 루프).
        String payload = text;
        boolean routedToQuarantine = false;
        String poisonReason = "";
        String scopeReason = "no_scope_guard";
        if (vectorPoisonGuard != null) {
            try {
                VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(sid, payload, meta, "vectorstore.enqueue");
                poisonReason = (dec == null) ? "null_guard_decision" : (dec.reason() == null ? "" : dec.reason());
                if (dec == null) {
                    routedToQuarantine = true;
                    sid = quarantineSid;
                    meta.put(VectorMetaKeys.META_POISON_REASON, "null_guard_decision");
                } else if (!dec.allow()) {
                    routedToQuarantine = true;
                    sid = quarantineSid;
                    if (dec.reason() != null && !dec.reason().isBlank()) {
                        meta.put(VectorMetaKeys.META_POISON_REASON, dec.reason());
                    }
                    if (dec.text() != null && !dec.text().isBlank()) {
                        payload = dec.text();
                    }
                    if (dec.meta() != null && !dec.meta().isEmpty()) {
                        meta = new LinkedHashMap<>(dec.meta());
                    }
                } else {
                    if (dec.text() != null && !dec.text().isBlank()) {
                        payload = dec.text();
                    }
                    if (dec.meta() != null && !dec.meta().isEmpty()) {
                        meta = new LinkedHashMap<>(dec.meta());
                    }
                }
            } catch (Exception ex) {
                routedToQuarantine = true;
                sid = quarantineSid;
                poisonReason = "guard_error:" + ex.getClass().getSimpleName();
                meta.put("poison_guard_error", ex.getClass().getSimpleName());
                meta.putIfAbsent(VectorMetaKeys.META_POISON_REASON, "guard_error:" + ex.getClass().getSimpleName());
                meta.put("poison_guard_error_message", limitLen(String.valueOf(ex.getMessage()), 180));
                log.warn("[VectorStore] poison guard failed; routing to quarantine. sid={} err={}", logicalSid, ex.toString());
            }
        }


        String docType0 = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, "LEGACY")).trim();

        // [VECTOR_SCOPE] Scope labeling/guard (best-effort)
        // - Always enrich scope meta keys (debug/filter material)
        // - If allow=false, route to quarantine sid (do not drop)
        if (vectorScopeGuard != null) {
            try {
                VectorScopeGuard.IngestDecision sd = vectorScopeGuard.inspectIngest(docType0, payload, meta);
                if (sd == null) {
                    scopeReason = "null_scope_decision";
                    meta.putIfAbsent(VectorMetaKeys.META_SCOPE_REASON, scopeReason);
                } else {
                    scopeReason = (sd.reason() == null ? "" : sd.reason());
                    if (sd.metaEnrich() != null && !sd.metaEnrich().isEmpty()) {
                        meta.putAll(sd.metaEnrich());
                    }
                    // keep the most informative reason string
                    if (!scopeReason.isBlank()) {
                        meta.put(VectorMetaKeys.META_SCOPE_REASON, scopeReason);
                    }
                    if (!sd.allow()) {
                        routedToQuarantine = true;
                        sid = quarantineSid;
                        meta.putIfAbsent(VectorMetaKeys.META_POISON_REASON, "scope_guard:" + scopeReason);
                    }
                }
            } catch (Exception ex) {
                scopeReason = "scope_guard_error:" + ex.getClass().getSimpleName();
                meta.putIfAbsent(VectorMetaKeys.META_SCOPE_REASON, scopeReason);
            }
        }

        // [INGEST_PROTECTION] If backend is unhealthy, force quarantine routing.
        boolean forcedByIngestProtection = false;
        String ingestProtectionReason = "";
        try {
            if (!routedToQuarantine
                    && ingestProtectionService != null
                    && ingestProtectionService.isQuarantineActive(logicalSid)) {
                forcedByIngestProtection = true;
                routedToQuarantine = true;
                sid = quarantineSid;

                ingestProtectionReason = ingestProtectionService.quarantineReason(logicalSid);
                if (ingestProtectionReason == null) ingestProtectionReason = "";

                meta.putIfAbsent(VectorMetaKeys.META_QUARANTINED_BY, "INGEST_PROTECTION");
                meta.putIfAbsent(VectorMetaKeys.META_QUARANTINE_REASON,
                        ingestProtectionReason.isBlank() ? "ingest_protection" : ingestProtectionReason);
                meta.putIfAbsent(VectorMetaKeys.META_POISON_REASON,
                        ingestProtectionReason.isBlank()
                                ? "ingest_protection"
                                : ("ingest_protection:" + ingestProtectionReason));

                // audit friendliness
                if (poisonReason == null || poisonReason.isBlank()) {
                    poisonReason = ingestProtectionReason.isBlank()
                            ? "ingest_protection"
                            : ("ingest_protection:" + ingestProtectionReason);
                }
            }
        } catch (Exception ignore) {
            // fail-soft: never block ingest
        }

        if (routedToQuarantine) {
            meta.putIfAbsent(VectorMetaKeys.META_SID_LOGICAL, logicalSid);
            meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, logicalSid);
            meta.put(VectorMetaKeys.META_VERIFIED, "false");
            meta.put("verification_needed", "true");
            meta.putIfAbsent(VectorMetaKeys.META_ORIGIN, "QUARANTINE");
            meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "QUARANTINE");

            // [AUTO-RECOMMEND] feed quarantine signal into sid-rotation advisor (fail-soft)
            try {
                if (sidRotationAdvisor != null) {
                    Object r = meta.get(VectorMetaKeys.META_POISON_REASON);
                    sidRotationAdvisor.recordQuarantine(logicalSid, (r == null ? "" : String.valueOf(r)));
                }
            } catch (Exception ignore) {
                // fail-soft
            }
        }

        String explicitId0 = (explicitId == null) ? null : explicitId.trim();
        String id;
        if (explicitId0 == null || explicitId0.isBlank()) {
            id = sid + ":" + DigestUtils.sha256Hex(payload);
        } else {
            if (forcedByIngestProtection
                    && ingestProtectionService != null
                    && ingestProtectionService.quarantineRewriteStableId()) {
                // When quarantining due to infra issues, do NOT overwrite stable ids in the primary namespace.
                meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_ID, explicitId0);
                meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, logicalSid);
                id = quarantineSid + ":" + DigestUtils.sha1Hex(logicalSid + "|" + explicitId0);
            } else {
                id = explicitId0;
            }
        }

        // [DLQ] Persist ingest-protection quarantines for later redrive (fail-soft).
        if (forcedByIngestProtection && vectorQuarantineDlqService != null) {
            try {
                String originalSidBase = VectorIngestProtectionService.sidBase(logicalSid);
                vectorQuarantineDlqService.recordQuarantined(
                        id,
                        (explicitId0 == null || explicitId0.isBlank()) ? null : explicitId0,
                        logicalSid,
                        originalSidBase,
                        ingestProtectionReason,
                        payload,
                        meta
                );
            } catch (Exception ex) {
                log.warn("[VectorDLQ] record quarantined failed (fail-soft): {}", ex.toString());
            }
        }

        queueRef.get().putIfAbsent(id, new BufferEntry(id, sid, payload, meta));

        // ingest-audit (best-effort, no secrets)
        try {
            boolean allow = !routedToQuarantine;
            recordAudit(new IngestAuditEvent(
                    System.currentTimeMillis(),
                    logicalSid,
                    sid,
                    docType0,
                    id,
                    explicitId,
                    allow,
                    poisonReason,
                    scopeReason,
                    String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_ANCHOR_KEY, "")),
                    String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_KIND, "")),
                    String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SCOPE_PART_KEY, "")),
                    (meta.get(VectorMetaKeys.META_SCOPE_CONF) instanceof Number n ? n.doubleValue() : null)
            ));
        } catch (Exception ignore) {
            // fail-soft
        }

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

                var res = embeddingModel.embedAll(segments);
                var embeds = (res == null) ? null : res.content();
                validateEmbeddingsOrThrow(embeds, segments);
                embeddingStore.addAll(ids, embeds, segments);
            }

            backoffUntilEpochMs = 0L;
            backoffStepMs = 0L;
            lastFlushAtEpochMs = now;
            lastFlushError = null;

            log.debug("[VectorStore] flushed {} segments (store={})", snapshot.size(), embeddingStore.getClass().getSimpleName());
        } catch (Exception e) {
            // [INGEST_PROTECTION] Feed flush failures into the quarantine detector (fail-soft).
            try {
                if (ingestProtectionService != null) {
                    int sample = Math.min(8, snapshot.size());
                    for (int i = 0; i < sample; i++) {
                        ingestProtectionService.recordIfMatches(snapshot.get(i).getValue().sessionId(), e, "vector_flush");
                    }
                    if (sample == 0) {
                        ingestProtectionService.recordIfMatches("", e, "vector_flush");
                    }
                }
            } catch (Exception ignore) {
                // fail-soft
            }
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
        meta.put(VectorMetaKeys.META_SID, be.sessionId());

        // optional dynamic injection
        if (be.extraMeta() != null) {
            meta.putAll(be.extraMeta());
        }

        // Normalize legacy keys (camelCase -> snake_case)
        if (!meta.containsKey(VectorMetaKeys.META_SOURCE_TAG) && meta.containsKey("sourceTag")) {
            meta.put(VectorMetaKeys.META_SOURCE_TAG, meta.get("sourceTag"));
        }
        if (!meta.containsKey("sourceTag") && meta.containsKey(VectorMetaKeys.META_SOURCE_TAG)) {
            meta.put("sourceTag", meta.get(VectorMetaKeys.META_SOURCE_TAG));
        }
        if (!meta.containsKey(VectorMetaKeys.META_DOC_TYPE) && meta.containsKey("docType")) {
            meta.put(VectorMetaKeys.META_DOC_TYPE, meta.get("docType"));
        }
        if (!meta.containsKey("docType") && meta.containsKey(VectorMetaKeys.META_DOC_TYPE)) {
            meta.put("docType", meta.get(VectorMetaKeys.META_DOC_TYPE));
        }
        meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "LEGACY");

        // enforce sid even if extraMeta tried to override
        meta.put(VectorMetaKeys.META_SID, be.sessionId());

        // ensure write failures are visible to the flush loop (DLQ/ingest-protection relies on this)
        meta.put(VectorMetaKeys.META_STRICT_WRITE, "true");

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

    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
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

    private static void validateEmbeddingsOrThrow(java.util.List<dev.langchain4j.data.embedding.Embedding> embeds,
                                                 java.util.List<TextSegment> segments) {
        int expected = (segments == null) ? 0 : segments.size();
        if (expected <= 0) {
            return;
        }
        if (embeds == null || embeds.size() != expected) {
            throw new IllegalStateException("EmbeddingModel returned invalid vectors: expected=" + expected
                    + " got=" + (embeds == null ? "null" : embeds.size()));
        }
        int empty = 0;
        int allZero = 0;
        for (int i = 0; i < expected; i++) {
            dev.langchain4j.data.embedding.Embedding e = embeds.get(i);
            if (e == null || e.vector() == null || e.vector().length == 0) {
                empty++;
                continue;
            }
            if (isAllZero(e.vector())) {
                allZero++;
            }
        }
        if (empty > 0 || allZero > 0) {
            throw new IllegalStateException("EmbeddingModel returned invalid vectors: expected=" + expected
                    + " empty=" + empty + " allZero=" + allZero);
        }
    }

    private static boolean isAllZero(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float x : v) {
            if (x != 0.0f) return false;
        }
        return true;
    }
}
