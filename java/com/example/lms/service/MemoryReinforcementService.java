
package com.example.lms.service;
import java.lang.reflect.Method;        // trySet/tryGet에서 사용 (IDE가 자동 추가해도 됩니다)
import java.util.List;
import java.util.Comparator;
import org.springframework.dao.DataIntegrityViolationException; // ★ fix: noRollbackFor용
// 수정 후 코드 (After)

// ✅ 필요한 Exception import 추가
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.service.VectorStoreService; // 🔹 vector store service
import com.example.lms.service.reinforcement.SnippetPruner;  // ★ NEW

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.github.benmanes.caffeine.cache.Caffeine;   // dup-cache
import com.github.benmanes.caffeine.cache.LoadingCache;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils; // SHA‑256 helper
import java.util.regex.Pattern;
import java.time.LocalDateTime;

/**
 * MemoryReinforcementService – 장기 기억 저장소 관리 & hitCount 증분 UPSERT 처리
 * ▸ UNIQUE(source_hash) 충돌 시 hit_count++ UPSERT
 * ▸ 3/4/5‑파라미터 reinforce API 제공
 */
@Slf4j
@Service
@Transactional
public class MemoryReinforcementService {

    /**
     * status 코드 매핑: 1=ACTIVE, 0=INACTIVE
     */
    private static final int STATUS_ACTIVE = 1;

    /* ────── 점수 가중치/컷오프 ────── */
    @Value("${memory.reinforce.score.low-quality-threshold:0.3}")
    private double lowScoreCutoff;

    @Value("${memory.reinforce.score.weight.cosine:0.6}")
    private double scoreWeightCosine;
    @Value("${memory.reinforce.score.weight.bm25:0.3}")
    private double scoreWeightBm25;
    @Value("${memory.reinforce.score.weight.rank:0.1}")
    private double scoreWeightRank;

    /* ────── 프루닝(문장 절삭) 설정 ────── */
    @Value("${memory.reinforce.pruning.enabled:true}")
    private boolean pruningEnabled;

    /**
     * 남은 문장 비율(coverage) 최소치. 미만이면 저장 스킵
     */
    @Value("${memory.reinforce.pruning.min-coverage:0.2}")
    private double pruningMinCoverage;

    /**
     * 최종점수 가중: final = avgSim * (1 + coverageWeight * coverage)
     */
    @Value("${memory.reinforce.pruning.coverage-weight:0.1}")
    private double coverageWeight;
    /**
     * 저장 길이 제약 (가드)
     */
    @Value("${memory.reinforce.min-length:32}")
    private int minContentLength;
    @Value("${memory.reinforce.max-length:4000}")
    private int maxContentLength;

    /* ─────────────── DI ─────────────── */
    private final TranslationMemoryRepository memoryRepository;
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;
    private final VectorStoreService vectorStoreService;
    private final SnippetPruner snippetPruner; // ★ NEW

    /* ─────────────── dup cache ─────────────── */
    @Value("${memory.reinforce.cache.max-size:8192}")
    private int dupCacheSize;

    @Value("${memory.reinforce.cache.expire-minutes:10}")
    private long recentCacheMinutes;

    private LoadingCache<String, Boolean> recentSnippetCache;   // PostConstruct 초기화

    /**
     * 명시적 생성자 – Bean 주입
     */
    public MemoryReinforcementService(TranslationMemoryRepository memoryRepository,
                                      VectorStoreService vectorStoreService,
                                      SnippetPruner snippetPruner) {                      // ★ NEW
        this.memoryRepository = memoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.snippetPruner = snippetPruner;                                               // ★ NEW
    }

    @PostConstruct
    private void initRecentSnippetCache() {
        if (dupCacheSize <= 0) dupCacheSize = 8_192;
        if (recentCacheMinutes <= 0) recentCacheMinutes = 10;

        this.recentSnippetCache = Caffeine.newBuilder()
                .maximumSize(dupCacheSize)
                .expireAfterWrite(Duration.ofMinutes(recentCacheMinutes))
                .build(k -> Boolean.TRUE);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = DataIntegrityViolationException.class
    )
    public void reinforceWithSnippet(TranslationMemory t) {
        // 🛡️ Guard Clause: 잘못된 데이터는 미리 차단
        if (t == null || t.getSourceHash() == null) {
            log.warn("[Memory] 강화할 데이터가 null이거나 해시 키가 없어 스킵합니다.");
            return;
        }

        // ✅ 단일 try-catch 구조로 단순화
        try {
            // 1. 기본적으로 저장(INSERT)을 시도합니다.
            memoryRepository.save(t);
            log.debug("[Memory] INSERT 성공 (hash={})", t.getSourceHash().substring(0, 12));

        } catch (DataIntegrityViolationException dup) {
            // 2. INSERT 실패 시 (source_hash 중복), UPDATE로 전환합니다.
            log.debug("[Memory] 중복 해시 감지; UPDATE로 전환 (hash={})", t.getSourceHash().substring(0, 12));
            try {
                // 기존 레코드의 hitCount를 1 증가시킵니다.
                memoryRepository.incrementHitCountBySourceHash(t.getSourceHash());
            } catch (Exception updateEx) {
                // UPDATE 마저 실패할 경우 로그만 남기고 넘어갑니다.
                log.warn("[Memory] hitCount 증가 UPDATE 실패: {}", updateEx.toString());
            }

        } catch (Exception e) {
            // 3. 그 외 예상치 못한 오류는 기록만 하고 전체 프로세스가 중단되지 않도록 합니다. (Soft-fail)
            log.warn("[Memory] 강화 저장 중 예상치 못한 오류 발생 (soft-fail): {}", e.toString());
        }
    }

    private String safeHash(String h) {
        return (h == null || h.length() < 12) ? String.valueOf(h) : h.substring(0, 12);
    }




    /* ─────────────── Reward helper ─────────────── */
    private double reward(double rawScore) {
        try {
            TranslationMemory tmp = new TranslationMemory();
            tmp.setHitCount(0);                       // 신규 삽입 가정
            tmp.setCreatedAt(LocalDateTime.now());    // 현재 시각
            double r = rewardEngine.reinforce(tmp, null, rawScore);
            log.debug("[Reward] raw={} → reinforced={}", rawScore, r);
            return r;
        } catch (Exception ex) {
            log.warn("[Memory] rewardEngine 실패 – rawScore={} → fallback ({})",
                    rawScore, ex.getMessage());
            return rawScore;   // 🛡️ graceful degradation
        }
    }

    /* ─────────────── SID 규칙/검증 ─────────────── */
    private static final Pattern CHAT_SID =
            Pattern.compile("^(chat-\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|GLOBAL)$");

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) return null;
        String s = sessionId.trim();
        if (s.isEmpty()) return s;
        if (s.startsWith("chat-")) return s;
        if (s.matches("\\d+")) return "chat-" + s;
        return s;
    }

    private boolean isStableSid(String sid) {
        return sid != null && CHAT_SID.matcher(sid).matches();
    }

    /* ─────────────── 해시 유틸 ─────────────── */
    private String hash(String input) {
        return DigestUtils.sha256Hex(input == null ? "" : input);
    }

    /**
     * 저장용 해시: 스니펫 ‘본문’만 기준으로 dedupe(링크/공백 제거)
     */
    private String storageHashFromSnippet(String snippet) {
        if (snippet == null) return hash("");
        String canon = snippet
                .replaceAll("<\\/?a[^>]*>", " ")   // a태그 제거
                .replaceAll("\\s+", " ")           // 다중 공백 접기
                .trim();
        return hash(canon);
    }

    // ★ NEW: 사용자의 좋아요/싫어요(+수정문) 피드백을 메모리에 반영
    @Transactional(propagation = Propagation.REQUIRES_NEW,
    noRollbackFor = DataIntegrityViolationException.class)
    public void applyFeedback(String sessionId,
                              String messageContent,
                              boolean positive,
                              String correctedText) {
        // 0) 가드
        if (!StringUtils.hasText(messageContent)) {
            log.debug("[Feedback] empty message → skip");
            return;
        }
        String sid = normalizeSessionId(sessionId);
        if (!isStableSid(sid)) {
            log.warn("[Feedback] unstable SID → skip (sid={})", sessionId);
            return;
        }

        // 1) 원문 답변(assistant 출력) 해시
        String msgHash = storageHashFromSnippet(messageContent);

        try {
            if (positive) {
                // (1) 긍정: 우선 hitCount+1 시도
                int rows = 0;
                try {
                    rows = memoryRepository.incrementHitCountBySourceHash(msgHash);
                } catch (Exception e) {
                    log.debug("[Feedback] incrementHitCount failed, will upsert: {}", e.toString());
                }

                // (2) 존재하지 않으면 upsert 로 보상값 기록(컷오프 회피)
                //     ※ reinforceWithSnippet 은 lowScoreCutoff 때문에 음수/저점수 저장이 막힐 수 있어
                //        피드백은 반드시 upsertViaRepository 로 직접 기록합니다.
                double s = reward(0.95); // 높은 보상
                String payload = "[SRC:FEEDBACK_POS] " + messageContent;
                upsertViaRepository(sid, /*query*/ null, payload, "FEEDBACK_POS", s, msgHash);

                // (3) 벡터 색인(긍정일 때만)
                try {
                    vectorStoreService.enqueue(sid, messageContent);
                } catch (Exception ignore) {
                }
            } else {
                // 부정: 낮은 보상으로 명시 저장(컷오프 우회)
                double s = reward(0.02);
                String payload = "[SRC:FEEDBACK_NEG] " + messageContent;
                upsertViaRepository(sid, /*query*/ null, payload, "FEEDBACK_NEG", s, msgHash);
                // 벡터 색인은 하지 않음(오염 방지)
            }

            // 2) 수정문이 있으면 별도 레코드로 고품질 저장
            if (StringUtils.hasText(correctedText) && !correctedText.equals(messageContent)) {
                String refined = correctedText.trim();
                if (refined.length() > maxContentLength) {
                    refined = refined.substring(0, maxContentLength);
                }
                String corrHash = storageHashFromSnippet(refined);
                double sCorr = reward(0.98); // 수정문은 강한 보상
                String payloadCorr = "[SRC:USER_CORRECTION] " + refined;
                upsertViaRepository(sid, /*query*/ null, payloadCorr, "USER_CORRECTION", sCorr, corrHash);

                try {
                    vectorStoreService.enqueue(sid, refined);
                } catch (Exception ignore) {
                }
            }

            log.debug("[Feedback] applied (sid={}, pos={}, msgHash={})", sid, positive, msgHash.substring(0, 12));
        } catch (Exception e) {
            log.error("[Feedback] applyFeedback failed", e);
            throw e;
        }
    }
// ===== ▼▼▼ Backward‑compat shim methods ▼▼▼ =====

    /** 과거 호출부 호환: 스니펫(웹/어시스턴트 답변 등)을 기억 저장소에 강화 저장 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DataIntegrityViolationException.class)
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     String source,
                                     double score) {
        if (!StringUtils.hasText(snippet)) return;

        // 세션키 정규화 + 컨텐츠 길이 가드
        String sid = normalizeSessionId(sessionId);
        String text = snippet.trim();
        if (text.length() < minContentLength) return;
        if (text.length() > maxContentLength) text = text.substring(0, maxContentLength);

        // 중복 방지(최근 캐시) + 품질 컷오프
        String sourceHash = storageHashFromSnippet(text);
        if (recentSnippetCache.getIfPresent(sourceHash) != null) return; // 최근에 본 스니펫
        recentSnippetCache.put(sourceHash, Boolean.TRUE);

        double finalScore = reward(score);
        if (finalScore < lowScoreCutoff) {
            log.debug("[Memory] below cutoff → skip (score={})", finalScore);
            return;
        }

        // 저장(업서트) + 벡터 색인(가능하면)
        String payload = (StringUtils.hasText(source) ? "[SRC:" + source + "] " : "") + text;
        upsertViaRepository(sid, query, payload, source, finalScore, sourceHash);
        try { vectorStoreService.enqueue(sid, text); } catch (Exception ignore) {}
    }

    /** 과거 호출부 호환: 단순 텍스트를 GLOBAL 스코프로 강화 */
    public void reinforceMemoryWithText(String text) {
        if (!StringUtils.hasText(text)) return;
        reinforceWithSnippet("GLOBAL", null, text, "TEXT", 0.50);
    }

    /** 과거 호출부 호환: 세션별 메모리 컨텍스트를 문자열로 반환 */
    public String loadContext(String sessionId) {
        try {
            String sid = normalizeSessionId(sessionId);
            // JpaRepository 기본 API에만 의존(특화 쿼리 없어도 컴파일/동작)
            java.util.List<TranslationMemory> all = memoryRepository.findAll();
            if (all == null || all.isEmpty()) return "";

            // 세션 일치(또는 공용)만 추림
            java.util.List<TranslationMemory> filtered = all.stream()
                    .filter(tm -> {
                        String mSid = tryGetString(tm, "getSid", "getSessionId");
                        if (mSid == null || "*".equals(mSid)) return true;
                        return sid != null && sid.equals(mSid);
                    })
                    .collect(java.util.stream.Collectors.toList());

            if (filtered.isEmpty()) return "";

            // 중요도(히트) → 최근순 정렬
            java.util.Comparator<TranslationMemory> cmp =
                    java.util.Comparator.<TranslationMemory, Integer>
                                    comparing(tm -> tryGetInt(tm, "getHitCount"), java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                            .reversed()
                            .thenComparing(tm -> tryGetTime(tm, "getUpdatedAt", "getCreatedAt"),
                                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

            java.util.List<String> lines = filtered.stream()
                    .sorted(cmp)
                    .limit(20) // 너무 길어지지 않게 상한
                    .map(tm -> tryGetString(tm, "getContent", "getText", "getBody"))
                    .filter(StringUtils::hasText)
                    .map(s -> s.length() > maxContentLength ? s.substring(0, maxContentLength) : s)
                    .collect(java.util.stream.Collectors.toList());

            return String.join("\n\n---\n\n", lines);
        } catch (Exception e) {
            log.debug("[Memory] loadContext fallback: {}", e.toString());
            return "";
        }
    }

    private void upsertViaRepository(String sid,
                                     String query,
                                     String payload,
                                     String source,
                                     double score,
                                     String sourceHash) {
        // 1) 먼저 엔티티를 구성한다 (리플렉션으로 필드 호환)
        TranslationMemory tm = new TranslationMemory();
        trySet(tm, "setSourceHash", sourceHash, String.class);
        trySet(tm, "setSid", sid, String.class);
        trySet(tm, "setSessionId", sid, String.class);

        trySet(tm, "setQuery", query, String.class);
        trySet(tm, "setContent", payload, String.class);
        trySet(tm, "setText", payload, String.class);
        trySet(tm, "setBody", payload, String.class);

        trySet(tm, "setSourceType", source, String.class);
        trySet(tm, "setSource", source, String.class);

        trySet(tm, "setScore", score, double.class, Double.class);
        trySet(tm, "setStatus", STATUS_ACTIVE, int.class, Integer.class);
        trySet(tm, "setHitCount", 1, int.class, Integer.class);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        trySet(tm, "setCreatedAt", now, java.time.LocalDateTime.class);
        trySet(tm, "setUpdatedAt", now, java.time.LocalDateTime.class);

        // 2) INSERT → 중복이면 UPDATE(hitCount)
        try {
            memoryRepository.save(tm);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.debug("[Memory] duplicate source_hash; switching to hitCount");
            try {
                memoryRepository.incrementHitCountBySourceHash(sourceHash);
            } catch (Exception updateEx) {
                log.warn("[Memory] hitCount UPDATE failed: {}", updateEx.toString());
            }
        } catch (Exception e) {
            // soft-fail: 트랜잭션 전체를 깨지 않음
            log.debug("[Memory] upsertViaRepository soft-fail: {}", e.toString());
        }
    }

    /* ───────────── 리플렉션 유틸 ───────────── */

    private static void trySet(Object bean, String method, Object value, Class<?>... paramTypes) {
        for (Class<?> p : paramTypes) {
            try {
                java.lang.reflect.Method m = bean.getClass().getMethod(method, p);
                m.invoke(bean, value);
                return;
            } catch (Exception ignore) {}
        }
    }

    private static String tryGetString(Object bean, String... getters) {
        for (String g : getters) {
            try {
                java.lang.reflect.Method m = bean.getClass().getMethod(g);
                Object v = m.invoke(bean);
                if (v != null) return v.toString();
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static Integer tryGetInt(Object bean, String... getters) {
        for (String g : getters) {
            try {
                java.lang.reflect.Method m = bean.getClass().getMethod(g);
                Object v = m.invoke(bean);
                if (v instanceof Number n) return n.intValue();
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static java.time.LocalDateTime tryGetTime(Object bean, String... getters) {
        for (String g : getters) {
            try {
                java.lang.reflect.Method m = bean.getClass().getMethod(g);
                Object v = m.invoke(bean);
                if (v instanceof java.time.LocalDateTime t) return t;
            } catch (Exception ignore) {}
        }
        return null;
    }
// ===== ▲▲▲ Backward‑compat shim methods ▲▲▲ =====

}
