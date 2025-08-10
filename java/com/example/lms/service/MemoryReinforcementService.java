package com.example.lms.service;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

}
