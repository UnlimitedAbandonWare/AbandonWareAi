package com.example.lms.service;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.service.VectorStoreService; // 🔹 vector store service
import lombok.RequiredArgsConstructor;
import java.util.stream.Collectors;  // 이 줄을 추가해야 합니다.

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.github.benmanes.caffeine.cache.Caffeine;   // NEW – dup-cache
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils; // SHA‑256 helper
import java.util.regex.Pattern;
import java.time.LocalDateTime;              // ★ NEW (dummy Memory 생성용)
/**
 * MemoryReinforcementService – 장기 기억 저장소 관리 & hitCount 증분 UPSERT 처리
 * <p>
 * ▸ 중복 해시가 들어오면 Unique 제약 오류 대신 hit_count만 올리는 UPSERT 패턴 적용.
 * ▸ NaverSearchService 와의 시그니처 호환을 위해 3‑/4‑/5‑파라미터 reinforce API 제공.
 */
@Slf4j
@Service
@Transactional
// @RequiredArgsConstructor  // 명시적 생성자를 쓰므로 Lombok 자동생성 비활성화
public class MemoryReinforcementService {
    /** status 코드 매핑: 1=ACTIVE, 0=INACTIVE */
    private static final int STATUS_ACTIVE = 1;
    /* ────── 설정값 (@Value 로 외부화) ────── */
    @Value("${memory.reinforce.score.low-quality-threshold:0.3}")
    private double lowScoreCutoff;

    @Value("${memory.reinforce.score.weight.cosine:0.6}")
    private double scoreWeightCosine;
    @Value("${memory.reinforce.score.weight.bm25:0.3}")
    private double scoreWeightBm25;
    @Value("${memory.reinforce.score.weight.rank:0.1}")
    private double scoreWeightRank;

    /* ─────────────── DI ─────────────── */
    private final TranslationMemoryRepository   memoryRepository;
    /** Reward V2 – 유사도·인기도·기간 가중 통합 */
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;
    /** Service responsible for indexing snippets into the vector store. */
    private final VectorStoreService vectorStoreService;
    /** SHA-256 (null-safe) */
    private String hash(String input) { return DigestUtils.sha256Hex(input == null ? "" : input); }
    /** 저장용 해시: 키/태그와 무관하게 <snippet 본문>만으로 중복 판단 (두 개 키 동시 검색 시 충돌 제거) */
    private String storageHashFromSnippet(String snippet) {
        if (snippet == null) return hash("");
        // 링크/태그와 공백 차이를 흡수한 ‘본문 기반’ 정규화
        String canon = snippet
                .replaceAll("<\\/?a[^>]*>", " ")   // a태그 제거
                .replaceAll("\\s+", " ")           // 다중 공백 접기
                .trim();
        return hash(canon);
    }
    /* ─────────────── Reward helper ─────────────── */
    /**
     * RewardScoringEngine 은 <code>reinforce(...)</code> 한 가지 API만
     * 노출하므로, 임시 {@link TranslationMemory} 객체를 만들어 호출한다.
     *
     * <p>유사도(similarity) 인자로 <b>원 rawScore</b>를 전달하여
     * 엔진이 ‘유사도 정책’(SimilarityPolicy)만 적용하도록 유도한다.
     */

/* 최근 동일 snippet(본문 기준) Reinforce 차단 */
    @Value("${memory.reinforce.cache.max-size:8192}")
    private int dupCacheSize;
    // ※ 중복 검사·SID 안정성 검증 로직을 하나의 private 메서드 `isInvalidOrDuplicate(...)`
//   로 옮겼습니다. reinforceWithSnippet(...) 양쪽 버전에서 호출만 하도록 변경.

    @Value("${memory.reinforce.cache.expire-minutes:10}")
    private long recentCacheMinutes;

    /** 중복 방지 캐시 – 생성자에서 불변 초기화 */
    private LoadingCache<String, Boolean> recentSnippetCache;   // PostConstruct 초기화

    /** Lombok 대신 명시적 생성자로 캐시·필드 완전 초기화 */
    public MemoryReinforcementService(
            TranslationMemoryRepository memoryRepository,
            VectorStoreService vectorStoreService) {
        this.memoryRepository   = memoryRepository;
        this.vectorStoreService = vectorStoreService;
        // cache 는 PostConstruct 에서 dupCacheSize 가 주입된 뒤 초기화
    }
    /* ────────────────────────────────────────────────
     *  Bean 초기화 이후(@Value 주입 완료) 캐시 재구성
     * ──────────────────────────────────────────────── */
    @PostConstruct
    private void initRecentSnippetCache() {
        if (dupCacheSize <= 0)        dupCacheSize        = 8_192;
        if (recentCacheMinutes <= 0)  recentCacheMinutes  = 10;

        this.recentSnippetCache = Caffeine.newBuilder()
                .maximumSize(dupCacheSize)
                .expireAfterWrite(Duration.ofMinutes(recentCacheMinutes))
                .build(k -> Boolean.TRUE);
    }

    private double reward(double rawScore) {
        try {
            TranslationMemory tmp = new TranslationMemory();
            tmp.setHitCount(0);                       // 신규 삽입 가정
            tmp.setCreatedAt(LocalDateTime.now());    // 현재 시각
            // queryText 는 현재 단계에서 사용하지 않으므로 null
            double r = rewardEngine.reinforce(tmp, null, rawScore);
            log.debug("[Reward] raw={} → reinforced={}", rawScore, r);
            return r;
        } catch (Exception ex) {
            log.warn("[Memory] rewardEngine 실패 – rawScore={} → fallback ({})",
                    rawScore, ex.getMessage());
            return rawScore;   // 🛡️ graceful degradation
        }
    }
    /** 안정 SID 규칙: "chat-<digits>" | <UUID> | GLOBAL 허용 */
    private static final Pattern CHAT_SID =
            Pattern.compile("^(chat-\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|GLOBAL)$");
    /** 세션 ID 정규화: 숫자형이면 "chat-<id>"로 통일 */
    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) return null;
        String s = sessionId.trim();
        if (s.isEmpty()) return s;
        if (s.startsWith("chat-")) return s;
        if (s.matches("\\d+")) return "chat-"+  s;
        return s; // 그 외(UUID 등)는 그대로 두되, 아래 가드에서 차단
    }
    /** 안정 SID 여부 */
    private boolean isStableSid(String sid) {
        return sid != null && CHAT_SID.matcher(sid).matches();
    }


    /* ════════════════ UPSERT 핵심 로직 ════════════════ */
    /**
     * translation_memory 테이블에 INSERT 시도 후 UNIQUE(source_hash) 충돌 시
     * hit_count만 1 증가시키는 UPSERT 패턴.
     */
// MemoryReinforcementService.java
    /** 단일 UPSERT 실행 (Deadlock·Lock wait 소량 재시도) */
    @Transactional(propagation = Propagation.REQUIRED)
    private void upsertViaRepository(String sessionId,
                                     String query,
                                     String content,
                                     String sourceTag,
                                     double score,
                                     String sourceHash) {
        final int maxRetry = 3;
        final double reward = score;    // rewardEngine은 상단에서 score 계산 시 이미 반영
        final double qValue = 0.0;      // 필요 시 값 공급
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

    /**
     * 임의의 긴 텍스트를 장기 기억으로 저장.
     * (현재 구현은 단순 로그/샘플 – 실제 저장 로직으로 대체 필요)
     */
    public void reinforceMemoryWithText(String text) {
        if (!StringUtils.hasText(text)) return;
        log.debug("[Memory] store text len={} ...", text.length());
        // TODO: 실제 저장 로직(DB·벡터스토어 등) 구현
        // 🔹 장문 텍스트도 버퍼에 적재
        // 안정 sid가 없으므로 장문 텍스트는 세션 메모리로 보존하지 않는다(오염 방지).
        // 필요 시 별도 document-store로만 보냄.
        vectorStoreService.enqueue("0", text);
    }
    /* ─────────────────────────────────────────────
     * [A]  3‑파라미터(레거시)  – score 미사용
     * [B]  5‑파라미터(정식)    – query/score 포함
     * [C]  4‑파라미터(편의)    – rank → score 환산
     * ───────────────────────────────────────────── */

    // [A] 레거시: query/score 없이 snippet + tag 만 저장
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId, String snippet, String tag) {
        if (!StringUtils.hasText(snippet)) return;
        // 정책: ASSISTANT/정보 없음 저장 금지 (오염 방지)
        if ("ASSISTANT".equalsIgnoreCase(tag)) return;
        String trimmed = snippet.trim();
        if ("정보 없음".equals(trimmed) || "정보 없음.".equals(trimmed)) return;

        String sid = normalizeSessionId(sessionId);
        if (!isStableSid(sid)) {
            log.warn("[Memory] unstable SID → skip store (sid={})", sessionId);
            return; // 🔥 UUID 등 불안정 키로는 저장 금지(핫픽스)
        }
        // 🔁 중복 컷: snippet 본문만 기준(키/태그 무관)
        String h = storageHashFromSnippet(snippet);
        if (recentSnippetCache.getIfPresent(h) != null) return; // 🔁 중복 컷
        if (recentSnippetCache.getIfPresent(h) == null) {
            recentSnippetCache.put(h, Boolean.TRUE);
        }

        // 저장은 payload로 하되, 해시는 snippet 본문 기준
        String payload = "[SRC:" + tag + "] " + snippet;
        double s = reward(1.0); // 레거시 기본 1.0 → 보상적용
        upsertViaRepository(sid, null, payload, tag, s, h);
        // 🔹 벡터 스토어 버퍼에 적재
        vectorStoreService.enqueue(sid, snippet);
    }

    /** bm25 / cosine / rank 기반 정규화 스코어 계산(0,1] – 인스턴스 메서드로 전환 */
    public double normalizeScore(Double bm25, Double cosine, Integer rank) {
        double sRank = (rank == null || rank < 1) ? 0.0 : 1.0 / rank;
        double sCos  = (cosine == null) ? 0.0 : Math.max(0.0, Math.min(1.0, cosine));
        double sBm   = (bm25   == null) ? 0.0 : 1.0 - Math.exp(-Math.max(0.0, bm25));
        double s     = scoreWeightCosine * sCos
                + scoreWeightBm25   * sBm
                + scoreWeightRank   * sRank;
        return Math.max(1e-6, Math.min(1.0, s));
    }   // ←★★ normalizeScore 닫는 중괄호 추가

    // [B] 정식 5-파라미터: query / score
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     String sourceTag,
                                     double score) {

        if (!StringUtils.hasText(snippet)) return;
        // 정책: ASSISTANT/정보 없음 저장 금지
        if ("ASSISTANT".equalsIgnoreCase(sourceTag)) return;
        String trimmed = snippet.trim();
        if ("정보 없음".equals(trimmed) || "정보 없음.".equals(trimmed)) return;
        // 점수 클램프 & 저품질 차단
        // ① NaN/음수 방지 → ② [0,1] 클램프 → ③ Reward → ④ 저품질 컷
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
        String payload = "[SRC:" + sourceTag + "] " + snippet;
        // 🔁 키 두 개로 동일 스니펫이 와도 해시는 snippet 본문 기준으로 통일
        String h = storageHashFromSnippet(snippet);
        if (recentSnippetCache.getIfPresent(h) != null) return; // 🔁 중복 컷
        if (recentSnippetCache.getIfPresent(h) == null) {
            recentSnippetCache.put(h, Boolean.TRUE);
        }
        upsertViaRepository(sid, query, payload, sourceTag, s, h);

        vectorStoreService.enqueue(sid, snippet);
    }



    // [C] rank 기반 편의 API (rank 1 → 1.0, 2 → 0.5, ...)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     int rank /* 1‑based */) {
        if (!StringUtils.hasText(snippet)) return;
            double normalizedScore = 1.0 / Math.max(rank, 1);
        if (Double.isNaN(normalizedScore) || normalizedScore <= 0.0) {
            normalizedScore = 0.5; // 안전 기본값
        }
            reinforceWithSnippet(sessionId, query, snippet, "WEB", normalizedScore);
    }

    /* ════════════════ 메모리 읽기 ════════════════ */
    public String loadContext(String sessionId) {
        String sid = normalizeSessionId(sessionId);
        log.debug("[Memory] loadContext sid={}", sid);
        // 🔥 안정 SID가 아니면 메모리 주입 중단(핫픽스: 오염 차단)
        if (!isStableSid(sid)) {
            log.warn("[Memory] unstable SID → skip load (sid={})", sessionId);
            return "";
        }
        // 점수 NULL 제외  (score × cosine_similarity) 가중 정렬  상위 N만 주입
        final int limit = 8;
        return memoryRepository.findTopRankedBySessionId(sid, limit).stream()
                .map(TranslationMemory::getContent)
                .collect(Collectors.joining("\n"));
    }

    /* ════════════════ 비동기 hitCount++ 지원 ════════════════ */
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
            throw e; // rollback
        }
    }

}
