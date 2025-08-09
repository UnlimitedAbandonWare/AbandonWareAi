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

    /** status 코드 매핑: 1=ACTIVE, 0=INACTIVE */
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

    /** 남은 문장 비율(coverage) 최소치. 미만이면 저장 스킵 */
    @Value("${memory.reinforce.pruning.min-coverage:0.2}")
    private double pruningMinCoverage;

    /** 최종점수 가중: final = avgSim * (1 + coverageWeight * coverage) */
    @Value("${memory.reinforce.pruning.coverage-weight:0.1}")
    private double coverageWeight;

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

    /** 명시적 생성자 – Bean 주입 */
    public MemoryReinforcementService(TranslationMemoryRepository memoryRepository,
                                      VectorStoreService vectorStoreService,
                                      SnippetPruner snippetPruner) {                      // ★ NEW
        this.memoryRepository = memoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.snippetPruner = snippetPruner;                                               // ★ NEW
    }

    @PostConstruct
    private void initRecentSnippetCache() {
        if (dupCacheSize <= 0)       dupCacheSize       = 8_192;
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
    private String hash(String input) { return DigestUtils.sha256Hex(input == null ? "" : input); }

    /** 저장용 해시: 스니펫 ‘본문’만 기준으로 dedupe(링크/공백 제거) */
    private String storageHashFromSnippet(String snippet) {
        if (snippet == null) return hash("");
        String canon = snippet
                .replaceAll("<\\/?a[^>]*>", " ")   // a태그 제거
                .replaceAll("\\s+", " ")           // 다중 공백 접기
                .trim();
        return hash(canon);
    }

    /* ════════════════ UPSERT 핵심 ════════════════ */
    @Transactional(propagation = Propagation.REQUIRED)
    private void upsertViaRepository(String sessionId,
                                     String query,
                                     String content,
                                     String sourceTag,
                                     double score,
                                     String sourceHash) {
        final int maxRetry = 3;
        final double reward = score;    // rewardEngine 가중은 상단에서 반영
        final double qValue = 0.0;

        for (int i = 1; i <= maxRetry; i++) {
            try {
                memoryRepository.upsertReward(
                        sessionId, sourceHash, content, query, score,
                        sourceTag, qValue, reward,
                        STATUS_ACTIVE,
                        /*cosSim*/ null, /*cosCorr*/ null
                );
                return;
            } catch (Exception ex) {
                if (i == maxRetry) throw ex;
                try { Thread.sleep(40L * i); } catch (InterruptedException ignored) {}
            }
        }
    }

    /* ════════════════ 퍼블릭 API ════════════════ */

    /** 임의의 장문 텍스트를 장기 기억으로 저장(데모/샘플) */
    public void reinforceMemoryWithText(String text) {
        if (!StringUtils.hasText(text)) return;
        log.debug("[Memory] store text len={} ...", text.length());
        vectorStoreService.enqueue("0", text);
    }

    /** bm25 / cosine / rank 기반 정규화 스코어(0,1] */
    public double normalizeScore(Double bm25, Double cosine, Integer rank) {
        double sRank = (rank == null || rank < 1) ? 0.0 : 1.0 / rank;
        double sCos  = (cosine == null) ? 0.0 : Math.max(0.0, Math.min(1.0, cosine));
        double sBm   = (bm25   == null) ? 0.0 : 1.0 - Math.exp(-Math.max(0.0, bm25));
        double s     = scoreWeightCosine * sCos
                + scoreWeightBm25   * sBm
                + scoreWeightRank   * sRank;
        return Math.max(1e-6, Math.min(1.0, s));
    }

    /* [A] 레거시: query/score 없이 snippet + tag 만 저장 (프루닝 적용 안 함) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId, String snippet, String tag) {
        if (!StringUtils.hasText(snippet)) return;
        if ("ASSISTANT".equalsIgnoreCase(tag)) return; // 오염 방지
        String trimmed = snippet.trim();
        if ("정보 없음".equals(trimmed) || "정보 없음.".equals(trimmed)) return;

        String sid = normalizeSessionId(sessionId);
        if (!isStableSid(sid)) {
            log.warn("[Memory] unstable SID → skip store (sid={})", sessionId);
            return;
        }

        // dedupe(본문 기준)
        String h = storageHashFromSnippet(snippet);
        if (recentSnippetCache.getIfPresent(h) != null) return;
        recentSnippetCache.put(h, Boolean.TRUE);

        String payload = "[SRC:" + tag + "] " + snippet;
        double s = reward(1.0); // 레거시 기본 1.0 → 보상적용
        upsertViaRepository(sid, null, payload, tag, s, h);

        vectorStoreService.enqueue(sid, snippet);
    }

    /* [B] 정식: query/score 포함 (★ 프루닝/재검증 적용) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     String sourceTag,
                                     double score) {
        if (!StringUtils.hasText(snippet)) return;
        if ("ASSISTANT".equalsIgnoreCase(sourceTag)) return;
        String trimmed = snippet.trim();
        if ("정보 없음".equals(trimmed) || "정보 없음.".equals(trimmed)) return;

        // 1) 1차: 저품질 컷(기존)
        if (Double.isNaN(score) || Double.isInfinite(score)) score = 0.0;
        double s = Math.max(0.0001, Math.min(1.0, score));
        s = reward(s);
        if (s < lowScoreCutoff) {
            log.debug("[Memory] low-score snippet skipped (score={})", s);
            return;
        }

        String sid = normalizeSessionId(sessionId);
        if (!isStableSid(sid)) {
            log.warn("[Memory] unstable SID → skip store (sid={})", sessionId);
            return;
        }

        // 2) 2차: 문장 단위 절삭(계층적 프루닝) + 재검증
        SnippetPruner.Result pruned = pruningEnabled && StringUtils.hasText(query)
                ? snippetPruner.prune(query, snippet)
                : SnippetPruner.Result.passThrough(snippet);

        if (pruned.keptSentences() <= 0) {
            log.debug("[Memory] pruned to 0 sentence → skip");
            return;
        }
        if (pruned.coverage() < pruningMinCoverage) {
            log.debug("[Memory] coverage<min → skip (cov={} < {})", pruned.coverage(), pruningMinCoverage);
            return;
        }

        // 3) 최종 점수: avgSim & coverage 반영
        double finalScore = pruned.avgSimilarity() * (1.0 + coverageWeight * pruned.coverage());
        finalScore = Math.max(0.0001, Math.min(1.0, finalScore));

        // 4) dedupe는 '정제된 본문' 기준으로
        String refined = pruned.refined();
        String h = storageHashFromSnippet(refined);
        if (recentSnippetCache.getIfPresent(h) != null) return;
        recentSnippetCache.put(h, Boolean.TRUE);

        // 5) 저장/색인: 정제된 본문으로
        String payload = "[SRC:" + sourceTag + "] " + refined;
        upsertViaRepository(sid, query, payload, sourceTag, finalScore, h);

        vectorStoreService.enqueue(sid, refined);
    }

    /* [C] rank 기반 편의 API (rank 1 → 1.0, 2 → 0.5, ...) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     int rank /* 1‑based */) {
        if (!StringUtils.hasText(snippet)) return;
        double normalizedScore = 1.0 / Math.max(rank, 1);
        if (Double.isNaN(normalizedScore) || normalizedScore <= 0.0) {
            normalizedScore = 0.5;
        }
        reinforceWithSnippet(sessionId, query, snippet, "WEB", normalizedScore);
    }

    /* ════════════════ 메모리 읽기 ════════════════ */
    public String loadContext(String sessionId) {
        String sid = normalizeSessionId(sessionId);
        log.debug("[Memory] loadContext sid={}", sid);
        if (!isStableSid(sid)) {
            log.warn("[Memory] unstable SID → skip load (sid={})", sessionId);
            return "";
        }
        final int limit = 8;
        return memoryRepository.findTopRankedBySessionId(sid, limit).stream()
                .map(TranslationMemory::getContent)
                .collect(Collectors.joining("\n"));
    }

    /* ════════════════ 비동기 hitCount++ ════════════════ */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceAsync(String text) {
        String h = storageHashFromSnippet(text);
        try {
            int rows = memoryRepository.incrementHitCountBySourceHash(h);
            if (rows > 0) {
                log.debug("[Memory] hitCount+1 ✅ hash={}...", h.substring(0, 12));
            } else {
                log.warn("[Memory] 대상 없음 ⚠️ hash={}...", h.substring(0, 12));
            }
        } catch (Exception e) {
            log.error("[Memory] hitCount 업데이트 실패 ❌ hash={}...", h.substring(0, 12), e);
            throw e;
        }
    }
}
