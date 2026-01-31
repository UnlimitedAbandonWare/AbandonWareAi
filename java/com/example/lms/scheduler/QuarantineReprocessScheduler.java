package com.example.lms.scheduler;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.infra.resilience.SidRotationAdvisor;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.VectorPoisonGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * QuarantineReprocessScheduler
 *
 * <p>
 * "격리 재처리" 자동 파이프라인:
 * - QUARANTINED TranslationMemory를 소량씩 claim(lease)해서
 * - 로그/트레이스 성격을 완화(sanitize)하고
 * - poison guard + evidence gate를 통과하면 PENDING으로 되돌려 담금질(soak) 단계로 재투입합니다.
 *
 * <p>
 * NOTE:
 * - 운영 안전을 위해 기본 비활성(memory.quarantine-reprocess.enabled=false).
 * - PENDING으로만 되돌리고, 최종 ACTIVE 승격 + 벡터 인덱싱은 PendingMemorySoakScheduler가 수행합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "memory.quarantine-reprocess.enabled", havingValue = "true")
public class QuarantineReprocessScheduler {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern W_MARKER = Pattern.compile("\\[W\\d+\\]");
    private static final Pattern TS_LEVEL = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*\\b(INFO|WARN|ERROR|DEBUG|TRACE)\\b.*$");

    private final TranslationMemoryRepository repo;
    private final VectorPoisonGuard vectorPoisonGuard;

    @Autowired(required = false)
    private SidRotationAdvisor sidRotationAdvisor;

    @Value("${memory.quarantine-reprocess.batch-size:25}")
    private int batchSize;

    /** Skip very recent quarantined rows (minutes). */
    @Value("${memory.quarantine-reprocess.min-age-minutes:30}")
    private long minAgeMinutes;

    /** Lease duration for multi-instance claim (minutes). */
    @Value("${memory.quarantine-reprocess.lease-minutes:10}")
    private long leaseMinutes;

    /** Evidence threshold (urls + [Wn]). */
    @Value("${memory.quarantine-reprocess.min-evidence:1}")
    private int minEvidence;

    /** Max age to attempt auto salvage (hours). Older items stay quarantined for manual. */
    @Value("${memory.quarantine-reprocess.max-age-hours:168}")
    private long maxAgeHours;

    /** Unique scheduler instance id for lease claiming. */
    private final String lockedBy = java.util.UUID.randomUUID().toString();

    @Scheduled(fixedDelayString = "${memory.quarantine-reprocess.interval-ms:900000}")
    public void reprocessQuarantine() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireBefore = now.minusMinutes(Math.max(1, leaseMinutes));

            int claimed = repo.claimQuarantineLease(
                    TranslationMemory.MemoryStatus.QUARANTINED.ordinal(),
                    lockedBy,
                    now,
                    expireBefore,
                    Math.max(1, Math.min(batchSize, 200))
            );
            if (claimed <= 0) return;

            for (TranslationMemory tm : repo.findByLockedByAndLockedAtOrderByCreatedAtAsc(lockedBy, now)) {
                if (tm == null) continue;

                // age gates
                if (tm.getCreatedAt() != null && minAgeMinutes > 0) {
                    long ageM = Math.max(0, Duration.between(tm.getCreatedAt(), LocalDateTime.now()).toMinutes());
                    if (ageM < minAgeMinutes) {
                        releaseLease(tm);
                        continue;
                    }
                }
                if (tm.getCreatedAt() != null && maxAgeHours > 0) {
                    long ageH = Math.max(0, Duration.between(tm.getCreatedAt(), LocalDateTime.now()).toHours());
                    if (ageH > maxAgeHours) {
                        releaseLease(tm);
                        continue;
                    }
                }

                String raw = pickText(tm);
                if (raw == null || raw.isBlank()) {
                    releaseLease(tm);
                    continue;
                }

                String sanitized = sanitize(raw);
                if (sanitized == null || sanitized.isBlank()) {
                    releaseLease(tm);
                    continue;
                }

                // poison guard pass (fail-soft: keep quarantined)
                try {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put(VectorMetaKeys.META_SOURCE_TAG, "QUARANTINE_REPROCESS");
                    meta.put(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
                    VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(tm.getSessionId(), sanitized, meta,
                            "quarantine-reprocess");
                    if (dec == null || !dec.allow()) {
                        try {
                            if (sidRotationAdvisor != null) {
                                sidRotationAdvisor.recordQuarantine(tm.getSessionId(),
                                        (dec == null ? "null_decision" : String.valueOf(dec.reason())));
                            }
                        } catch (Exception ignore) {
                            // ignore
                        }
                        releaseLease(tm);
                        continue;
                    }
                    if (dec.text() != null && !dec.text().isBlank()) {
                        sanitized = dec.text();
                    }
                } catch (Exception guardErr) {
                    releaseLease(tm);
                    continue;
                }

                int evidence = countEvidenceSignals(sanitized);
                boolean weak = EvidenceAwareGuard.looksWeak(sanitized);
                if (weak) {
                    releaseLease(tm);
                    continue;
                }

                if (!passesEvidenceGate(tm, evidence)) {
                    releaseLease(tm);
                    continue;
                }

                // promote back into the PENDING soak pipeline
                if (!Objects.equals(raw, sanitized)) {
                    tm.setCorrected(sanitized);
                } else if (tm.getCorrected() == null || tm.getCorrected().isBlank()) {
                    tm.setCorrected(sanitized);
                }
                tm.setStatus(TranslationMemory.MemoryStatus.PENDING);
                tm.setLockedAt(null);
                tm.setLockedBy(null);
                repo.save(tm);

                log.info("[QUARANTINE_REPROCESS] -> PENDING id={} sid={} evidence={} len={}",
                        tm.getId(), tm.getSessionId(), evidence, sanitized.length());
            }
        } catch (Exception e) {
            log.warn("[QUARANTINE_REPROCESS] failed: {}", e.toString());
        }
    }

    private void releaseLease(TranslationMemory tm) {
        if (tm == null) return;
        try {
            tm.setLockedAt(null);
            tm.setLockedBy(null);
            repo.save(tm);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static String pickText(TranslationMemory tm) {
        if (tm == null) return null;
        String c = tm.getCorrected();
        if (c != null && !c.isBlank()) return c;
        return tm.getContent();
    }

    private static int countEvidenceSignals(String s) {
        if (s == null || s.isBlank()) return 0;
        int n = 0;
        if (URL.matcher(s).find()) n++;
        var m = W_MARKER.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private boolean passesEvidenceGate(TranslationMemory tm, int evidence) {
        int min = Math.max(0, minEvidence);
        if (min <= 0) return true;

        String tag = (tm == null || tm.getSourceTag() == null) ? "" : tm.getSourceTag().trim().toUpperCase(Locale.ROOT);
        boolean strongOrigin = tag.startsWith("USER") || tag.startsWith("OFFICIAL") || tag.startsWith("SYSTEM");
        boolean assistant = tag.contains("ASSISTANT") || tag.contains("LLM");

        Double conf = (tm == null) ? null : tm.getConfidenceScore();
        boolean highConf = conf != null && conf >= 0.85;

        if (assistant) {
            return evidence >= min || highConf;
        }
        if (strongOrigin) {
            return true;
        }
        return evidence >= min;
    }

    private static String sanitize(String text) {
        if (text == null) return null;
        String t = text.replace('\u0000', ' ').trim();
        if (t.isBlank()) return t;

        if (t.length() <= 1200 && !TS_LEVEL.matcher(firstLine(t)).matches()) {
            return t;
        }

        StringBuilder sb = new StringBuilder(Math.min(1200, t.length()));
        int kept = 0;

        for (String line : t.split("\\R")) {
            if (line == null) continue;
            String ln = line.strip();
            if (ln.isBlank()) continue;

            // drop log-like lines
            if (TS_LEVEL.matcher(ln).matches()) continue;
            if (ln.startsWith("at ") && ln.contains("(") && ln.contains(")")) continue;
            if (ln.startsWith("Caused by:")) continue;
            if (ln.startsWith("ROUTE_LABEL")) continue;
            if (ln.contains("Hibernate:")) continue;

            sb.append(ln).append("\n");
            kept++;
            if (kept >= 10) break;
            if (sb.length() >= 1400) break;
        }

        String out = sb.toString().trim();
        if (out.isBlank()) {
            out = t.length() > 400 ? t.substring(0, 400) : t;
        }
        out = out.replaceAll("\\s+", " ").trim();
        if (out.length() > 2000) out = out.substring(0, 2000);
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int idx = s.indexOf('\n');
        if (idx < 0) return s;
        return s.substring(0, idx).trim();
    }
}
