// src/main/java/com/example/lms/service/VectorStoreService.java
package com.example.lms.service;

import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.example.lms.service.vector.VectorIngestProtectionService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.service.vector.VectorShadowMergeDlqService;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.TraceLogger;
import com.example.lms.trace.TraceSnapshotStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private VectorShadowMergeDlqService vectorShadowMergeDlqService;
    @Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

    @Autowired(required = false)
    private TraceSnapshotStore traceSnapshotStore;

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

    @Value("${vectorstore.shadow.enabled:true}")
    private boolean shadowWriteEnabled;

    @Value("${vectorstore.shadow.require-explicit-id:true}")
    private boolean shadowRequireExplicitId;

    @Value("${vectorstore.quarantine.rewrite-stable-id:true}")
    private boolean quarantineRewriteStableId;
    @Value("${vectorstore.flush-interval-ms:5000}")
    private long flushIntervalMs;

    /**
     * Flush grouping strategy (performance vs correlation trade-off).
     *
     * <ul>
     *   <li><b>trace</b>: group by (sid, traceId/requestId) — best x-request-id correlation (default)</li>
     *   <li><b>session</b>: group by sid only — best batching/perf</li>
     *   <li><b>session_bucket</b>: group by (sid, time-bucket) — batching with bounded group size</li>
     *   <li><b>auto</b>: debug entries => trace, otherwise => session</li>
     * </ul>
     */
    @Value("${vectorstore.flush.grouping:trace}")
    private String flushGrouping;

    /** Max number of distinct requestIds to include in breadcrumbs when grouping != trace. */
    @Value("${vectorstore.flush.grouping.max-request-ids:8}")
    private int flushGroupingMaxRequestIds;

    /**
     * Time bucket size (ms) used for synthetic flush trace ids and optional session-bucket grouping.
     *
     * <p>When grouping is not {@code trace}, we synthesize a trace id in the form
     * {@code vflush:&lt;sid&gt;:&lt;bucket&gt;} so downstream logs/snapshots remain correlatable.
     * The {@code bucket} is {@code floor(epochMs / bucketMs)}.</p>
     */
    @Value("${vectorstore.flush.bucket-ms:5000}")
    private long flushBucketMs;

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
    /**
     * Buffer entry with request correlation context.
     *
     * <p>Why carry trace/request ids here?</p>
     * <ul>
     *   <li>Vector ingest happens on request threads, while flush may happen on a scheduler thread.</li>
     *   <li>To preserve x-request-id/sessionId correlation across the ingest→buffer→flush boundary,
     *       we keep minimal correlation fields on each buffered item.</li>
     * </ul>
     */
    private record BufferEntry(
            String id,
            String sessionId,
            String text,
            Map<String, Object> extraMeta,
            long createdAtMs,
            String traceId,
            String requestId,
            boolean debug
    ) {
    }

    /** Groups buffered items by correlation context so flush logs remain attributable. */
    private record FlushGroupKey(String sessionId, String traceId, String requestId, boolean debug) {
    }

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

        // [SHADOW_WRITE] Stage risky/unverified content into a shadow sid (prevents hub-vector pollution).
        boolean shadowed = false;
        String shadowVectorId = null;
        // Keep reason for breadcrumbs/logging outside of the inner shouldShadow block
        // so later trace events can reference it safely.
        String shadowReason = null;

        if (!routedToQuarantine
                && shadowWriteEnabled
                && vectorShadowMergeDlqService != null
                && !truthy(meta.get(VectorMetaKeys.META_SHADOW_BYPASS))) {

            boolean requestedShadow = truthy(meta.get(VectorMetaKeys.META_SHADOW_WRITE));

            boolean verificationNeeded = truthy(meta.get(VectorMetaKeys.META_VERIFICATION_NEEDED))
                    || truthy(meta.get("verification_needed"))
                    || "false".equalsIgnoreCase(String.valueOf(meta.getOrDefault(VectorMetaKeys.META_VERIFIED, "true")));

            int citationCount = safeInt(meta.get(VectorMetaKeys.META_CITATION_COUNT), 0);
            String srcTag = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_SOURCE_TAG, ""));
            boolean riskySystemKbNoCitations = "KB".equalsIgnoreCase(docType0)
                    && "SYSTEM".equalsIgnoreCase(srcTag)
                    && citationCount <= 0;

            boolean shouldShadow = requestedShadow || verificationNeeded || riskySystemKbNoCitations;

            if (shouldShadow) {
                boolean hasStableId = explicitId0 != null && !explicitId0.isBlank();
                if (!shadowRequireExplicitId || hasStableId) {
                    shadowReason = requestedShadow ? "requested"
                            : (verificationNeeded ? "unverified" : "risky_kb_no_citations");

                    String runId = VectorSidService.normalizeRunId(MDC.get("trace"));
                    String shadowTargetSid = sid;
                    String shadowSid = (vectorSidService != null)
                            ? vectorSidService.shadowSid(shadowTargetSid, runId)
                            : (shadowTargetSid + "~S" + runId);

                    String stableId0 = hasStableId ? explicitId0 : "";
                    shadowVectorId = "S:" + DigestUtils.sha1Hex(shadowSid + "|" + shadowTargetSid + "|" + stableId0 + "|" + runId);

                    if (verificationNeeded || riskySystemKbNoCitations) {
                        meta.put(VectorMetaKeys.META_VERIFIED, "false");
                        meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
                    } else {
                        meta.putIfAbsent(VectorMetaKeys.META_VERIFIED, "true");
                        meta.putIfAbsent(VectorMetaKeys.META_VERIFICATION_NEEDED, "false");
                    }

                    meta.put(VectorMetaKeys.META_SHADOW_WRITE, "true");
                    meta.put(VectorMetaKeys.META_SHADOW_REASON, shadowReason);
                    meta.put(VectorMetaKeys.META_SHADOW_RUN_ID, runId);
                    meta.put(VectorMetaKeys.META_SHADOW_TARGET_SID, shadowTargetSid);
                    meta.put(VectorMetaKeys.META_SHADOW_SID, shadowSid);
                    meta.put(VectorMetaKeys.META_SHADOW_VECTOR_ID, shadowVectorId);
                    meta.put(VectorMetaKeys.META_SHADOW_STATE, "STAGED");

                    if (hasStableId) {
                        meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_ID, explicitId0);
                    }
                    meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, logicalSid);

                    // Persist DLQ row for later merge to stable id (fail-soft).
                    try {
                        vectorShadowMergeDlqService.recordStaged(
                                shadowVectorId,
                                hasStableId ? explicitId0 : null,
                                logicalSid,
                                shadowTargetSid,
                                shadowSid,
                                runId,
                                shadowReason,
                                payload,
                                meta
                        );
                    } catch (Exception ex) {
                        log.warn("[VectorShadowDLQ] record staged failed (fail-soft): {}", ex.toString());
                    }

                    // Route this write to the shadow sid, and force id to the shadow vector id.
                    shadowed = true;
                    sid = shadowSid;
                } else {
                    // Can't shadow without a stable id; still mark as unverified to allow downstream guards.
                    meta.put(VectorMetaKeys.META_VERIFIED, "false");
                    meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
                }
            }
        }

        String id;
        if (shadowed && shadowVectorId != null && !shadowVectorId.isBlank()) {
            id = shadowVectorId;
        } else if (explicitId0 == null || explicitId0.isBlank()) {
            id = sid + ":" + DigestUtils.sha256Hex(payload);
        } else {
            boolean rewriteStableId = false;
            if (routedToQuarantine && quarantineRewriteStableId) {
                rewriteStableId = true;
            }
            if (forcedByIngestProtection
                    && ingestProtectionService != null
                    && ingestProtectionService.quarantineRewriteStableId()) {
                rewriteStableId = true;
            }

            if (rewriteStableId) {
                // When routing to quarantine, do NOT overwrite stable ids in the primary namespace.
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

        // Carry request correlation through the ingest→buffer→flush boundary.
        String traceId0 = firstNonBlank(MDC.get("traceId"), MDC.get("trace"), MDC.get("x-request-id"));
        if (traceId0 == null || traceId0.isBlank()) {
            traceId0 = UUID.randomUUID().toString();
        }
        String requestId0 = firstNonBlank(MDC.get("x-request-id"), traceId0);
        boolean dbg0 = truthy(MDC.get("dbgSearch"));

        long createdAtMs = System.currentTimeMillis();
        queueRef.get().putIfAbsent(id, new BufferEntry(id, sid, payload, meta, createdAtMs, traceId0, requestId0, dbg0));

        // Breadcrumbs (safe) to debug merge-boundary issues.
        try {
            TraceStore.put("ml.vector.ingest.id", id);
            TraceStore.put("ml.vector.ingest.sid", sid);
            TraceStore.put("ml.vector.ingest.traceId", traceId0);
            TraceStore.put("ml.vector.ingest.route", routedToQuarantine ? "quarantine" : (shadowed ? "shadow" : "primary"));
            if (shadowed && shadowReason != null) TraceStore.put("ml.vector.ingest.shadow.reason", shadowReason);
        } catch (Throwable ignore) {
            // fail-soft
        }

        if (dbg0 || routedToQuarantine || shadowed) {
            try {
                TraceLogger.emit("vector_enqueue", "vector",
                        java.util.Map.of(
                                "id", id,
                                "sid", sid,
                                "route", routedToQuarantine ? "quarantine" : (shadowed ? "shadow" : "primary"),
                                "shadowReason", (shadowReason == null ? "" : shadowReason),
                                "payloadLen", payload == null ? 0 : payload.length()
                        ));
            } catch (Throwable ignore) {
                // fail-soft
            }
        }

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

        // Group by correlation context so flush logs + trace events remain attributable.
        // NOTE: grouping strategy is tunable via vectorstore.flush.grouping.
        String grouping = (flushGrouping == null) ? "trace" : flushGrouping.trim().toLowerCase(java.util.Locale.ROOT);
        if (grouping.isBlank()) grouping = "trace";

        Map<FlushGroupKey, List<Map.Entry<String, BufferEntry>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, BufferEntry> e : snapshot) {
            if (e == null || e.getValue() == null) continue;
            BufferEntry be = e.getValue();
            String sid = be.sessionId();
            String traceId0 = firstNonBlank(be.traceId(), be.requestId());
            if (traceId0 == null || traceId0.isBlank()) {
                traceId0 = UUID.randomUUID().toString();
            }
            String requestId0 = firstNonBlank(be.requestId(), traceId0);

            // Grouping policy:
            // - trace         : (sid, traceId/requestId)
            // - session       : (sid)
            // - session_bucket: (sid, time-bucket)
            // - auto          : dbgSearch entries => trace, otherwise => session
            String gm = grouping;
            if ("auto".equals(gm) && !be.debug()) {
                gm = "session";
            }
            if ("bucket".equals(gm)) {
                gm = "session_bucket";
            }

            FlushGroupKey k;
            if ("session_bucket".equals(gm)) {
                long bucket = bucketOf(be.createdAtMs());
                String vtid = vflushTraceId(sid, bucket);
                k = new FlushGroupKey(sid, vtid, vtid, be.debug());
            } else if ("session".equals(gm)) {
                k = new FlushGroupKey(sid, "", "", be.debug());
            } else {
                k = new FlushGroupKey(sid, traceId0, requestId0, be.debug());
            }
            groups.computeIfAbsent(k, __ -> new ArrayList<>()).add(e);
        }

        java.util.Set<String> okIds = new java.util.HashSet<>();

        try {
            for (Map.Entry<FlushGroupKey, List<Map.Entry<String, BufferEntry>>> grp : groups.entrySet()) {
                FlushGroupKey k = grp.getKey();
                List<Map.Entry<String, BufferEntry>> items = grp.getValue();
                if (k == null || items == null || items.isEmpty()) continue;

                // When grouping != trace, create a synthetic flush-trace id so downstream logs/snapshots remain correlatable.
                // Format: vflush:<sid>:<bucket>
                String groupTraceId = firstNonBlank(k.traceId(), k.requestId());
                long groupBucket = bucketOf(now);
                Long parsedBucket = tryParseVflushBucket(groupTraceId);
                if (parsedBucket != null) {
                    groupBucket = parsedBucket;
                }
                if (groupTraceId == null || groupTraceId.isBlank()) {
                    groupTraceId = vflushTraceId(k.sessionId(), groupBucket);
                }
                String groupRequestId = firstNonBlank(k.requestId(), groupTraceId);
                if (groupRequestId == null || groupRequestId.isBlank()) {
                    groupRequestId = groupTraceId;
                }

                try (TraceContext ignored = TraceContext.attach(k.sessionId(), groupTraceId)) {
                    // Ensure x-request-id/sessionId correlation for downstream logs.
                    if (groupRequestId != null && !groupRequestId.isBlank()) {
                        MDC.put("x-request-id", groupRequestId);
                    }
                    if (k.debug()) {
                        MDC.put("dbgSearch", "1");
                    } else {
                        MDC.remove("dbgSearch");
                    }

                    // Merge-boundary breadcrumbs.
                    try {
                        TraceStore.put("ml.vector.flush.grouping", grouping);
                        TraceStore.put("ml.vector.flush.group.size", items.size());
                        TraceStore.put("ml.vector.flush.traceId", groupTraceId);
                        TraceStore.put("ml.vector.flush.requestId", groupRequestId);
                        TraceStore.put("ml.vector.flush.bucket", groupBucket);

                        // When grouping by session (or auto->session), keep a compact sample of requestIds
                        // so we can correlate flush batches back to originating HTTP requests.
                        if (!"trace".equals(grouping)) {
                            java.util.LinkedHashSet<String> reqIds = new java.util.LinkedHashSet<>();
                            java.util.LinkedHashSet<String> traceIds = new java.util.LinkedHashSet<>();
                            for (Map.Entry<String, BufferEntry> it : items) {
                                if (it == null || it.getValue() == null) continue;
                                String rid = firstNonBlank(it.getValue().requestId(), it.getValue().traceId());
                                if (rid != null && !rid.isBlank()) reqIds.add(rid);
                                String tid = firstNonBlank(it.getValue().traceId(), it.getValue().requestId());
                                if (tid != null && !tid.isBlank()) traceIds.add(tid);
                                if (reqIds.size() >= Math.max(1, flushGroupingMaxRequestIds)
                                        && traceIds.size() >= Math.max(1, flushGroupingMaxRequestIds)) {
                                    break;
                                }
                            }
                            if (!reqIds.isEmpty()) {
                                TraceStore.put("ml.vector.flush.requestIds.sample", String.join(",", reqIds));
                            }
                            if (!traceIds.isEmpty()) {
                                TraceStore.put("ml.vector.flush.traceIds.sample", String.join(",", traceIds));
                            }
                        }
                    } catch (Throwable ignore) {
                    }

                    // TRACE_JSON breadcrumbs (captured by TraceSnapshotStore if configured).
                    try {
                        TraceLogger.emit("vector_flush_group_start", "vector",
                                java.util.Map.of(
                                        "grouping", grouping,
                                        "sid", k.sessionId(),
                                        "traceId", groupTraceId,
                                        "bucket", groupBucket,
                                        "count", items.size(),
                                        "debug", k.debug()
                                ));
                    } catch (Throwable ignore) {
                    }

                    int okInGroup = 0;

                    try {
                        for (int i = 0; i < items.size(); i += batchSize) {
                            List<Map.Entry<String, BufferEntry>> batch = items.subList(i, Math.min(i + batchSize, items.size()));
                            List<String> ids = batch.stream().map(Map.Entry::getKey).toList();

                            List<TextSegment> segments = batch.stream()
                                    .map(en -> TextSegment.from(en.getValue().text(), buildMeta(en.getValue())))
                                    .collect(Collectors.toList());

                            var res = embeddingModel.embedAll(segments);
                            var embeds = (res == null) ? null : res.content();
                            validateEmbeddingsOrThrow(embeds, segments);
                            embeddingStore.addAll(ids, embeds, segments);
                            okIds.addAll(ids);
                            okInGroup += ids.size();

                            if (k.debug()) {
                                try {
                                    TraceLogger.emit("vector_flush_batch", "vector",
                                            java.util.Map.of(
                                                    "count", ids.size(),
                                                    "store", embeddingStore.getClass().getSimpleName(),
                                                    "groupSize", items.size()
                                            ));
                                } catch (Throwable ignore) {
                                }
                            }
                        }

                        // TRACE_JSON breadcrumbs (captured by TraceSnapshotStore if configured).
                        try {
                            TraceLogger.emit("vector_flush_group_done", "vector",
                                    java.util.Map.of(
                                            "grouping", grouping,
                                            "sid", k.sessionId(),
                                            "traceId", groupTraceId,
                                            "bucket", groupBucket,
                                            "count", items.size(),
                                            "ok", okInGroup,
                                            "debug", k.debug()
                                    ));
                        } catch (Throwable ignore) {
                        }
                    } catch (Exception e) {
                        // [INGEST_PROTECTION] Feed flush failures into the quarantine detector (fail-soft).
                        try {
                            if (ingestProtectionService != null) {
                                ingestProtectionService.recordIfMatches(k.sessionId(), e, "vector_flush");
                            }
                        } catch (Exception ignore) {
                            // fail-soft
                        }

                        // Breadcrumb + snapshot for post-mortem.
                        try {
                            TraceStore.put("ml.vector.flush.error", String.valueOf(e));
                        } catch (Throwable ignore) {
                        }
                        try {
                            if (traceSnapshotStore != null) {
                                traceSnapshotStore.captureCurrent("vector_flush_error", "SCHED", "vector.flush", null, e);
                            }
                        } catch (Throwable ignore) {
                        }

                        throw e;
                    }

                }
            }

            backoffUntilEpochMs = 0L;
            backoffStepMs = 0L;
            lastFlushAtEpochMs = now;
            lastFlushError = null;

            log.debug("[VectorStore] flushed {} segments in {} groups (store={})",
                    snapshot.size(), groups.size(), embeddingStore.getClass().getSimpleName());
        } catch (Exception e) {
            // Restore snapshot to current queue (best-effort), excluding already-flushed ids.
            ConcurrentHashMap<String, BufferEntry> q = queueRef.get();
            int restored = 0;
            for (Map.Entry<String, BufferEntry> e2 : snapshot) {
                if (e2 == null) continue;
                String id = e2.getKey();
                if (id != null && okIds.contains(id)) continue;
                q.putIfAbsent(id, e2.getValue());
                restored++;
            }

            lastFlushError = e.toString();

            backoffStepMs = (backoffStepMs <= 0) ? initialBackoffMs : Math.min(backoffStepMs * 2, maxBackoffMs);
            backoffUntilEpochMs = System.currentTimeMillis() + backoffStepMs;

            log.warn("[VectorStore] batch insert failed; backoff={}ms; restored={} (queueNow={}) : {}",
                    backoffStepMs, restored, q.size(), e.toString());
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

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private long bucketOf(long epochMs) {
        long ms = flushBucketMs;
        if (ms <= 0L) return epochMs;
        return Math.max(0L, epochMs) / Math.max(1L, ms);
    }

    private static String vflushTraceId(String sid, long bucket) {
        String s = (sid == null || sid.isBlank()) ? "-" : sid.trim();
        return "vflush:" + s + ":" + bucket;
    }

    private static Long tryParseVflushBucket(String traceId) {
        if (traceId == null) return null;
        String t = traceId.trim();
        if (!t.startsWith("vflush:")) return null;
        int last = t.lastIndexOf(':');
        if (last <= 0 || last >= t.length() - 1) return null;
        try {
            return Long.parseLong(t.substring(last + 1));
        } catch (Exception ignore) {
            return null;
        }
    }



    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return s.equalsIgnoreCase("true")
                || s.equalsIgnoreCase("1")
                || s.equalsIgnoreCase("yes")
                || s.equalsIgnoreCase("y")
                || s.equalsIgnoreCase("on");
    }

    private static int safeInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return def;
        }
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
