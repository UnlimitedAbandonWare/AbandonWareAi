package com.example.lms.scheduler;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.VectorPoisonGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "memory.pending-soak.enabled", havingValue = "true")
public class PendingMemorySoakScheduler {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern W_MARKER = Pattern.compile("\\[W\\d+\\]");

    private final TranslationMemoryRepository repo;
    private final VectorStoreService vectorStoreService;
    private final VectorPoisonGuard vectorPoisonGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

    @Value("${memory.pending-soak.batch-size:25}")
    private int batchSize;

    @Value("${memory.pending-soak.max-age-hours:72}")
    private long maxAgeHours;

    /** Lease duration for multi-instance claim (minutes). */
    @Value("${memory.pending-soak.lease-minutes:10}")
    private long leaseMinutes;

    @Value("${memory.pending-soak.min-evidence:1}")
    private int minEvidence;

    /** Unique scheduler instance id for lease claiming. */
    private final String lockedBy = java.util.UUID.randomUUID().toString();

    @Scheduled(fixedDelayString = "${memory.pending-soak.interval-ms:300000}")
    public void soakPendingMemories() {
        try {
            // Claim PENDING rows in DB using an index-friendly query.
            // This removes full scan/filesort cost and prevents duplicate promotion
            // in multi-instance deployments.
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireBefore = now.minusMinutes(Math.max(1, leaseMinutes));
            int claimed = repo.claimPendingLease(
                    TranslationMemory.MemoryStatus.PENDING.ordinal(),
                    lockedBy,
                    now,
                    expireBefore,
                    Math.max(1, batchSize)
            );
            if (claimed <= 0) return;

            List<TranslationMemory> promotions = new ArrayList<>();
            for (TranslationMemory tm : repo.findByLockedByAndLockedAtOrderByCreatedAtAsc(lockedBy, now)) {
                if (tm == null) continue;

                LocalDateTime createdAt = tm.getCreatedAt();
                if (createdAt != null && maxAgeHours > 0) {
                    long ageH = Math.max(0, Duration.between(createdAt, LocalDateTime.now()).toHours());
                    if (ageH > maxAgeHours) {
                        tm.setStatus(TranslationMemory.MemoryStatus.QUARANTINED);
                        try {
                            if (sidRotationAdvisor != null) {
                                sidRotationAdvisor.recordQuarantine(tm.getSessionId(), "pending_soak.max_age");
                            }
                        } catch (Exception ignore) {
                            traceSuppressed("sidRotation.maxAge", ignore);
                            // fail-soft
                        }
                        tm.setLockedAt(null);
                        tm.setLockedBy(null);
                        repo.save(tm);
                        continue;
                    }
                }

                String content = tm.getContent();
                if (content == null || content.isBlank()) {
                    tm.setLockedAt(null);
                    tm.setLockedBy(null);
                    repo.save(tm);
                    continue;
                }

                // [VECTOR_POISON] block promotion of log/trace dumps
                try {
                    Map<String, Object> guardMeta = new HashMap<>();
                    guardMeta.put(VectorMetaKeys.META_SOURCE_TAG, "PENDING_SOAK");
                    guardMeta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
                    VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(tm.getSessionId(), content, guardMeta, "pending-soak");
                    if (dec == null || !dec.allow()) {
                        tm.setStatus(TranslationMemory.MemoryStatus.QUARANTINED);
                        try {
                            if (sidRotationAdvisor != null) {
                                sidRotationAdvisor.recordQuarantine(tm.getSessionId(), (dec != null ? dec.reason() : "null"));
                            }
                        } catch (Exception ignore) {
                            traceSuppressed("sidRotation.poison", ignore);
                            // fail-soft
                        }
                        tm.setLockedAt(null);
                        tm.setLockedBy(null);
                        repo.save(tm);
                        log.warn("[PENDING_SOAK] quarantined suspicious memory idHash={} sessionHash={} reason={} ",
                                com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(tm.getId())), com.example.lms.trace.SafeRedactor.hashValue(tm.getSessionId()), (dec != null ? dec.reason() : "null"));
                        continue;
                    }
                } catch (Exception guardErr) {
                    log.warn("[PENDING_SOAK] guard error; skip promote idHash={} sessionHash={} errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(tm.getId())),
                            com.example.lms.trace.SafeRedactor.hashValue(tm.getSessionId()),
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(guardErr)),
                            messageLength(guardErr));
                    tm.setLockedAt(null);
                    tm.setLockedBy(null);
                    repo.save(tm);
                    continue;
                }

                int evidence = countEvidenceSignals(content);
                if (evidence < Math.max(1, minEvidence)) {
                    // ambiguous -> keep pending but release lease
                    tm.setLockedAt(null);
                    tm.setLockedBy(null);
                    repo.save(tm);
                    continue;
                }

                boolean weak = EvidenceAwareGuard.looksWeak(content);
                if (weak) {
                    // ambiguous/low-signal payload -> quarantine (fail-soft)
                    tm.setStatus(TranslationMemory.MemoryStatus.QUARANTINED);
                    try {
                        if (sidRotationAdvisor != null) {
                            sidRotationAdvisor.recordQuarantine(tm.getSessionId(), "pending_soak.weak_evidence");
                        }
                    } catch (Exception ignore) {
                        traceSuppressed("sidRotation.weakEvidence", ignore);
                        // fail-soft
                    }
                    tm.setLockedAt(null);
                    tm.setLockedBy(null);
                    repo.save(tm);
                    continue;
                }

                try {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put(VectorMetaKeys.META_SOURCE_TAG, "PENDING_SOAK");
                    meta.put(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
                    meta.put("evidenceSignals", evidence);
                    vectorStoreService.enqueue(tm.getSessionId(), content, meta);
                    promotions.add(tm);
                } catch (Exception enqueueErr) {
                    log.debug("[PENDING_SOAK] enqueue failed idHash={} errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(tm.getId())),
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(enqueueErr)),
                            messageLength(enqueueErr));
                    releasePendingLease(tm, "enqueue_failure");
                }
            }

            if (!promotions.isEmpty()) {
                VectorStoreService.VectorFlushOutcome outcome = null;
                try {
                    outcome = vectorStoreService.flush();
                } catch (Exception flushErr) {
                    log.warn("[PENDING_SOAK] flush failed errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(flushErr)),
                            messageLength(flushErr));
                    com.example.lms.search.TraceStore.inc("vectorstore.flush.failed");
                }

                if (outcome != null && outcome.durable()) {
                    for (TranslationMemory promotion : promotions) {
                        promotion.setStatus(TranslationMemory.MemoryStatus.ACTIVE);
                        promotion.setLockedAt(null);
                        promotion.setLockedBy(null);
                        repo.save(promotion);
                        log.info("[PENDING_SOAK] promoted memory idHash={} sessionHash={} evidence={}",
                                com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(promotion.getId())),
                                com.example.lms.trace.SafeRedactor.hashValue(promotion.getSessionId()),
                                countEvidenceSignals(promotion.getContent()));
                    }
                } else {
                    String reasonCode = flushRetryReason(outcome);
                    for (TranslationMemory promotion : promotions) {
                        releasePendingLease(promotion, reasonCode);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[PENDING_SOAK] failed errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
        }
    }

    private void releasePendingLease(TranslationMemory memory, String reasonCode) {
        String safeReasonCode = switch (reasonCode == null ? "" : reasonCode) {
            case "enqueue_failure", "flush_backoff", "flush_failure", "flush_partial" -> reasonCode;
            default -> "flush_failure";
        };
        memory.setStatus(TranslationMemory.MemoryStatus.PENDING);
        memory.setLockedAt(null);
        memory.setLockedBy(null);
        repo.save(memory);
        com.example.lms.search.TraceStore.put("pendingSoak.retry.reason", safeReasonCode);
        com.example.lms.search.TraceStore.put("pendingSoak.retry.memoryIdHash",
                com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(memory.getId())));
        com.example.lms.search.TraceStore.put("pendingSoak.retry.sessionHash",
                com.example.lms.trace.SafeRedactor.hashValue(memory.getSessionId()));
    }

    private static String flushRetryReason(VectorStoreService.VectorFlushOutcome outcome) {
        if (outcome == null) return "flush_failure";
        if ("backoff".equals(outcome.reasonCode())) return "flush_backoff";
        if (outcome.succeededCount() > 0) return "flush_partial";
        return "flush_failure";
    }

    private static int countEvidenceSignals(String s) {
        if (s == null || s.isBlank()) return 0;
        int n = 0;
        if (URL.matcher(s).find()) n++;
        var m = W_MARKER.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        String safeErrorType = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        com.example.lms.search.TraceStore.put("pending.memory.soak.suppressed." + safeStage, true);
        com.example.lms.search.TraceStore.put("pending.memory.soak.suppressed." + safeStage + ".errorType", safeErrorType);
    }
}
