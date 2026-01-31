package com.example.lms.service.vector;

import com.example.lms.entity.VectorShadowMergeDlq;
import com.example.lms.repository.VectorShadowMergeDlqRepository;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Handles recording and merging of shadow-write staged vectors.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Fail-soft recording (do not break ingestion).</li>
 *   <li>Delayed merge into the stable/global index, guarded by quality checks.</li>
 *   <li>Idempotent: stableVectorId is upserted into the target sid.</li>
 * </ul>
 */
@ConditionalOnBean(VectorShadowMergeDlqRepository.class)
@Service
@RequiredArgsConstructor
public class VectorShadowMergeDlqService {
    private static final Logger log = LoggerFactory.getLogger(VectorShadowMergeDlqService.class);

    private final VectorShadowMergeDlqRepository repo;
    private final ObjectMapper om = new ObjectMapper();

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    @Qualifier("federatedEmbeddingStore")
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired(required = false)
    private VectorPoisonGuard poisonGuard;

    @Autowired(required = false)
    private VectorScopeGuard scopeGuard;

    @Value("${vectorstore.shadow.merge.batch-size:5}")
    private int mergeBatchSize;

    @Value("${vectorstore.shadow.merge.max-attempts:8}")
    private int maxAttempts;

    @Value("${vectorstore.shadow.merge.retry-seconds:300}")
    private long retrySeconds;

    @Value("${vectorstore.shadow.merge.blocked-retry-seconds:1800}")
    private long blockedRetrySeconds;

    public long pendingCount() {
        try {
            return repo.countByStatus(VectorShadowMergeDlq.Status.PENDING);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Record a staged shadow vector (best-effort).
     */
    public void recordStaged(String shadowVectorId,
                             String stableVectorId,
                             String logicalSid,
                             String targetSid,
                             String shadowSid,
                             String shadowRunId,
                             String shadowReason,
                             String payload,
                             Map<String, Object> meta) {
        if (shadowVectorId == null || shadowVectorId.isBlank()) return;

        String dedupeKey = DigestUtils.sha1Hex(
                safe(shadowVectorId) + "|" + safe(stableVectorId) + "|" + safe(shadowSid) + "|" + safe(targetSid)
        );

        try {
            if (repo.findByDedupeKey(dedupeKey).isPresent()) {
                return;
            }
        } catch (Exception ignore) {
            // ignore
        }

        VectorShadowMergeDlq row = new VectorShadowMergeDlq();
        row.setDedupeKey(limit(dedupeKey, 96));
        row.setStableVectorId(limit(stableVectorId, 256));
        row.setShadowVectorId(limit(shadowVectorId, 256));
        row.setLogicalSid(limit(logicalSid, 96));
        row.setTargetSid(limit(targetSid, 96));
        row.setShadowSid(limit(shadowSid, 96));
        row.setShadowRunId(limit(shadowRunId, 96));
        row.setShadowReason(limit(shadowReason, 512));
        row.setPayload(payload);

        try {
            row.setMetaJson(om.writeValueAsString(meta == null ? Map.of() : meta));
        } catch (Exception e) {
            row.setMetaJson("{}");
        }

        row.setStatus(VectorShadowMergeDlq.Status.PENDING);
        row.setNextAttemptAt(LocalDateTime.now());

        try {
            repo.save(row);
        } catch (DataIntegrityViolationException dup) {
            // ignore duplicates
        } catch (Exception e) {
            log.debug("[ShadowDLQ] record failed: {}", e.toString());
        }
    }

    /**
     * Merge a few due DLQ rows once (best-effort).
     */
    @Transactional
    public MergeReport mergeDueOnce() {
        MergeReport report = new MergeReport();
        report.now = LocalDateTime.now();

        if (embeddingModel == null || embeddingStore == null) {
            report.notes.add("embeddingModel/embeddingStore not wired; skipping");
            return report;
        }

        List<VectorShadowMergeDlq> due = repo.lockDue(report.now, PageRequest.of(0, Math.max(1, mergeBatchSize)));
        if (due.isEmpty()) {
            report.notes.add("no due rows");
            return report;
        }

        for (VectorShadowMergeDlq row : due) {
            report.scanned++;
            if (row.getAttemptCount() >= Math.max(1, maxAttempts)) {
                row.setStatus(VectorShadowMergeDlq.Status.FAILED);
                row.setLastError("max_attempts_exceeded");
                row.setNextAttemptAt(null);
                report.failed++;
                continue;
            }

            row.setStatus(VectorShadowMergeDlq.Status.INFLIGHT);
            row.setAttemptCount(row.getAttemptCount() + 1);
            row.setLastAttemptAt(report.now);
            row.setLockedAt(report.now);
            row.setLockedBy("node");
        }

        // Flush state changes
        repo.saveAll(due);

        for (VectorShadowMergeDlq row : due) {
            if (row.getStatus() != VectorShadowMergeDlq.Status.INFLIGHT) continue;
            try {
                mergeOne(row);
                row.setStatus(VectorShadowMergeDlq.Status.DONE);
                row.setLastError(null);
                row.setNextAttemptAt(null);
                report.merged++;
            } catch (BlockedException be) {
                row.setStatus(VectorShadowMergeDlq.Status.BLOCKED);
                row.setLastError(limit(be.getMessage(), 1024));
                row.setNextAttemptAt(report.now.plusSeconds(Math.max(60, blockedRetrySeconds)));
                report.blocked++;
            } catch (Exception e) {
                row.setStatus(VectorShadowMergeDlq.Status.FAILED);
                row.setLastError(limit(e.toString(), 1024));
                row.setNextAttemptAt(report.now.plusSeconds(Math.max(60, retrySeconds)));
                report.failed++;
            } finally {
                row.setLockedAt(null);
                row.setLockedBy(null);
            }
        }

        repo.saveAll(due);
        return report;
    }

    private void mergeOne(VectorShadowMergeDlq row) {
        String stableVectorId = safe(row.getStableVectorId());
        if (stableVectorId.isBlank()) {
            throw new BlockedException("missing_stable_vector_id");
        }

        Map<String, Object> meta = parseMeta(row.getMetaJson());

        // Require explicit "verified" or no "verification_needed" to merge.
        boolean verificationNeeded = truthy(meta.get(VectorMetaKeys.META_VERIFICATION_NEEDED))
                || truthy(meta.get("verification_needed"));
        boolean verified = truthy(meta.get(VectorMetaKeys.META_VERIFIED));

        if (verificationNeeded || !verified) {
            throw new BlockedException("still_unverified");
        }

        // Force sid to target sid for the merged write.
        String targetSid = safe(row.getTargetSid());
        if (targetSid.isBlank()) {
            throw new BlockedException("missing_target_sid");
        }
        meta.put(VectorMetaKeys.META_SID, targetSid);

        // Mark shadow merge status.
        meta.put(VectorMetaKeys.META_SHADOW_STATE, "MERGED");
        meta.put(VectorMetaKeys.META_SHADOW_WRITE, "false");

        String payload = safe(row.getPayload());
        if (payload.isBlank()) {
            throw new BlockedException("empty_payload");
        }

        // Guard re-checks (optional)
        if (poisonGuard != null) {
            VectorPoisonGuard.IngestDecision d = poisonGuard.inspectIngest(targetSid, payload, meta, "shadow_merge");
            if (!d.allow()) throw new BlockedException("poison_guard:" + safe(d.reason()));
        }
        if (scopeGuard != null) {
            String docType = safe(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, ""));
            VectorScopeGuard.IngestDecision d = scopeGuard.inspectIngest(docType, payload, meta);
            if (!d.allow()) throw new BlockedException("scope_guard:" + safe(d.reason()));
        }

        Embedding emb = embeddingModel.embed(payload).content();
        if (emb == null || emb.vector() == null || emb.vector().length == 0) {
            throw new RuntimeException("empty_embedding");
        }

        Map<String, Object> metaForLc = sanitizeForLangChainMeta(meta);
        TextSegment seg = TextSegment.from(payload, dev.langchain4j.data.document.Metadata.from(metaForLc));

        // Upsert stable vector id into the main store (metadata carries sid).
        embeddingStore.addAll(List.of(stableVectorId), List.of(emb), List.of(seg));
    }

    private Map<String, Object> parseMeta(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return om.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
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

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String limit(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }



    private static Map<String, Object> sanitizeForLangChainMeta(Map<String, Object> in) {
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
        if (v instanceof String || v instanceof java.util.UUID
                || v instanceof Integer || v instanceof Long
                || v instanceof Float || v instanceof Double) return v;
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Number n) {
            double d = n.doubleValue();
            long l = n.longValue();
            if (Math.abs(d - (double) l) < 1e-9) {
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            }
            return d;
        }
        // Flatten small collections as comma-separated strings
        if (v instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) {
                if (o == null) continue;
                parts.add(String.valueOf(o));
                if (parts.size() >= 64) break;
            }
            return String.join(",", parts);
        }
        if (v instanceof Map<?, ?> m) {
            // Avoid nested objects in Metadata; stringify.
            return String.valueOf(m);
        }
        return String.valueOf(v);
    }
    public static class MergeReport {
        public LocalDateTime now;
        public int scanned;
        public int merged;
        public int blocked;
        public int failed;
        public final List<String> notes = new ArrayList<>();

        @Override
        public String toString() {
            return "MergeReport{scanned=" + scanned + ", merged=" + merged + ", blocked=" + blocked + ", failed=" + failed + ", notes=" + notes + "}";
        }
    }

    private static class BlockedException extends RuntimeException {
        BlockedException(String msg) { super(msg); }
    }
}
