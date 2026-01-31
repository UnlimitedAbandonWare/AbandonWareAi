package com.example.lms.infra.resilience;

import com.example.lms.service.rag.LangChainRAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SidRotationAdvisor
 *
 * <p>
 * "자동 권고"용 경량 신호 집계기.
 * - retrieval 단계에서 vector-poison 필터 드랍이 반복되거나
 * - ingest 단계에서 quarantine 라우팅이 반복되면
 * sid rotation(특히 GLOBAL_SID) 권고 플래그를 올린다.
 *
 * <p>
 * NOTE:
 * - 실제 rotate 실행은 관리자 API(/api/admin/vector/rotate-sid)로 분리.
 * - 운영 안전을 위해 기본은 "권고"만 수행한다.
 */
@Component
public class SidRotationAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SidRotationAdvisor.class);

    @Value("${vector.sid.rotation-advisor.enabled:true}")
    private boolean enabled;

    /** Sliding window size (millis). Default: 10 minutes. */
    @Value("${vector.sid.rotation-advisor.window-ms:600000}")
    private long windowMs;

    /** Poison-filter events needed to recommend rotation (within window). */
    @Value("${vector.sid.rotation-advisor.poison-threshold:3}")
    private int poisonThreshold;

    /** Quarantine-routing events needed to recommend rotation (within window). */
    @Value("${vector.sid.rotation-advisor.quarantine-threshold:10}")
    private int quarantineThreshold;

    /** When true, only recommend rotation for the GLOBAL_SID. */
    @Value("${vector.sid.rotation-advisor.global-only:true}")
    private boolean globalOnly;

    /** Cooldown to avoid log spam + flapping. */
    @Value("${vector.sid.rotation-advisor.cooldown-ms:1800000}")
    private long cooldownMs;

    private final ConcurrentHashMap<String, Deque<Long>> poisonEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> quarantineEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recommendedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastReason = new ConcurrentHashMap<>();

    public void recordPoison(String sid, String reason) {
        record(sid, reason, poisonEvents, poisonThreshold, "poison");
    }

    public void recordQuarantine(String sid, String reason) {
        record(sid, reason, quarantineEvents, quarantineThreshold, "quarantine");
    }

    /** Return a small snapshot safe to expose on admin endpoints. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("windowMs", windowMs);
        out.put("poisonThreshold", poisonThreshold);
        out.put("quarantineThreshold", quarantineThreshold);
        out.put("globalOnly", globalOnly);

        Map<String, Object> per = new LinkedHashMap<>();
        for (String sid : unionKeys()) {
            per.put(sid, snapshotFor(sid));
        }
        out.put("sids", per);
        return out;
    }

    public Map<String, Object> snapshotFor(String sid) {
        String base = sidBase(sid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sid", base);
        out.put("poisonCount", countAndTrim(poisonEvents.get(base)));
        out.put("quarantineCount", countAndTrim(quarantineEvents.get(base)));
        Long at = recommendedAt.get(base);
        out.put("rotateRecommended", at != null && at > 0);
        out.put("recommendedAtEpochMs", at);
        out.put("lastReason", lastReason.get(base));
        return out;
    }

    public boolean isRotationRecommended(String sid) {
        String base = sidBase(sid);
        Long at = recommendedAt.get(base);
        if (at == null || at <= 0) return false;
        long now = System.currentTimeMillis();
        return (cooldownMs <= 0) || (now - at) <= cooldownMs;
    }

    public void clearRecommendation(String sid) {
        String base = sidBase(sid);
        recommendedAt.remove(base);
    }

    /* ------------------------ internals ------------------------ */

    private void record(String sid, String reason,
                        ConcurrentHashMap<String, Deque<Long>> bucket,
                        int threshold,
                        String kind) {

        if (!enabled) return;
        String base = sidBase(sid);
        if (base.isBlank()) return;

        if (globalOnly && !Objects.equals(base, LangChainRAGService.GLOBAL_SID)) {
            return;
        }

        long now = System.currentTimeMillis();
        long win = Math.max(5_000L, windowMs);

        Deque<Long> q = bucket.computeIfAbsent(base, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            trimLocked(q, now, win);
        }

        int cnt = countAndTrim(q, now, win);
        if (reason != null && !reason.isBlank()) {
            lastReason.put(base, limitLen(reason, 240));
        }

        if (threshold > 0 && cnt >= threshold) {
            Long prev = recommendedAt.get(base);
            if (prev == null || prev <= 0 || (cooldownMs > 0 && (now - prev) > cooldownMs)) {
                recommendedAt.put(base, now);
                log.warn("[SidRotationAdvisor] rotate recommended sid={} kind={} count={} threshold={} windowMs={} reason={}",
                        base, kind, cnt, threshold, win, lastReason.get(base));
            }
        }
    }

    private int countAndTrim(Deque<Long> q) {
        if (q == null) return 0;
        long now = System.currentTimeMillis();
        long win = Math.max(5_000L, windowMs);
        return countAndTrim(q, now, win);
    }

    private int countAndTrim(Deque<Long> q, long now, long win) {
        if (q == null) return 0;
        synchronized (q) {
            trimLocked(q, now, win);
            return q.size();
        }
    }

    private static void trimLocked(Deque<Long> q, long now, long win) {
        if (q == null) return;
        long cutoff = now - win;
        while (!q.isEmpty()) {
            Long t = q.peekFirst();
            if (t == null || t < cutoff) q.pollFirst();
            else break;
        }
        // prevent unbounded growth even if trimming misbehaves
        while (q.size() > 1024) q.pollFirst();
    }

    private Iterable<String> unionKeys() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.addAll(poisonEvents.keySet());
        out.addAll(quarantineEvents.keySet());
        out.addAll(recommendedAt.keySet());
        return out;
    }

    private static String sidBase(String sid) {
        if (sid == null) return "";
        String s = sid.trim();
        if (s.isBlank()) return "";
        int h = s.indexOf('#');
        if (h > 0) s = s.substring(0, h);
        return s.trim();
    }

    private static String limitLen(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
