package com.example.lms.service.vector;

import com.example.lms.entity.VectorQuarantineDlq;
import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * DLQ service for quarantined vector ingests (INGEST_PROTECTION).
 */
@ConditionalOnProperty(name = "vector.dlq.enabled", havingValue = "true")
@ConditionalOnBean(VectorQuarantineDlqRepository.class)
@Service
public class VectorQuarantineDlqService {

    private static final Logger log = LoggerFactory.getLogger(VectorQuarantineDlqService.class);

    private final VectorQuarantineDlqRepository repo;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    @Qualifier("federatedEmbeddingStore")
    private final EmbeddingStore<TextSegment> embeddingStore;

    private final TransactionTemplate claimTx;

    public VectorQuarantineDlqService(VectorQuarantineDlqRepository repo,
                                     ObjectMapper objectMapper,
                                     EmbeddingModel embeddingModel,
                                     @Qualifier("federatedEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore,
                                     PlatformTransactionManager txManager) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.claimTx = new TransactionTemplate(txManager);
        // Claim은 짧은 트랜잭션으로 끊어서(임베딩/업서트 등 느린 작업과 분리) 락 점유를 최소화합니다.
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    private VectorSidService vectorSidService;

    @Autowired(required = false)
    private VectorPoisonGuard vectorPoisonGuard;

    @Autowired(required = false)
    private VectorScopeGuard vectorScopeGuard;

    @Value("${vector.dlq.enabled:false}")
    private boolean enabled;

    @Value("${vector.dlq.batch-size:50}")
    private int batchSize;

    @Value("${vector.dlq.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${vector.dlq.max-attempts:25}")
    private int maxAttempts;

    @Value("${vector.dlq.base-backoff-ms:60000}")
    private long baseBackoffMs;

    @Value("${vector.dlq.max-backoff-ms:3600000}")
    private long maxBackoffMs;

    public record RedriveReport(
            boolean enabled,
            int claimed,
            int attempted,
            int succeeded,
            int blocked,
            int failed,
            int deferred,
            String leaseKey
    ) {
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Record a quarantined ingest into the DB (idempotent via dedupeKey).
     */
    public void recordQuarantined(
            String quarantineVectorId,
            String originalVectorId,
            String originalSid,
            String originalSidBase,
            String quarantineReason,
            String payload,
            Map<String, Object> meta
    ) {
        if (!enabled) return;
        if (quarantineVectorId == null || quarantineVectorId.isBlank()) return;
        if (originalSid == null || originalSid.isBlank()) return;
        if (payload == null || payload.isBlank()) return;

        String base = (originalSidBase == null || originalSidBase.isBlank())
                ? VectorIngestProtectionService.sidBase(originalSid)
                : originalSidBase;

        String dedupeKey = computeDedupeKey(base, originalVectorId, quarantineVectorId, payload);

        try {
            if (repo.findByDedupeKey(dedupeKey).isPresent()) {
                return;
            }

            VectorQuarantineDlq row = new VectorQuarantineDlq();
            row.setDedupeKey(dedupeKey);
            row.setQuarantineVectorId(quarantineVectorId);
            row.setOriginalVectorId((originalVectorId == null || originalVectorId.isBlank()) ? null : originalVectorId);
            row.setOriginalSid(originalSid);
            row.setOriginalSidBase(base);
            row.setQuarantineReason((quarantineReason == null) ? null : limitLen(quarantineReason, 512));
            row.setPayload(payload);

            Map<String, Object> safeMeta = sanitizeForJson(meta);
            row.setMetaJson(objectMapper.writeValueAsString(safeMeta));

            row.setStatus(VectorQuarantineDlq.Status.PENDING);
            row.setAttemptCount(0);
            row.setNextAttemptAt(LocalDateTime.now());

            repo.save(row);
        } catch (Exception e) {
            // fail-soft
            log.warn("[VectorDLQ] recordQuarantined failed (fail-soft): {}", e.toString());
        }
    }



    public Page<VectorQuarantineDlqRepository.DlqRecordSummary> listRecords(VectorQuarantineDlq.Status status,
                                                                           String originalSidBase,
                                                                           int page,
                                                                           int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        var pageable = PageRequest.of(safePage, safeSize);
        boolean hasStatus = status != null;
        boolean hasSidBase = originalSidBase != null && !originalSidBase.isBlank();

        if (hasStatus && hasSidBase) {
            return repo.findByStatusAndOriginalSidBaseOrderByIdDesc(status, originalSidBase, pageable);
        }
        if (hasStatus) {
            return repo.findByStatusOrderByIdDesc(status, pageable);
        }
        if (hasSidBase) {
            return repo.findByOriginalSidBaseOrderByIdDesc(originalSidBase, pageable);
        }
        return repo.findAllByOrderByIdDesc(pageable);
    }

    public List<VectorQuarantineDlqRepository.ReasonCountView> topReasons(VectorQuarantineDlq.Status status, int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 200);
        return repo.topReasonCounts(status, PageRequest.of(0, safeLimit));
    }

    public VectorQuarantineDlq getById(Long id) {
        if (id == null) {
            return null;
        }
        return repo.findById(id).orElse(null);
    }
    public Map<String, Object> stats() {
        if (!enabled) {
            return Map.of("enabled", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        try {
            out.put("pending", repo.countByStatus(VectorQuarantineDlq.Status.PENDING));
            out.put("inflight", repo.countByStatus(VectorQuarantineDlq.Status.INFLIGHT));
            out.put("done", repo.countByStatus(VectorQuarantineDlq.Status.DONE));
            out.put("blocked", repo.countByStatus(VectorQuarantineDlq.Status.BLOCKED));
            out.put("failed", repo.countByStatus(VectorQuarantineDlq.Status.FAILED));

            out.put("leaseSeconds", leaseSeconds);
            out.put("batchSize", batchSize);
            out.put("maxAttempts", maxAttempts);

            LocalDateTime expireBefore = LocalDateTime.now().minusSeconds(Math.max(leaseSeconds, 1));
            out.put("inflightExpired", repo.countInflightExpired(expireBefore));
        } catch (Exception e) {
            out.put("error", e.toString());
        }
        return out;
    }

    private List<VectorQuarantineDlq> claimDueLeaseTx(String leaseKey,
                                                 LocalDateTime now,
                                                 LocalDateTime expireBefore,
                                                 int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 500);

        List<VectorQuarantineDlq> claimed = claimTx.execute(status -> {
            List<VectorQuarantineDlq> picked = new ArrayList<>();

            picked.addAll(repo.findPendingDueForUpdate(
                    VectorQuarantineDlq.Status.PENDING,
                    now,
                    PageRequest.of(0, safeLimit)
            ));

            int remaining = safeLimit - picked.size();
            if (remaining > 0) {
                picked.addAll(repo.findStaleInflightForUpdate(
                        VectorQuarantineDlq.Status.INFLIGHT,
                        expireBefore,
                        PageRequest.of(0, remaining)
                ));
            }

            if (picked.isEmpty()) {
                return List.of();
            }

            for (VectorQuarantineDlq row : picked) {
                row.setStatus(VectorQuarantineDlq.Status.INFLIGHT);
                row.setLockedBy(leaseKey);
                row.setLockedAt(now);
            }

            repo.saveAll(picked);
            repo.flush();
            return picked;
        });

        return claimed == null ? List.of() : claimed;
    }

    /**
     * Claim due rows and attempt redrive once.
     */
    public RedriveReport redriveDueOnce(String requestedBy) {
        if (!enabled) {
            return new RedriveReport(false, 0, 0, 0, 0, 0, 0, null);
        }

        String leaseKey = (requestedBy == null || requestedBy.isBlank())
                ? ("manual@" + UUID.randomUUID())
                : (requestedBy + "@" + UUID.randomUUID());

        // Use second-level precision to avoid DB timestamp precision mismatches
        // when we later query by locked_at = :now.
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime expireBefore = now.minusSeconds(Math.max(leaseSeconds, 1));

        List<VectorQuarantineDlq> rows;
        try {
            rows = claimDueLeaseTx(leaseKey, now, expireBefore, Math.max(batchSize, 1));
        } catch (Exception e) {
            log.warn("[VectorDLQ] claimDueLeaseTx failed: {}", e.toString());
            return new RedriveReport(true, 0, 0, 0, 0, 0, 0, leaseKey);
        }

        int claimed = rows.size();
        if (claimed <= 0) {
            return new RedriveReport(true, 0, 0, 0, 0, 0, 0, leaseKey);
        }

        int attempted = 0;
        int succeeded = 0;
        int blocked = 0;
        int failed = 0;
        int deferred = 0;

        for (VectorQuarantineDlq row : rows) {
            attempted++;
            try {
                if (row.getAttemptCount() >= maxAttempts) {
                    markFailed(row, "max_attempts_exceeded");
                    failed++;
                    continue;
                }

                replayOne(row);
                markDone(row);
                succeeded++;
            } catch (BlockedRedrive br) {
                markBlocked(row, br.getMessage());
                blocked++;
            } catch (Exception e) {
                boolean willRetry = markRetry(row, e);
                if (willRetry) deferred++; else failed++;
            }
        }

        return new RedriveReport(true, claimed, attempted, succeeded, blocked, failed, deferred, leaseKey);
    }

    /* ====================== internals ====================== */

    private void replayOne(VectorQuarantineDlq row) {
        String payload = row.getPayload();
        if (payload == null || payload.isBlank()) {
            throw new BlockedRedrive("empty_payload");
        }

        Map<String, Object> meta = parseMeta(row.getMetaJson());

        String base = (row.getOriginalSidBase() == null || row.getOriginalSidBase().isBlank())
                ? VectorIngestProtectionService.sidBase(row.getOriginalSid())
                : row.getOriginalSidBase();

        String targetSid = base;
        if (vectorSidService != null) {
            try {
                String resolved = vectorSidService.resolveActiveSid(base);
                if (resolved != null && !resolved.isBlank()) {
                    targetSid = resolved;
                }
            } catch (Exception ignore) {
                // fail-soft
            }
        }

        // Ensure required keys
        meta.put(VectorMetaKeys.META_SID, targetSid);
        meta.putIfAbsent(VectorMetaKeys.META_SID_LOGICAL, base);
        meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_SID, row.getOriginalSid());
        if (row.getOriginalVectorId() != null && !row.getOriginalVectorId().isBlank()) {
            meta.putIfAbsent(VectorMetaKeys.META_ORIGINAL_ID, row.getOriginalVectorId());
        }
        meta.put(VectorMetaKeys.META_DLQ_REPLAY, "true");
        meta.put(VectorMetaKeys.META_DLQ_ID, String.valueOf(row.getId()));
        if (row.getQuarantineVectorId() != null && !row.getQuarantineVectorId().isBlank()) {
            meta.put(VectorMetaKeys.META_DLQ_QUARANTINE_ID, row.getQuarantineVectorId());
        }
        meta.put(VectorMetaKeys.META_ORIGIN, "DLQ_REDRIVE");
        meta.put(VectorMetaKeys.META_STRICT_WRITE, "true");

        String docType0 = String.valueOf(meta.getOrDefault(VectorMetaKeys.META_DOC_TYPE, "LEGACY")).trim();

        // Re-run ingest guards; if it is now rejected, block redrive.
        if (vectorPoisonGuard != null) {
            VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(targetSid, payload, meta, "vector_dlq_redrive");
            if (dec == null) {
                throw new BlockedRedrive("null_guard_decision");
            }
            if (!dec.allow()) {
                throw new BlockedRedrive("poison_guard:" + (dec.reason() == null ? "" : dec.reason()));
            }
            if (dec.text() != null && !dec.text().isBlank()) {
                payload = dec.text();
            }
            if (dec.meta() != null && !dec.meta().isEmpty()) {
                meta = new LinkedHashMap<>(dec.meta());
                // re-apply required keys after meta replacement
                meta.put(VectorMetaKeys.META_SID, targetSid);
                meta.putIfAbsent(VectorMetaKeys.META_SID_LOGICAL, base);
                meta.put(VectorMetaKeys.META_DLQ_REPLAY, "true");
                meta.put(VectorMetaKeys.META_DLQ_ID, String.valueOf(row.getId()));
                meta.put(VectorMetaKeys.META_ORIGIN, "DLQ_REDRIVE");
                meta.put(VectorMetaKeys.META_STRICT_WRITE, "true");
            }
        }

        if (vectorScopeGuard != null) {
            VectorScopeGuard.IngestDecision sd = vectorScopeGuard.inspectIngest(docType0, payload, meta);
            if (sd == null) {
                // allow, but annotate
                meta.putIfAbsent(VectorMetaKeys.META_SCOPE_REASON, "null_scope_decision");
            } else {
                if (sd.metaEnrich() != null && !sd.metaEnrich().isEmpty()) {
                    meta.putAll(sd.metaEnrich());
                }
                if (!sd.allow()) {
                    throw new BlockedRedrive("scope_guard:" + (sd.reason() == null ? "" : sd.reason()));
                }
            }
        }

        Map<String, Object> metaForLc = sanitizeForLangChainMeta(meta);
        TextSegment seg = TextSegment.from(payload, Metadata.from(metaForLc));

        var resp = embeddingModel.embedAll(List.of(seg));
        var embeds = (resp == null) ? null : resp.content();
        if (embeds == null || embeds.isEmpty() || embeds.get(0) == null || embeds.get(0).vector() == null || embeds.get(0).vector().length == 0) {
            throw new IllegalStateException("EmbeddingModel returned empty vector during DLQ redrive");
        }
        if (isAllZero(embeds.get(0).vector())) {
            throw new IllegalStateException("EmbeddingModel returned all-zero vector during DLQ redrive");
        }

        String id = computeTargetId(targetSid, row.getOriginalVectorId(), payload);
        embeddingStore.addAll(List.of(id), List.of(embeds.get(0)), List.of(seg));
    }

    private static String computeTargetId(String targetSid, String originalVectorId, String payload) {
        String ov = (originalVectorId == null) ? null : originalVectorId.trim();
        if (ov != null && !ov.isBlank()) {
            return ov;
        }
        return targetSid + ":" + DigestUtils.sha256Hex(payload);
    }

    private Map<String, Object> parseMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String computeDedupeKey(String originalSidBase,
                                          String originalVectorId,
                                          String quarantineVectorId,
                                          String payload) {
        String base = (originalSidBase == null) ? "" : originalSidBase.trim();
        String ovid = (originalVectorId == null) ? "" : originalVectorId.trim();
        String qvid = (quarantineVectorId == null) ? "" : quarantineVectorId.trim();
        String p = DigestUtils.sha256Hex(payload);
        return DigestUtils.sha256Hex(base + "|" + ovid + "|" + qvid + "|" + p);
    }

    private void markDone(VectorQuarantineDlq row) {
        row.setStatus(VectorQuarantineDlq.Status.DONE);
        row.setAttemptCount(row.getAttemptCount() + 1);
        row.setLastAttemptAt(LocalDateTime.now());
        row.setNextAttemptAt(null);
        row.setLastError(null);
        row.setLockedAt(null);
        row.setLockedBy(null);
        repo.save(row);
    }

    private void markBlocked(VectorQuarantineDlq row, String reason) {
        row.setStatus(VectorQuarantineDlq.Status.BLOCKED);
        row.setAttemptCount(row.getAttemptCount() + 1);
        row.setLastAttemptAt(LocalDateTime.now());
        row.setNextAttemptAt(null);
        row.setLastError(limitLen(reason, 1024));
        row.setLockedAt(null);
        row.setLockedBy(null);
        repo.save(row);
    }

    private void markFailed(VectorQuarantineDlq row, String reason) {
        row.setStatus(VectorQuarantineDlq.Status.FAILED);
        row.setAttemptCount(Math.max(row.getAttemptCount(), maxAttempts));
        row.setLastAttemptAt(LocalDateTime.now());
        row.setNextAttemptAt(null);
        row.setLastError(limitLen(reason, 1024));
        row.setLockedAt(null);
        row.setLockedBy(null);
        repo.save(row);
    }

    private boolean markRetry(VectorQuarantineDlq row, Exception e) {
        int nextAttempt = row.getAttemptCount() + 1;
        row.setAttemptCount(nextAttempt);
        row.setLastAttemptAt(LocalDateTime.now());
        row.setLastError(limitLen(e.toString(), 1024));
        row.setLockedAt(null);
        row.setLockedBy(null);

        if (nextAttempt >= maxAttempts) {
            row.setStatus(VectorQuarantineDlq.Status.FAILED);
            row.setNextAttemptAt(null);
            repo.save(row);
            return false;
        }

        long delay = computeBackoffMs(nextAttempt);
        row.setStatus(VectorQuarantineDlq.Status.PENDING);
        row.setNextAttemptAt(LocalDateTime.now().plusNanos(delay * 1_000_000L));
        repo.save(row);
        return true;
    }

    private long computeBackoffMs(int attempt) {
        long pow = 1L << Math.min(Math.max(attempt - 1, 0), 20); // cap exponent
        long raw = baseBackoffMs * pow;
        long capped = Math.min(raw, maxBackoffMs);
        long jitter = (long) (Math.random() * 5000L);
        return capped + jitter;
    }

    private static String limitLen(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static boolean isAllZero(float[] v) {
        if (v == null || v.length == 0) return true;
        for (float x : v) {
            if (x != 0.0f) return false;
        }
        return true;
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

    private static Map<String, Object> sanitizeForJson(Map<String, Object> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (e == null || e.getKey() == null || e.getKey().isBlank()) continue;
            out.put(e.getKey(), sanitizeJsonValue(e.getValue(), 0));
        }
        return out;
    }

    private static Object sanitizeJsonValue(Object v, int depth) {
        if (v == null) return null;
        if (depth > 4) return String.valueOf(v);
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e == null || e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), sanitizeJsonValue(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            int n = 0;
            for (Object o : c) {
                out.add(sanitizeJsonValue(o, depth + 1));
                if (++n >= 128) break;
            }
            return out;
        }
        return String.valueOf(v);
    }

    private static final class BlockedRedrive extends RuntimeException {
        BlockedRedrive(String msg) {
            super(msg);
        }
    }
}
