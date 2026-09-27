package com.example.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import java.util.regex.Pattern;
import com.example.lms.service.VectorStoreService;
import com.example.lms.domain.ChatMessage;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.service.reinforcement.SnippetPruner;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import java.time.Duration; // ★ NEW
import java.time.LocalDateTime; // ★ NEW

import java.nio.charset.StandardCharsets; // NEW
import java.security.MessageDigest; // NEW
import org.springframework.dao.DataIntegrityViolationException; // ⬅️ 누락 import 추가
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.domain.enums.MemoryGateProfile;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadata;

@Service
@Transactional
public class MemoryReinforcementService {
    @Value("${jammini.memory.progressive:true}")
    private boolean progressiveMemoryMode;

    @Autowired
    private GuardProfileProps guardProfileProps;

    @Autowired(required = false)
    private VectorPoisonGuard vectorPoisonGuard;

    private static final Logger log = LoggerFactory.getLogger(MemoryReinforcementService.class);

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    // [NEW] Quality metadata for reinforcement gating (Projection Jammini)
    public record QualityMetadata(
            double fusionScore,
            double evidenceScore,
            String planId,
            boolean hasCitation) {
    }

    /** Fixed neutral signal when the durable rated assistant no longer exists. */
    public static final class RatedRecordNotFoundException extends RuntimeException {
        private RatedRecordNotFoundException() {
            super("rated_record_not_found");
        }

        public static RatedRecordNotFoundException notFound() {
            return new RatedRecordNotFoundException();
        }
    }

    // ─────────────────────────────────────────────────────────
    // Vector store meta helpers (오염 추적/필터링을 위한 표준 메타)
    // ─────────────────────────────────────────────────────────

    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)\\d+\\]");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    /**
     * 소스 태그 정규화.
     * - ASSISTANT/LLM/AI_GENERATED → ASSISTANT
     * - USER/USER_CORRECTION → USER
     * - OFFICIAL → OFFICIAL
     * - 그 외/미지정 → WEB (또는 UNKNOWN)
     */
    private String normalizeSourceTag(String tag) {
        if (tag == null)
            return "UNKNOWN";
        String t = tag.toUpperCase(java.util.Locale.ROOT);
        return switch (t) {
            case "ASSISTANT", "LLM", "AI_GENERATED" -> "ASSISTANT";
            case "USER", "USER_CORRECTION" -> "USER";
            case "OFFICIAL" -> "OFFICIAL";
            case "ATTENDANCE", "ASSIGNMENT", "SUBMISSION", "GRADE", "FEEDBACK", "AUTOLEARN", "RAG_OPS" -> t;
            default -> "WEB";
        };
    }

    /** origin 도출 */
    private String deriveOrigin(String sourceTag) {
        String normalized = normalizeSourceTag(sourceTag);
        return switch (normalized) {
            case "ASSISTANT" -> "LLM";
            case "USER", "SUBMISSION", "FEEDBACK" -> "USER";
            case "OFFICIAL", "WEB" -> "WEB";
            case "ATTENDANCE", "ASSIGNMENT", "GRADE", "AUTOLEARN", "RAG_OPS" -> "SYSTEM";
            default -> "SYSTEM";
        };
    }

    private int detectCitationCount(String text) {
        if (text == null || text.isBlank())
            return 0;
        int count = 0;

        // URL count
        try {
            var m = URL_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("citation.urlPattern", ignore);
        }

        // Evidence marker count
        try {
            var m = CITATION_MARKER_PATTERN.matcher(text);
            while (m.find())
                count++;
        } catch (Exception ignore) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("citation.markerPattern", ignore);
        }

        return count;
    }

    private boolean detectCitationSignal(String text) {
        return detectCitationCount(text) > 0;
    }

    /**
     * 벡터스토어 enqueue 시 표준 메타를 구성.
     *
     * <p>
     * Map.of()는 null에 취약하므로 HashMap 사용.
     * </p>
     */
    private Map<String, Object> buildVectorMeta(String sourceTag, boolean hasCitation, int citationCount) {
        Map<String, Object> meta = new HashMap<>();
        String st = normalizeSourceTag(sourceTag);
        meta.put(VectorMetaKeys.META_SOURCE_TAG, st);
        meta.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "MEMORY");
        meta.put(VectorMetaKeys.META_ORIGIN, deriveOrigin(st));
        meta.put(VectorMetaKeys.META_VERIFIED, String.valueOf(hasCitation));
        meta.put(VectorMetaKeys.META_CITATION_COUNT, citationCount);
        return meta;
    }

    private void enqueueVectorSafe(String sid, String text, Map<String, Object> meta, String stage) {
        if (vectorStoreService == null) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }

        String session = (sid == null || sid.isBlank()) ? "__TRANSIENT__" : sid.trim();
        Map<String, Object> m = (meta == null) ? new HashMap<>() : new HashMap<>(meta);
        m.putIfAbsent(VectorMetaKeys.META_DOC_TYPE, "MEMORY");

        if (vectorPoisonGuard != null) {
            try {
                VectorPoisonGuard.IngestDecision dec = vectorPoisonGuard.inspectIngest(session, text, m,
                        (stage == null ? "memory.reinforcement" : stage));
                if (dec == null || !dec.allow()) {
                    log.debug("[MemoryReinforcement] blocked by poison guard stage={} sidHash={} reason={}",
                            stage, com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(session)), dec != null ? dec.reason() : "null");
                    return;
                }
                String payload = dec.text();
                Map<String, Object> mm = (dec.meta() == null) ? m : dec.meta();
                vectorStoreService.enqueue(session, payload, mm);
                return;
            } catch (Throwable t) {
                // Fail-safe: do not enqueue on guard error (better drop than poison)
                log.warn("[MemoryReinforcement] guard error; skip enqueue stage={} sidHash={} : {}", stage,
                        com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(session)),
                        errorSummary(t));
                return;
            }
        }

        vectorStoreService.enqueue(session, text, m);
    }

    // ===== [볼츠만/담금질 상수] =====
    private static final double W_SIM = 1.0;
    private static final double W_Q = 0.25;
    private static final double W_SR = 0.50;
    private static final double W_EXPL = 0.10;
    private static final double W_TAG = 0.10; // 출처 태그 보너스(낮을수록 에너지↓)
    private static final double T0 = 1.0;

    private static final int STATUS_ACTIVE = 1;

    /* ────── 점수 가중치/컷오프 ────── */
    @Value("${memory.reinforce.score.low-quality-threshold:0.3}")
    private double lowScoreCutoff;

    // Reinforcement mode: CONSERVATIVE | EXPLORE (기본값: CONSERVATIVE)
    @Value("${memory.reinforce.mode:CONSERVATIVE}")
    private String reinforcementMode;

    // ⬅️ 누락된 길이 정책 필드 추가
    @Value("${memory.snippet.min-length:40}")
    private int minContentLength;
    @Value("${memory.snippet.max-length:4000}")
    private int maxContentLength;

    // Citation gate mode: STRICT | SOFT (기본값: SOFT)
    @Value("${memory.citation.gate-mode:SOFT}")
    private String citationGateMode;

    // 최소 인용 마커 개수 (예: [W1], [V2], '출처:' 등)
    @Value("${memory.citation.min-evidence-markers:1}")
    private int minEvidenceMarkers;

    // MERGE_HOOK:PROJ_AGENT::MEMORY_ENABLED_FALLBACK_V1
    @Value("${memory.enabled:${memory.write.enabled:false}}")
    private boolean memoryEnabled;

    @Value("${uaw.autolearn.memory-reinforcement.enabled:false}")
    private boolean autolearnMemoryReinforcementEnabled;

    @Value("${memory.reinforce.save-weak-as-pending:true}")
    private boolean saveWeakAsPending;

    @Value("${memory.reinforce.pending-min-score:0.55}")
    private double pendingMinScore;

    /* ─────────────── DI ─────────────── */
    private final TranslationMemoryRepository memoryRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;
    private final VectorStoreService vectorStoreService;
    private final SnippetPruner snippetPruner;
    private final com.example.lms.service.config.HyperparameterService hp; // ★ NEW

    private LoadingCache<String, Boolean> recentSnippetCache;

    // ▼▼ 신규 DI
    private final com.example.lms.strategy.StrategyPerformanceRepository perfRepo;
    private final com.example.lms.strategy.StrategyDecisionTracker strategyTracker;

    public MemoryReinforcementService(TranslationMemoryRepository memoryRepository,
            VectorStoreService vectorStoreService,
            SnippetPruner snippetPruner,
            com.example.lms.strategy.StrategyPerformanceRepository perfRepo,
            com.example.lms.strategy.StrategyDecisionTracker strategyTracker,
            com.example.lms.service.config.HyperparameterService hp) {
        this.memoryRepository = memoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.snippetPruner = snippetPruner;
        this.perfRepo = perfRepo;
        this.strategyTracker = strategyTracker;
        this.hp = hp; // ★ NEW
    }

    @PostConstruct
    private void initRecentSnippetCache() {
        // 캐시를 실제 초기화 (10분 유지, 최대 10k건)
        // 중복 판정은 getIfPresent로만 수행 → put으로 ‘본 것’을 마킹
        this.recentSnippetCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(10_000)
                .recordStats()
                .build(key -> Boolean.TRUE); // 로더는 사용하지 않지만 타입상 LoadingCache 유지
    }

    /** 0~1 보상값 클램프 */
    private double reward(double base) {
        if (Double.isNaN(base) || Double.isInfinite(base))
            return 0.0;
        return Math.max(0.0, Math.min(1.0, base));
    }

    private void upsertPendingCandidate(
            String sid,
            String query,
            String payload,
            String sourceTag,
            double score,
            String hash) {
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        if (payload == null || payload.isBlank())
            return;
        if (hash == null || hash.isBlank())
            return;

        TranslationMemory tm = memoryRepository.findBySourceHash(hash)
                .orElseGet(() -> new TranslationMemory(hash));

        // basic fields
        tm.setSourceHash(hash);
        tm.setSessionId((sid == null || sid.isBlank()) ? "__TRANSIENT__" : sid);
        tm.setQuery(query);
        tm.setContent(payload);
        tm.setSourceTag(sourceTag);
        tm.setScore(reward(score));
        tm.setStatus(TranslationMemory.MemoryStatus.PENDING);

        try {
            memoryRepository.save(tm);
        } catch (Exception e) {
            log.warn("[MEMORY] Failed to save PENDING candidate: {}", errorSummary(e));
        }
    }

    /** 간단 UPSERT: source_hash 기준으로 존재하면 갱신, 없으면 신규 생성 */
    private void upsertViaRepository(String sid,
            String query,
            String payload,
            String sourceTag,
            double score,
            String hash) {
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        // When no existing record is found, construct a new TranslationMemory with the
        // computed source hash. Using a lambda avoids relying on a Lombok-generated
        // no-arg constructor that may not be present if annotation processing fails.
        TranslationMemory tm = memoryRepository.findBySourceHash(hash)
                .orElseGet(() -> new TranslationMemory(hash));

        if (tm.getId() == null) {
            tm.setSourceHash(hash);
            // [HARDENING] use __TRANSIENT__ instead of wildcard for unknown session
            tm.setSessionId((sid == null || sid.isBlank()) ? "__TRANSIENT__" : sid);
            // 존재하는 수치 필드만 안전하게 초기화
            tm.setHitCount(0);
            tm.setSuccessCount(0);
            tm.setFailureCount(0);
            tm.setCosineSimilarity(0.0);
            tm.setSourceTag(sourceTag); // + 최초 생성 시 출처 태그 보존
            tm.setQValue(0.0);
        }
        // 기존 엔티티에도 최신 태그를 반영(옵션)
        if (sourceTag != null && !sourceTag.isBlank()) {
            tm.setSourceTag(sourceTag);
        }

        // 점수는 0.0 ~ 1.0 범위로 클램프해서 엔티티에 반영
        double clampedScore = reward(score);
        tm.setScore(clampedScore);
        // 관측 1회
        tm.setHitCount(tm.getHitCount() + 1);
        if (clampedScore >= 0.5)
            tm.setSuccessCount(tm.getSuccessCount() + 1);
        else
            tm.setFailureCount(tm.getFailureCount() + 1);

        // Q-value: 지수이동평균(EMA) 형태로 업데이트 (0.2 반영률)
        double prevQ = tm.getQValue();
        tm.setQValue(prevQ + 0.2 * (clampedScore - prevQ));

        // 에너지/온도 계산 및 반영
        double energy = this.computeBoltzmannEnergy(tm); // ★ CHG
        double temp = this.annealTemperature(tm.getHitCount());// ★ CHG
        tm.setEnergy(energy);
        tm.setTemperature(temp);

        // DB 반영 (세션 격리 정책 우선)
        int updated = (tm.getSessionId() != null)
                ? memoryRepository.updateEnergyByHashAndSession(hash, tm.getSessionId(), energy, temp)
                : memoryRepository.updateEnergyByHash(hash, energy, temp);

        if (updated == 0) {
            // 신규 등으로 업데이트 0이면 save
            memoryRepository.save(tm);
        }
    }

    /*
     * =========================================================
     * 볼츠만 에너지 & 담금질 (기존 시그니처 유지)
     * =========================================================
     */

    // ★ CHG: 인스턴스 메서드로 전환하여 동적 하이퍼파라미터 반영
    private double computeBoltzmannEnergy(TranslationMemory tm) {
        if (tm == null)
            return 0.0;

        double cosSim = (tm.getCosineSimilarity() == null ? 0.0 : tm.getCosineSimilarity());
        Double qObj = tm.getQValue(); // <= null-safe
        double qValue = (qObj == null ? 0.0 : qObj);

        int hit = tm.getHitCount();
        int success = tm.getSuccessCount();
        int failure = tm.getFailureCount();

        double successRatio = (hit <= 0) ? 0.0 : (double) (success - failure) / hit;
        double exploreTerm = 1.0 / Math.sqrt(hit + 1.0);

        // SMART_FALLBACK 태그 보너스(선호도↑ → 에너지↓)
        double tagBonus = 0.0;
        try {
            String tag = tm.getSourceTag();
            if (tag != null && "SMART_FALLBACK".equalsIgnoreCase(tag)) {
                tagBonus = W_TAG; // 0.10 가중치
            }
        } catch (Exception e) {
            log.debug("[Memory] Failed to inspect TranslationMemory sourceTag: {}", errorSummary(e));
        }

        // ★ NEW: 신뢰도/재사용 최신성(Recency) 반영
        double conf = (tm.getConfidenceScore() == null ? 0.0 : tm.getConfidenceScore());
        double wConf = hp.getDouble("energy.weight.confidence", 0.20);
        // 최근 사용할수록 recTerm↑ → 에너지↓
        double tauH = hp.getDouble("energy.recency.tauHours", 72.0);
        double recTerm = 0.0;
        LocalDateTime lu = tm.getLastUsedAt();
        if (lu != null) {
            double ageH = Math.max(0.0, Duration.between(lu, LocalDateTime.now()).toHours());
            recTerm = Math.exp(-ageH / Math.max(1e-6, tauH));
        }
        double wRec = hp.getDouble("energy.weight.recency", 0.15);

        // 에너지는 "낮을수록 좋음": 혜택항은 음수, 탐험항은 양수
        return -(W_SIM * cosSim + W_Q * qValue + W_SR * successRatio + tagBonus
                + wConf * conf + wRec * recTerm)
                + W_EXPL * exploreTerm;
    }

    private static double annealTemperature(int hit) {
        return T0 / Math.sqrt(hit + 1.0);
    }

    /*
     * =========================================================
     * 피드백 적용 (기존 코드 유지, 내부 호출에서 updateEnergyAndTemperature 사용)
     * =========================================================
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DataIntegrityViolationException.class)
    public int bumpOnly(String hash) {
        return memoryRepository.incrementHitCountBySourceHash(hash);
    }

    /**
     * Apply feedback only after the API boundary resolved one durable assistant
     * record and bound its safe content hash to the authoritative stored text.
     */
    public void applyFeedbackToRatedAssistant(
            String sessionId,
            Long ratedMessageId,
            String ratedContentHash,
            String messageContent,
            boolean positive,
            String correctedText) {
        if (!memoryEnabled) {
            applyFeedback(sessionId, messageContent, positive, correctedText);
            return;
        }
        String expectedContentHash = com.example.lms.trace.SafeRedactor.hashValue(messageContent);
        boolean validTarget = ratedMessageId != null
                && ratedMessageId > 0L
                && StringUtils.hasText(messageContent)
                && StringUtils.hasText(ratedContentHash)
                && MessageDigest.isEqual(
                        expectedContentHash.getBytes(StandardCharsets.UTF_8),
                        ratedContentHash.getBytes(StandardCharsets.UTF_8));
        if (!validTarget) {
            throw new IllegalArgumentException("rated_record_invalid");
        }
        Long numericSessionId = parseRatedSessionId(sessionId);
        if (numericSessionId == null) {
            throw new IllegalArgumentException("rated_record_invalid");
        }
        if (chatMessageRepository == null) {
            throw new IllegalStateException("rated_record_repository_unavailable");
        }
        ChatMessage durableRecord = chatMessageRepository.findById(ratedMessageId)
                .filter(message -> "assistant".equalsIgnoreCase(message.getRole()))
                .filter(message -> message.getSession() != null)
                .filter(message -> java.util.Objects.equals(
                        numericSessionId,
                        message.getSession().getId()))
                .orElseThrow(RatedRecordNotFoundException::notFound);
        if (!java.util.Objects.equals(messageContent, durableRecord.getContent())) {
            throw new IllegalArgumentException("rated_record_invalid");
        }
        applyFeedback(sessionId, durableRecord.getContent(), positive, correctedText);
    }

    private static Long parseRatedSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(sessionId);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public void applyFeedback(String sessionId,
            String messageContent,
            boolean positive,
            String correctedText) {
        // [Jammini No-Memory Guard]
        if (!memoryEnabled) {
            log.debug("[Memory] Disabled. Skipping applyFeedback for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }

        // 0) 가드
        if (!StringUtils.hasText(messageContent)) {
            log.debug("[Feedback] empty message → skip");
            return;
        }
        String sid = normalizeSessionId(sessionId);
        if (!isStableSid(sid)) {
            log.warn("[Feedback] unstable SID → skip (sidHash={})", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }

        // 1) 원문 답변(assistant 출력) 해시
        String msgHash = storageHashFromSnippet(messageContent);

        try {
            // (기존 긍/부정 점수 업데이트 로직)
            // /* ... */ upsertViaRepository(sid, /* ... */),
            // incrementHitCountBySourceHash(/* ... */) 등 기존 구현 유지 /* ... */

            // 핵심: 에너지/온도 갱신
            updateEnergyAndTemperature(msgHash);

            // 2) 수정문 보강 (있을 때만)
            if (StringUtils.hasText(correctedText) && !correctedText.equals(messageContent)) {
                String refined = correctedText.trim();
                if (refined.length() > 4000)
                    refined = refined.substring(0, 4000);

                String corrHash = storageHashFromSnippet(refined);
                double sCorr = reward(0.98); // 수정문 강한 보상
                String payloadCorr = "[SRC:USER_CORRECTION] " + refined;
                upsertViaRepository(sid, /* query */ null, payloadCorr, "USER_CORRECTION", sCorr, corrHash);

                updateEnergyAndTemperature(corrHash);

                // [FIX] blockVectorStore: USER_CORRECTION은 사용자 제공 데이터이므로 벡터 저장 허용
                final boolean blockVectorStore = false;
                try {
                    if (!blockVectorStore) {
                        int citationCount = detectCitationCount(refined);
                        enqueueVectorSafe(sid, refined,
                                buildVectorMeta("USER_CORRECTION", citationCount > 0, citationCount),
                                "memory.feedback.user_correction");
                    }
                } catch (Exception e) {
                    log.warn("[Memory] Failed to enqueue reinforcement snippet (sidHash={}): {}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sid)), errorSummary(e));
                }
            }

            log.debug("[Feedback] applied (sidHash={}, pos={}, msgHash={})", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sid)), positive, safeHash(msgHash));

            // ▼ 전략 성과 집계: 최근 세션 전략을 읽어 Success/Failure 누적
            var maybeStrategy = strategyTracker.getLastStrategyForSession(sid);
            maybeStrategy.ifPresent(st -> {
                try {
                    perfRepo.upsertAndAccumulate(st.name(), /* category */ "default",
                            positive ? 1 : 0, positive ? 0 : 1,
                            positive ? 1.0 : 0.0);
                } catch (Exception e) {
                    log.debug("[StrategyPerf] update skipped: {}", errorSummary(e));
                }
            });

            // ─────────────────────────────────────────────
            // ★ 공명형 동조: 전역 하이퍼파라미터 미세 조정
            // - 성공: 활용↑(온도↓, ε↓), 신뢰/Authority 가중↑
            // - 실패: 탐험↑(온도↑, ε↑), 신뢰 가중↓
            // ─────────────────────────────────────────────
            try {
                double step = hp.getDouble("reinforce.step", 0.02);
                double dir = positive ? +1.0 : -1.0;
                // Retrieval 랭킹 가중치
                hp.adjust("retrieval.rank.w.auth", dir * step, 0.0, 1.0, 0.10);
                hp.adjust("retrieval.rank.w.rel", dir * step, 0.0, 1.0, 0.60);
                // 에너지 함수 가중
                hp.adjust("energy.weight.confidence", dir * step, 0.0, 1.0, 0.20);
                hp.adjust("energy.weight.recency", dir * step, 0.0, 1.0, 0.15);
                // 전략 탐색/온도
                hp.adjust("strategy.temperature", -dir * step, 0.20, 2.5, 1.0);
                hp.adjust("strategy.epsilon", -dir * step, 0.01, 0.50, 0.10);
                // 성공률/보상 가중
                hp.adjust("strategy.weight.success_rate", dir * step, 0.0, 1.0, 0.65);
                hp.adjust("strategy.weight.reward", dir * step, 0.0, 1.0, 0.30);
            } catch (Exception tuneEx) {
                log.debug("[Reinforce] hyperparam tune skipped: {}", errorSummary(tuneEx));
            }
        } catch (Exception e) {
            log.error("[Feedback] applyFeedback failed: {}", errorSummary(e));
            throw e;
        }
    }

    /**
     * 해시를 기반으로 메모리를 찾아 에너지/온도를 계산 후 DB에 반영.
     */
    private void updateEnergyAndTemperature(String sourceHash) {
        memoryRepository.findBySourceHash(sourceHash).ifPresent(tm -> {
            double energy = this.computeBoltzmannEnergy(tm); // ★ CHG
            double temp = this.annealTemperature(tm.getHitCount()); // ★ CHG

            int updatedRows = memoryRepository.updateEnergyByHash(tm.getSourceHash(), energy, temp);
            if (updatedRows > 0) {
                // SLF4J 자리표시자 포맷으로 보수
                log.info("[Reinforce] Energy/Temp updated for hash: {}, E={}, T={}",
                        safeHash(tm.getSourceHash()),
                        String.format("%.4f", energy),
                        String.format("%.4f", temp));
            }
        });
    }

    private String safeHash(String h) {
        return (h == null || h.length() < 12) ? String.valueOf(h) : h.substring(0, 12);
    }

    private static String errorSummary(Throwable error) {
        return "errorHash=" + errorHash(error) + " errorLength=" + errorLength(error);
    }

    private static String errorHash(Throwable error) {
        return com.example.lms.trace.SafeRedactor.hashValue(error == null ? null : String.valueOf(error));
    }

    private static int errorLength(Throwable error) {
        return error == null ? 0 : String.valueOf(error).length();
    }

    private boolean skipReinforceForSensitive() {
        com.example.lms.service.guard.GuardContext g = com.example.lms.service.guard.GuardContextHolder.get();
        return g != null && (g.isSensitiveTopic() || g.planBool("memory.forceOff", false));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DataIntegrityViolationException.class)
    public void reinforceWithSnippet(TranslationMemory t) {
        // [Jammini No-Memory Guard]
        if (!memoryEnabled) {
            log.debug("[Memory] Disabled. Skipping reinforcement for TranslationMemory entry");
            return;
        }

        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        try {
            // 0) 안전 추출
            String content = tryGetString(t, "getContent", "getText", "getBody");
            if (!StringUtils.hasText(content))
                return;
            String text = content.trim();
            if (text.length() < minContentLength)
                return;
            if (text.length() > maxContentLength)
                text = text.substring(0, maxContentLength);

            String sid = normalizeSessionId(tryGetString(t, "getSid", "getSessionId"));
            String query = tryGetString(t, "getQuery");
            String source = tryGetString(t, "getSourceType", "getSource");
            Double rawSc = tryGetDouble(t, "getScore");
            double finalScore = reward(rawSc != null ? rawSc : 0.5);

            // [FIX] blockVectorStore: ASSISTANT/LLM 소스타입일 경우 벡터 저장소 인큐 차단
            final boolean blockVectorStore = ("ASSISTANT".equalsIgnoreCase(source) || "LLM".equalsIgnoreCase(source));

            // 1) 중복 키: 본문 기반 해시
            String sourceHash = storageHashFromSnippet(text);

            // 2) 존재하면 hit++ (리포지토리에 있는 메서드 이름 맞춰 사용)
            int rows = 0;
            try {
                rows = memoryRepository.incrementHitCountBySourceHash(sourceHash);
            } catch (Exception ignore) {
                MemoryReinforcementTraceSuppressions.traceSuppressed("sourceHash.incrementHit", ignore);
            }

            // 3) 없으면 업서트
            if (rows == 0) {
                String payload = (StringUtils.hasText(source) ? "[SRC:" + source + "] " : "") + text;
                upsertViaRepository(sid, query, payload, source, finalScore, sourceHash);
            }

            // 4) 긍정 데이터만 벡터 색인(여기서는 일단 색인)
            try {
                if (!blockVectorStore) {
                    int citationCount = detectCitationCount(text);
                    enqueueVectorSafe(sid, text,
                            buildVectorMeta(source, citationCount > 0, citationCount),
                            "memory.reinforce.tm");
                }
            } catch (Exception ignore) {
                MemoryReinforcementTraceSuppressions.traceSuppressed("turnMemory.vectorEnqueue", ignore);
            }
        } catch (DataIntegrityViolationException dup) {
            log.debug("[Memory] duplicate; fallback to UPDATE", dup);
            // 중복이면 hit++만 시도
            try {
                String content = tryGetString(t, "getContent", "getText", "getBody");
                if (StringUtils.hasText(content)) {
                    memoryRepository.incrementHitCountBySourceHash(storageHashFromSnippet(content));
                }
            } catch (Exception e) {
                log.debug("[Memory] Failed to read numeric hyperparameter via reflection: {}", errorSummary(e));
            }
        } catch (Exception e) {
            log.warn("[Memory] soft-fail: {}", errorSummary(e));
        }
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_PROJECTION_V1
    // [NEW] Overload with quality metadata (Projection Jammini v1).
    // 메타데이터가 주어지면 fusionScore / evidenceScore / planId / citation 여부를 참고하여
    // 기존 HARD 모드에서도 일부 케이스를 강제로 저장(override)할 수 있도록 한다.
    public void reinforceWithSnippet(TranslationMemory tm, QualityMetadata meta) {
        // 메타데이터가 없으면 레거시 동작 유지
        if (meta == null) {
            reinforceWithSnippet(tm);
            return;
        }

        // Jammini No-Memory Guard
        if (!memoryEnabled) {
            log.debug("[Memory] Disabled. Skipping reinforcement for TranslationMemory entry (metadata path)");
            return;
        }

        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        try {
            // 0) 안전 추출 (TranslationMemory 기반 content)
            String content = tryGetString(tm, "getContent", "getText", "getBody");
            if (!org.springframework.util.StringUtils.hasText(content)) {
                return;
            }
            String text = content.trim();
            if (text.length() < minContentLength)
                return;
            if (text.length() > maxContentLength)
                text = text.substring(0, maxContentLength);

            // 1) 부정 패턴이지만 fusionScore 가 매우 높은 경우 → 강제 저장
            if (hasNegativeAnswerSignal(text) && meta.fusionScore() >= 0.90) {
                log.info("[MEMORY_GATE] Negative pattern but high fusionScore={} → force save (Projection Jammini)",
                        String.format(java.util.Locale.ROOT, "%.2f", meta.fusionScore()));
                // 기존 로직을 그대로 사용하여 저장 처리
                reinforceWithSnippet(tm);
                return;
            }

            // 2) citation 이 0개이지만 aggressive 플랜(예: zero_break)이면서 evidenceScore 가 높은 경우 →
            // 저장 허용
            String planId = meta.planId();
            if (!meta.hasCitation()
                    && planId != null
                    && "zero_break".equalsIgnoreCase(planId)
                    && meta.evidenceScore() >= 0.80) {
                log.info(
                        "[MEMORY_GATE] No citation but aggressive plan '{}' & evidenceScore={} → save (Projection Jammini)",
                        planId,
                        String.format(java.util.Locale.ROOT, "%.2f", meta.evidenceScore()));
                reinforceWithSnippet(tm);
                return;
            }

            // 3) 그 외에는 메타데이터는 참고 신호만 쓰고, 기본 레거시 로직을 그대로 사용
            reinforceWithSnippet(tm);
        } catch (Exception e) {
            // 메타데이터 기반 경로에서 문제가 생기면 안전하게 레거시 경로로 폴백
            log.debug("[MEMORY_GATE] metadata-aware reinforcement failed; falling back to legacy: {}", errorSummary(e));
            reinforceWithSnippet(tm);
        }
    }

    // MERGE_HOOK:PROJ_AGENT::JAMMINI_FORCE_SAVE
    /**
     * 점수 및 GuardContext 기반 강제 저장 로직 (Projection Jammini v2).
     *
     * <p>
     * 기본 원칙:
     * <ul>
     * <li>fusionScore >= 0.90 이면서 부정 패턴이면 기존 게이트를 우회하고 강제 저장</li>
     * <li>Brave/ZeroBreak 등 aggressive 플랜에서는 0.75 이상부터 완화 저장</li>
     * <li>그 외 약한(snippet) 경우에는 메타데이터 기반 v1 로직에 위임</li>
     * </ul>
     *
     * 이 메서드는 v1(overload) 위에 얹혀지는 선택적 경로이므로, 호출자가 준비되지 않은 경우
     * 기존 {@link #reinforceWithSnippet(TranslationMemory, QualityMetadata)} 을 그대로
     * 사용해도 된다.
     */
    public void reinforceWithSnippetV2(TranslationMemory tm,
            QualityMetadata meta,
            GuardContext ctx) {

        // 메타/컨텍스트가 모두 없으면 레거시 로직만 사용
        if (tm == null) {
            return;
        }
        if (meta == null && ctx == null) {
            reinforceWithSnippet(tm);
            return;
        }

        // Jammini No-Memory Guard
        if (!memoryEnabled) {
            log.debug("[Memory] Disabled. Skipping reinforcement for TranslationMemory entry (V2 path)");
            return;
        }

        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        try {
            String content = tryGetString(tm, "getContent", "getText", "getBody");
            if (!org.springframework.util.StringUtils.hasText(content)) {
                return;
            }
            String text = content.trim();
            if (text.length() < minContentLength)
                return;
            if (text.length() > maxContentLength)
                text = text.substring(0, maxContentLength);

            double fusion = (meta != null ? meta.fusionScore() : 0.0);
            if (ctx != null && fusion < ctx.fusionScore()) {
                fusion = ctx.fusionScore();
            }

            // 1. 부정 패턴이지만 점수가 높으면 강제 저장
            if (hasNegativeAnswerSignal(text) && fusion >= 0.90) {
                log.info("[MEMORY_GATE][V2] Negative pattern but high fusionScore={} → force save",
                        String.format(java.util.Locale.ROOT, "%.2f", fusion));
                reinforceWithSnippet(tm);
                return;
            }

            // 2. Brave/ZeroBreak/Wild 등 aggressive 플랜에서는 0.75 이상이면 완화 저장
            if (ctx != null && ctx.isAggressivePlan() && fusion >= 0.75) {
                log.info("[MEMORY_GATE][V2] Aggressive plan & fusionScore={} → relaxed save",
                        String.format(java.util.Locale.ROOT, "%.2f", fusion));
                reinforceWithSnippet(tm);
                return;
            }

            // 3. 약한 스니펫이면 후보 경로로 위임 (현재는 v1 로직 재사용)
            GuardContext effectiveCtx = (ctx != null ? ctx : GuardContext.defaultContext());
            if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(text, effectiveCtx)) {
                log.debug("[MEMORY_GATE][V2] Weak snippet detected → delegating to v1(metadata) path");
                reinforceWithSnippet(tm, meta);
                return;
            }

            // 4. 그 외에는 v1 메타데이터 경로 사용
            reinforceWithSnippet(tm, meta);
        } catch (Exception e) {
            log.debug("[MEMORY_GATE][V2] reinforcement failed; falling back to legacy: {}", errorSummary(e));
            reinforceWithSnippet(tm);
        }
    }
    // MERGE_HOOK END

    /*
     * =========================================================
     * ▼▼▼ Backward-Compat Adapter API (호환 레이어) ▼▼▼
     * 기존 호출부가 참조하는 시그니처를 그대로 제공
     * =========================================================
     */

    /**
     * 기존 호출부:
     * reinforceWithSnippet(sessionId, query, snippet, sourceTag, score)
     */
    @Transactional
    public void reinforceWithSnippet(String sessionId,
            String query,
            String snippet,
            String sourceTag,
            double score,
            LearningSampleValidationMetadata validation) {
        if (!autolearnMemoryReinforcementEnabled) {
            return;
        }
        if (!memoryEnabled) {
            log.debug("[MEMORY_GATE] AutoLearn reinforcement disabled by memory.enabled=false");
            return;
        }
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip AutoLearn reinforcement");
            return;
        }
        if (validation == null || !validation.accepted() || validation.sampleScore() < 0.55d) {
            log.debug("[MEMORY_GATE] AutoLearn validation rejected -> skip reinforcement");
            return;
        }
        if (!StringUtils.hasText(snippet)) {
            return;
        }

        String payload = snippet.trim();
        if ("[NO_EVIDENCE]".equals(payload)) {
            return;
        }
        if (payload.length() < minContentLength) {
            return;
        }
        if (payload.length() > maxContentLength) {
            payload = payload.substring(0, maxContentLength);
        }

        String sid = StringUtils.hasText(sessionId) ? sessionId : "__TRANSIENT__";
        String tag = StringUtils.hasText(sourceTag) ? sourceTag : "UAW_AUTOLRN";
        double finalScore = reward(Math.min(score, validation.sampleScore()));
        String hash = sha1("UAW_AUTOLRN|" + payload);
        upsertPendingCandidate(sid, query, payload, tag, finalScore, hash);
        log.debug("[MEMORY_GATE] AutoLearn validation sample stored as PENDING sidHash={} score={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sid)), finalScore);
    }

    @Transactional
    public void reinforceWithSnippet(String sessionId,
            String query,
            String snippet,
            String sourceTag,
            double score) {
        final boolean blockVectorStore = ("ASSISTANT".equalsIgnoreCase(sourceTag) || "LLM".equalsIgnoreCase(sourceTag));
        // [Jammini No-Memory Guard]
        if (!memoryEnabled) {
            log.debug("[Memory] Disabled. Skipping reinforcement for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }

        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }

        // 신규 GuardProfile 기반 메모리 비활성화 모드
        GuardProfile profile = guardProfileProps.currentProfile();
        if (profile == GuardProfile.PROFILE_FREE) {
            log.debug("[MEMORY_GATE] FREE profile → reinforcement disabled for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }
        // PROFILE_FREE 또는 WILD 모드: 메모리 저장 완전 비활성
        if (profile == GuardProfile.WILD) {
            log.debug("[MEMORY_GATE] WILD profile → reinforcement disabled for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }
        // BRAVE 모드: 고점수(0.75+)만 저장
        if (profile == GuardProfile.BRAVE && score < 0.75d) {
            log.debug("[MEMORY_GATE] BRAVE profile → skip low-score snippet ({}) for sessionHash={}", score, com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }

        if (!StringUtils.hasText(snippet))
            return;
        // ⓵ [NO_EVIDENCE] 또는 "정보 없음" 패턴 스킵
        String trimmedSnippet = snippet.trim();
        if ("[NO_EVIDENCE]".equals(trimmedSnippet)) {
            log.debug("[MEMORY] Skipping [NO_EVIDENCE] snippet for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }
        // Skip evidence-list style fallbacks produced by the guard. These answers
        // already summarise web search snippets and should not be reinforced as
        // long-term assistant knowledge.
        String lowerSnippet = trimmedSnippet.toLowerCase(Locale.ROOT);
        if (trimmedSnippet.contains("## 검색 결과 요약")
                || trimmedSnippet.contains("### 검색 결과 요약")
                || trimmedSnippet.contains("## 검색 결과")
                || trimmedSnippet.contains("### 검색 결과")
                || trimmedSnippet.contains("## 근거")
                || trimmedSnippet.contains("### 근거")
                || lowerSnippet.contains("근거 리스트")
                || lowerSnippet.contains("근거 목록")
                || lowerSnippet.contains("b) 최종 컨텍스트 (llm 입력)")
                || lowerSnippet.contains("🌐 web (reranked topk)")
                || lowerSnippet.contains("[src:web]")
                || lowerSnippet.contains("[src:rag]")
                || lowerSnippet.contains("커뮤니티/위키에서 다음 정보를 찾았습니다")
                || lowerSnippet.contains("검색된 자료를 바탕으로 정리했으나")
                || trimmedSnippet.startsWith("아래 근거를 바탕으로 가능한 사실만 정리합니다")) {
            log.debug("[MEMORY_MODE] skip search-summary/evidence-list style snippet");
            return;
        }
        // [SOFTENED POLICY]
        // looksWeak() is now advisory only. We log a warning but proceed with storage.
        // Rationale: In practice, many valid game/subculture answers (e.g., "Furina is
        // a 5-star...")
        // were incorrectly classified as weak, causing memory loss and repeated "no
        // information" loops.
        //
        // Future improvement: Add domain-specific weak detection (medical/legal vs. pop
        // culture).
        boolean weak = com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(trimmedSnippet);
        // [ADD] Force Save: 점수 0.9+ 고신뢰 응답은 weak 여부 상관없이 저장
        boolean isHighConfidence = (score >= 0.90);
        if (isHighConfidence) {
            if (weak) {
                log.info("[Memory] Force-saving HIGH SCORE ({}) snippet despite weak signal.", score);
            }
            String sid = org.springframework.util.StringUtils.hasText(sessionId) ? sessionId : "__TRANSIENT__";
            String hash = sha1(snippet);
            upsertViaRepository(sid, query, trimmedSnippet, sourceTag, score, hash);
            try {
                if (!blockVectorStore) {
                    int citationCount = detectCitationCount(snippet);
                    enqueueVectorSafe(sid, snippet,
                            buildVectorMeta(sourceTag, citationCount > 0, citationCount),
                            "memory.reinforce.snippet");
                }
            } catch (Exception ignore) {
                MemoryReinforcementTraceSuppressions.traceSuppressed("snippet.vectorEnqueue", ignore);
            }
            return; // 이후 로직 스킵
        }

        boolean isSubculture = trimmedSnippet.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(원신|genshin|마비카|푸리나|게임|애니|만화|캐릭터).*");
        if (weak && !isSubculture) {
            if (saveWeakAsPending && score >= pendingMinScore) {
                String sid = org.springframework.util.StringUtils.hasText(sessionId) ? sessionId : "__TRANSIENT__";
                String safePayload = trimmedSnippet == null ? "" : trimmedSnippet.trim();
                if (!safePayload.isBlank()) {
                    String hash = sha1(safePayload);
                    upsertPendingCandidate(sid, query, safePayload, sourceTag, score, hash);
                    log.info("[MEMORY] Stored WEAK snippet as PENDING (score={}, sidHash={})", score, com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sid)));
                }
            } else {
                log.debug("[MEMORY] Normal domain: skipping WEAK snippet for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            }
            return;
        }
        if (weak && isSubculture) {
            // 서브컬처/게임 도메인은 weak도 허용
            log.debug("[MEMORY] Subculture domain: storing WEAK snippet (relaxed policy)");
        }
        // ═══════════════════════════════════════════════════════════
        // GATE 1: Negative Answer Filter (부정 응답 차단)
        // - 단순 거절 vs 회복형 답변을 구분
        // ═══════════════════════════════════════════════════════════
        boolean hasNegative = hasNegativeAnswerSignal(trimmedSnippet);
        boolean hasRecovery = hasRecoverySignal(trimmedSnippet);
        if (hasNegative && !hasRecovery) {
            // 순수한 거절/부정 답변 → 학습에서 제외
            log.info("[MEMORY_GATE] Blocking simple negative/uncertain answer from reinforcement: answerHash={} answerLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(trimmedSnippet), trimmedSnippet == null ? 0 : trimmedSnippet.length());
            return;
        }
        if (hasNegative && hasRecovery) {
            // 🔥 [시선1 패치] 증거 있으면 패널티 제거
            boolean hasEvidence = (sourceTag != null &&
                    !sourceTag.equalsIgnoreCase("TEXT"));

            double penalty = hasEvidence ? 1.0 : 0.95; // 🔥 0.90 → 0.95 (없을 때)
            try {
                penalty = hp.getDouble("memory.reinforce.recovered-penalty", penalty);
            } catch (Exception ignore) {
                MemoryReinforcementTraceSuppressions.traceSuppressed("recoveredPenalty.config", ignore);
            }

            score = score * penalty;
            log.info("[MEMORY_GATE] Allowing recovered answer with penalty (x{}). " +
                    "hasEvidence={}, snippetHash={} snippetLength={}",
                    String.format(Locale.ROOT, "%.2f", penalty),
                    hasEvidence,
                    com.example.lms.trace.SafeRedactor.hashValue(trimmedSnippet), trimmedSnippet == null ? 0 : trimmedSnippet.length());
        }

        // ═══════════════════════════════════════════════════════════
        // GATE 2: Citation Validation (출처 검증)
        // - STRICT 모드: 인용 신호가 없으면 차단
        // - SOFT 모드: 인용 신호가 없으면 점수를 낮춰 약하게만 강화
        // ═══════════════════════════════════════════════════════════
        if ("ASSISTANT".equals(sourceTag) || "AI_GENERATED".equals(sourceTag)) {
            String snippetLower = snippet.toLowerCase();

            boolean hasHttpLink = snippet.contains("http://") || snippet.contains("https://");

            // [W1], [V2], [D3] 와 같은 인용 마커 또는 '출처:' / 'source:' 텍스트를 넓게 인정
            boolean hasEvidenceMarker = snippet.matches("(?s).*\\[(W|V|D)\\d+].*") ||
                    snippetLower.contains("출처:") ||
                    snippetLower.contains("source:");

            boolean hasCitationSignal = hasHttpLink || hasEvidenceMarker;

            String mode = (citationGateMode == null ? "SOFT" : citationGateMode);

            // 현재 GuardProfile 확인 (FREE 프로파일은 reinforceWithSnippet 초입에서 이미 차단)
            GuardProfile profileForCitation = guardProfileProps.currentProfile();
            // 의료/공공/PII 질의 감지 (NaverSearchService 유틸과 동일 패턴)
            boolean strictDomain = query != null &&
                    query.toLowerCase().matches(".*(병원|의료|의사|전문의|교수|대학교|학과|연구실|공공기관|정부|학회).*");

            // STRICT 모드 + ProgressiveMemory 비활성일 때만 기존 차단 로직 유지
            if (("STRICT".equalsIgnoreCase(mode) || strictDomain) && !progressiveMemoryMode) {
                if (!hasCitationSignal) {
                    log.warn(
                            "[MEMORY_GATE] Blocking ASSISTANT answer without any citation signal (STRICT mode). snippetHash={} snippetLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(snippet), snippet == null ? 0 : snippet.length());
                    return;
                }
                log.info("[MEMORY_GATE] citation signal detected (STRICT mode) → allowing reinforcement");
            } else {
                // 서브컬처/게임 도메인 감지 (sourceTag 또는 snippet 내용 기반)
                // [FIX] 변수명 충돌 해결: GATE 1의 isSubculture 와 구분하기 위해 로컬 변수 이름 변경
                boolean isSubcultureInGate = (sourceTag != null && sourceTag.toLowerCase(Locale.ROOT).contains("game"))
                        || trimmedSnippet.toLowerCase(Locale.ROOT).matches(".*(원신|genshin|마비카|푸리나|캐릭터|게임|애니|만화).*");

                // HARD 모드: 일반 도메인에만 적용, 서브컬처는 완화
                if (!hasCitationSignal && !isSubcultureInGate) {
                    if (progressiveMemoryMode) {
                        // Progressive + PROFILE_MEMORY: citation 없더라도 페널티만 주고 저장
                        log.info(
                                "[MEMORY_GATE] Progressive: no citation signal but storing anyway (may apply score penalty).");
                    } else {
                        log.info("[MEMORY_GATE] HARD mode: missing explicit citation → skip reinforcement");
                        return;
                    }
                } else if (!hasCitationSignal && isSubcultureInGate) {
                    log.info(
                            "[MEMORY_GATE] Subculture domain: allowing reinforcement without citation (relaxed policy)");
                    // 점수 감쇠 없이 그대로 진행
                } else {
                    log.info("[MEMORY_GATE] HARD/SOFT mode: citation signal detected → normal scoring");
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        // GATE 3: Score Threshold (모드 기반 조정)
        // - CONSERVATIVE: 기존 로직 유지
        // - EXPLORE:
        // * 서브컬처/게임/위키 스타일 질의 → 점수와 citation 부족에 관계없이 대부분 저장
        // * 일반 도메인 → score >= lowScoreCutoff 일 때만 저장
        // ═══════════════════════════════════════════════════════════
        String modeForReinforce = reinforcementMode == null ? "CONSERVATIVE" : reinforcementMode;
        boolean exploreMode = "EXPLORE".equalsIgnoreCase(modeForReinforce);

        boolean isSubcultureDomain = (sourceTag != null && sourceTag.toLowerCase(Locale.ROOT).contains("game")) ||
                trimmedSnippet.toLowerCase(Locale.ROOT).matches(
                        ".*(원신|genshin|마비카|푸리나|캐릭터|게임|애니|만화|위키|나무위키|" +
                                "hoyo|hoyoverse|스냅드래곤|chipset|리뷰|벤치마크|블로그).*");
        if (!exploreMode) {
            // 🔥 서브컬처도 낮은 컷오프 적용
            double effectiveCutoff = isSubcultureDomain
                    ? Math.min(lowScoreCutoff, 0.15) // 🔥 0.3 → 0.15
                    : lowScoreCutoff;

            if (score < effectiveCutoff || !shouldStore(snippet)) {
                log.debug("[Reinforce] skip store (score < cutoff or bad snippet). " +
                        "score={}, cutoff={}", score, effectiveCutoff);
                return;
            }
        } else {
            if (!isSubcultureDomain) {
                if (score < lowScoreCutoff || !shouldStore(snippet)) {
                    log.debug(
                            "[Reinforce][EXPLORE] skip store (non-subculture, score < cutoff or bad snippet). score={}, cutoff={}",
                            score, lowScoreCutoff);
                    return;
                }
            } else {
                // EXPLORE + Subculture: 길이가 너무 짧은 경우만 차단하고 나머지는 저장
                if (trimmedSnippet.length() < Math.max(10, minContentLength / 2)) {
                    log.debug("[Reinforce][EXPLORE] skip very short subculture snippet. len={}, minLen={}",
                            trimmedSnippet.length(), minContentLength);
                    return;
                }
                log.info("[Reinforce][EXPLORE] subculture snippet accepted even with low score. score={}, cutoff={}",
                        score, lowScoreCutoff);
            }
        }
        // [HARDENING] normalize null/blank to __TRANSIENT__
        String sid = StringUtils.hasText(sessionId) ? sessionId : "__TRANSIENT__";
        String hash = sha1(snippet);

        // 기존 레코드 조회 or 새로 생성
        // When no record exists, construct a new TranslationMemory with the current
        // snippet's hash.
        TranslationMemory tm = memoryRepository.findBySourceHash(hash)
                .orElseGet(() -> new TranslationMemory(hash));

        if (tm.getId() == null) {
            tm.setSourceHash(hash);
            tm.setSessionId(sid);
            tm.setHitCount(0);
            tm.setSuccessCount(0);
            tm.setFailureCount(0);
            tm.setQValue(0.0);
            tm.setCosineSimilarity(0.0);
        }

        // score를 0~1로 클램프하고 엔티티에 저장
        double clampedScore = reward(score);
        tm.setScore(clampedScore);
        // 간단 규칙: score 기준 성공/실패 카운트
        tm.setHitCount((tm.getHitCount() == null ? 0 : tm.getHitCount()) + 1);
        boolean success = clampedScore >= 0.5;
        if (success) {
            tm.setSuccessCount(tm.getSuccessCount() + 1);
        } else {
            tm.setFailureCount(tm.getFailureCount() + 1);
        }

        // Q-value 업데이트(0~1로 클램프)
        double q = Math.max(0.0, Math.min(1.0, clampedScore));
        tm.setQValue(q);
        // + 검증 단계에서 전달된 신뢰도(있다면) 반영 - 없으면 q로 초기화
        if (tm.getConfidenceScore() == null) {
            tm.setConfidenceScore(q);
        } else {
            tm.setConfidenceScore(0.8 * tm.getConfidenceScore() + 0.2 * q);
        }

        // 에너지/온도 계산
        double energy = this.computeBoltzmannEnergy(tm); // ★ CHG
        double temp = this.annealTemperature(tm.getHitCount()); // ★ CHG
        tm.setEnergy(energy);
        tm.setTemperature(temp);

        // 세션 정책에 따라 원자적 갱신
        int updated = (tm.getSessionId() != null)
                ? memoryRepository.updateEnergyByHashAndSession(hash, tm.getSessionId(), energy, temp)
                : memoryRepository.updateEnergyByHash(hash, energy, temp);

        if (updated == 0) {
            // 최초 생성 등으로 업데이트가 0이면 저장
            memoryRepository.save(tm);
        }
        // 저장 후에는 “봤다”로 마킹 → 이후 동일 스니펫은 shouldStore에서 중복으로 필터
        try {
            if (recentSnippetCache != null) {
                recentSnippetCache.put(hash, Boolean.TRUE);
            }
        } catch (Exception ignore) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("recentSnippetCache.put", ignore);
        }
        // + 벡터 색인 큐에 적재(예외 무시)
        try {
            if (!blockVectorStore) {
                int citationCount = detectCitationCount(snippet);
                enqueueVectorSafe(sid, snippet,
                        buildVectorMeta(sourceTag, citationCount > 0, citationCount),
                        "memory.reinforce.snippet.v2");
            }
        } catch (Exception ignore) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("snippetV2.vectorEnqueue", ignore);
        }
    }

    /**
     * 기존 호출부: loadContext(sessionId)
     * → 상위 저에너지 10개를 합쳐 문자열 컨텍스트 반환
     */
    @Transactional(readOnly = true)
    public String loadContext(String sessionId) {
        try {
            List<TranslationMemory> list = memoryRepository
                    .findTop10BySessionIdAndEnergyNotNullOrderByEnergyAsc(sessionId);
            if (list == null || list.isEmpty()) {
                list = memoryRepository.findTop10ByEnergyNotNullOrderByEnergyAsc();
            }
            StringBuilder sb = new StringBuilder();
            for (TranslationMemory tm : list) {
                String txt = extractText(tm);
                if (StringUtils.hasText(txt)) {
                    if (sb.length() > 0)
                        sb.append("\n\n---\n\n");
                    sb.append(txt);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("context.load", e); return "";
        }
    }

    /**
     * [Dual-Vision] 프로파일 기반 메모리 강화
     *
     * @param profile MemoryGateProfile (HARD/BALANCED/RELAXED)
     */
    @Transactional
    public void reinforceWithSnippet(String sessionId,
            String query,
            String snippet,
            String sourceTag,
            double score,
            MemoryGateProfile profile) {
        if (!StringUtils.hasText(snippet)) {
            return;
        }
        String trimmedSnippet = snippet.trim();
        if ("정보 없음".equals(trimmedSnippet)) {
            return;
        }

        boolean looksWeak = com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(trimmedSnippet);
        boolean templateLike = com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(trimmedSnippet);

        switch (profile) {
            case HARD -> {
                if (looksWeak || templateLike || missingCitation(trimmedSnippet)) {
                    log.info("[MEMORY_GATE] HARD mode: skip weak snippet");
                    return;
                }
            }
            case BALANCED -> {
                if (looksWeak && missingCitation(trimmedSnippet)) {
                    log.debug("[MEMORY_GATE] BALANCED: weak + no citation, storing with low weight");
                    score *= 0.5;
                }
            }
            case RELAXED -> {
                if (isHighRiskContent(query, trimmedSnippet)) {
                    log.info("[MEMORY_GATE] RELAXED but high-risk detected -> force HARD");
                    return;
                }
                if (looksWeak) {
                    score *= 0.4;
                }
            }
        }

        // 기존 저장 로직 호출
        reinforceWithSnippet(sessionId, query, trimmedSnippet, sourceTag, score);
    }

    @Transactional
    public boolean reinforceSessionSummaryShadow(
            String sessionId,
            String snippet,
            String promotionHash,
            double score) {
        if (!memoryEnabled) {
            log.debug("[MEMORY_GATE] session-summary shadow disabled by memory.enabled=false");
            return false;
        }
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip session-summary shadow");
            return false;
        }
        GuardProfile profile = guardProfileProps.currentProfile();
        if (profile == GuardProfile.PROFILE_FREE || profile == GuardProfile.WILD) {
            log.debug("[MEMORY_GATE] profile={} -> skip session-summary shadow", profile);
            return false;
        }
        if (!StringUtils.hasText(snippet)) {
            return false;
        }
        String payload = snippet.trim();
        if ("정보 없음".equals(payload) || "[NO_EVIDENCE]".equals(payload)) {
            return false;
        }
        if (com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(payload)) {
            return false;
        }
        double adjustedScore = reward(score);
        if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(payload)) {
            adjustedScore *= 0.5d;
        }
        if (payload.length() < minContentLength || adjustedScore < pendingMinScore) {
            return false;
        }
        if (payload.length() > maxContentLength) {
            payload = payload.substring(0, maxContentLength);
        }
        String sid = StringUtils.hasText(sessionId) ? sessionId : "__TRANSIENT__";
        String hash = sha1("SESSION_SUMMARY|" + (promotionHash == null ? "" : promotionHash) + "|" + payload);
        upsertPendingCandidate(sid, "", payload, "RAG_OPS", adjustedScore, hash);
        return true;
    }

    /**
     * MemoryMode-aware variant of reinforceWithSnippet.
     * When memoryMode is not write-enabled (e.g. HYBRID read-only or EPHEMERAL),
     * this method becomes a no-op to respect user/session preferences.
     */
    @Transactional
    public void reinforceWithSnippet(String sessionId,
            String query,
            String snippet,
            String sourceTag,
            double score,
            MemoryGateProfile profile,
            MemoryMode memoryMode) {
        if (memoryMode != null && !memoryMode.isWriteEnabled()) {
            log.debug("[MEMORY_MODE] skip reinforcement (mode={}) for sessionHash={}, source={}",
                    memoryMode, com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)), sourceTag);
            return;
        }
        reinforceWithSnippet(sessionId, query, snippet, sourceTag, score, profile);
    }

    private boolean missingCitation(String answer) {
        return answer != null && !answer.matches(".*\\[(W|V)\\d+].*");
    }

    private boolean isHighRiskContent(String query, String answer) {
        String q = (query == null ? "" : query);
        String a = (answer == null ? "" : answer);
        String combined = (q + " " + a).toLowerCase(java.util.Locale.ROOT);
        return combined.matches(".*(진단|처방|증상|법률|소송|형량|투자|수익률|보험금).*");
    }

    /**
     * 기존 호출부: reinforceMemoryWithText(text)
     */
    public void reinforceMemoryWithText(String text) {
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }
        if (!StringUtils.hasText(text))
            return;
        // 세션 미상 → 공용(__TRANSIENT__)으로 적재, 보수적 점수 0.5 [HARDENING]
        // [HARDENING] unknown session -> __TRANSIENT__
        reinforceWithSnippet("__TRANSIENT__", "", text, "TEXT", 0.5);
    }

    /* ────────────── 호환 유틸 ────────────── */

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b)
                sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            MemoryReinforcementTraceSuppressions.traceSuppressed("hash.sha1Fallback", e); return Integer.toHexString(s.hashCode());
        }
    }

    /** TranslationMemory 안의 텍스트 필드명을 모를 때 안전 추출 */
    private static String extractText(TranslationMemory tm) {
        if (tm == null) return null;
        return firstText(tm.getContent(), tm.getCorrected(), tm.getQuery());
    }

    private static String tryGetString(TranslationMemory bean, String... getters) {
        if (bean == null || getters == null) return null;
        for (String g : getters) {
            String value = switch (g) {
                case "getContent", "getText", "getBody" -> bean.getContent();
                case "getSid", "getSessionId" -> bean.getSessionId();
                case "getQuery" -> bean.getQuery();
                case "getSourceType", "getSource" -> bean.getSource();
                case "getSourceTag" -> bean.getSourceTag();
                default -> null;
            };
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private static Double tryGetDouble(TranslationMemory bean, String... getters) {
        if (bean == null || getters == null) return null;
        for (String g : getters) {
            Double value = switch (g) {
                case "getScore" -> bean.getScore();
                case "getConfidenceScore" -> bean.getConfidenceScore();
                case "getEnergy" -> bean.getEnergy();
                case "getTemperature" -> bean.getTemperature();
                default -> null;
            };
            if (value != null) return value;
        }
        return null;
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }
    /* ====================== Missing helpers (added) ====================== */

    /** 세션키 정규화: 숫자면 chat- 접두, 없으면 "__TRANSIENT__" */ // [HARDENING]
    private static String normalizeSessionId(String sessionId) {
        // [HARDENING] return __TRANSIENT__ when no session id
        if (!StringUtils.hasText(sessionId))
            return "__TRANSIENT__";
        String s = sessionId.trim();
        if (s.startsWith("chat-"))
            return s;
        if (s.matches("\\d+"))
            return "chat-" + s;
        return s;
    }

    /** “안정적인” 세션키 판단: chat- 접두 또는 6자 이상 영숫자/대시; '*' 및 __TRANSIENT__ are unstable */ // [HARDENING]
    private static boolean isStableSid(String sid) {
        if (!StringUtils.hasText(sid))
            return false;
        // [HARDENING] __TRANSIENT__ is not considered stable; we avoid wildcard '*'
        if ("__TRANSIENT__".equals(sid))
            return false;
        if (sid.startsWith("chat-"))
            return true;
        return Pattern.compile("^[A-Za-z0-9\\-]{6,}$").matcher(sid).matches();
    }

    /** 스니펫을 저장용 해시로 변환 (현재는 SHA-1 사용) */
    private static String storageHashFromSnippet(String s) {
        if (s == null)
            return null;
        return sha1(s.trim());
    }

    // 간단한 보관 전 품질 게이트(너무 짧은/중복성 높은 스니펫 차단)
    private boolean shouldStore(String text) {
        String s = text.trim();
        if (s.length() < 40)
            return false; // 너무 짧음 → 노이즈
        if (recentSnippetCache != null) { // 최근 중복 방지(있으면)
            String h = storageHashFromSnippet(s);
            // 캐시에 "이미 본" 기록이 있을 때만 중복으로 간주
            if (Boolean.TRUE.equals(recentSnippetCache.getIfPresent(h)))
                return false;
        }
        return true;
    }

    /**
     * 문장 내 부정/거절 신호가 있는지 단순 감지.
     */
    private boolean hasNegativeAnswerSignal(String text) {
        if (text == null)
            return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("죄송합니다")
                || lower.contains("정보가 없")
                || lower.contains("찾을 수 없")
                || lower.contains("모르겠습니다")
                || lower.contains("확실하지 않")
                || lower.contains("언어 모델")
                || lower.contains("i'm sorry")
                || lower.contains("no information")
                || lower.contains("don't know");
    }

    /**
     * 부정 서두 이후에 유용한 정보가 이어지는지(회복 신호) 감지.
     * - 접속사/전환 키워드 + 일정 길이 이상일 때 true.
     */
    private boolean hasRecoverySignal(String text) {
        if (text == null)
            return false;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasConjunction = lower.contains("하지만")
                || lower.contains("그러나")
                || lower.contains("다만")
                || lower.contains("한편")
                || lower.contains("참고로")
                || lower.contains("반면")
                || lower.contains("however")
                || lower.contains("but ")
                || lower.contains("on the other hand");
        boolean hasInformativeKeyword = lower.contains("루머")
                || lower.contains("소문")
                || lower.contains("예상")
                || lower.contains("전망")
                || lower.contains("보도")
                || lower.contains("따르면")
                || lower.contains("according to")
                || lower.contains("rumor")
                || lower.contains("leak");
        int len = lower.length();
        // 너무 짧으면 단순 거절로 간주
        boolean longEnough = len >= 150;
        return (hasConjunction || hasInformativeKeyword) && longEnough;
    }

    /** Helper: 문자열을 안전하게 자르기 */
    private static String safeTrunc(String s, int max) {
        if (s == null) {
            return "null";
        }
        return com.example.lms.trace.SafeRedactor.safeMessage(s, max);
    }

    /*
     * =========================================================
     * 이하, 기존 서비스 내부 유틸/메서드 유지
     * - normalizeSessionId(/* ... *&#47;)
     * - isStabl
     * 
     * /**
     * GuardDecision 기반으로 reinforcement 점수를 조정합니다.
     * 시선1(PROFILE_MEMORY)에서만 호출되어야 합니다.
     */
    public double adjustScoreByGuard(
            com.example.lms.service.guard.EvidenceAwareGuard.GuardDecision decision,
            double baseScore) {

        if (decision == null) {
            return baseScore;
        }

        // ALLOW가 아니면 강화하지 않음
        if (decision.action() != com.example.lms.service.guard.EvidenceAwareGuard.GuardAction.ALLOW) {
            return 0.0;
        }

        // coverage와 strength 기반 가중치 조정
        double coverageWeight = Math.max(0.5, decision.coverageScore());
        double strengthWeight = (decision
                .evidenceStrength() == com.example.lms.service.guard.EvidenceAwareGuard.EvidenceStrength.STRONG)
                        ? 1.0
                        : 0.7;

        return Math.min(1.0, baseScore * coverageWeight * strengthWeight);
    }

    // MERGE_HOOK:PROJ_AGENT::jammini_memory_reinforce
    /**
     * GuardDecision 기반 조건부 메모리 강화
     * - ALLOW: 정상 저장 (시선1)
     * - ALLOW_NO_MEMORY: 저장 금지 (시선2 Free 모드)
     * - BLOCK/REWRITE: 저장 안함
     */
    @Transactional
    public void reinforceFromGuardDecision(String sessionId,
            String query,
            com.example.lms.service.guard.EvidenceAwareGuard.GuardDecision decision) {
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }
        if (!memoryEnabled) {
            log.debug("[MemoryReinforce] Disabled. Skipping for sessionHash={}", com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }
        if (decision == null) {
            log.debug("[MemoryReinforce] Skipped: decision is null");
            return;
        }

        switch (decision.action()) {
            case ALLOW -> {
                // 시선1: 정상 저장 - evidenceList에서 snippet 추출
                var evidenceList = decision.evidenceList();
                if (evidenceList != null && !evidenceList.isEmpty()) {
                    for (var evidence : evidenceList) {
                        String snippet = evidence.snippet();
                        if (snippet == null || snippet.isBlank()) {
                            continue;
                        }
                        try {
                            reinforceWithSnippet(sessionId, query, snippet, "guard-evidence", 0.7);
                        } catch (Exception ex) {
                            log.debug("[MemoryReinforce] snippet reinforcement failed: {}", errorSummary(ex));
                        }
                    }
                }
                // finalDraft도 저장 (요약본/최종 답변)
                if (decision.finalDraft() != null && !decision.finalDraft().isBlank()) {
                    try {
                        reinforceWithSnippet(sessionId, query, decision.finalDraft(), "guard-answer", 0.6);
                    } catch (Exception ex) {
                        log.debug("[MemoryReinforce] finalDraft reinforcement failed: {}", errorSummary(ex));
                    }
                }
            }

            case ALLOW_NO_MEMORY -> {
                // 시선2: 저장 금지 (Free 모드)
                log.debug("[MemoryReinforce] Skipped due to ALLOW_NO_MEMORY action (Vision 2 / Free Mode)");
            }
            default -> {
                // BLOCK / REWRITE / 기타 액션은 메모리에 저장하지 않음
                log.debug("[MemoryReinforce] Skipped due to action: {}", decision.action());
            }
        }
    }

    /**
     * MemoryMode-aware variant of reinforceFromGuardDecision.
     * When memoryMode is not write-enabled, guard-driven reinforcement is skipped.
     */
    @Transactional
    public void reinforceFromGuardDecision(String sessionId,
            String query,
            com.example.lms.service.guard.EvidenceAwareGuard.GuardDecision decision,
            MemoryMode memoryMode) {
        if (skipReinforceForSensitive()) {
            log.debug("[MEMORY_GATE] sensitive/forceOff -> skip reinforcement");
            return;
        }
        if (memoryMode != null && !memoryMode.isWriteEnabled()) {
            log.debug("[MEMORY_MODE] skip guard-based reinforcement (mode={}) for sessionHash={}",
                    memoryMode, com.example.lms.trace.SafeRedactor.hashValue(String.valueOf(sessionId)));
            return;
        }
        reinforceFromGuardDecision(sessionId, query, decision);
    }

    /**
     * GuardDecision을 받아 시선1 조건을 만족하는 경우에만 강화합니다.
     */
    @Transactional
    public void reinforceWithSnippet(
            String sessionId,
            String query,
            String snippet,
            String sourceTag,
            double score,
            com.example.lms.service.guard.EvidenceAwareGuard.GuardDecision decision) {

        // 시선1만 강화 허용
        if (decision != null &&
                decision.action() != com.example.lms.service.guard.EvidenceAwareGuard.GuardAction.ALLOW) {
            log.debug("[MEMORY] Skipping reinforcement: action={}", decision.action());
            return;
        }

        double adjustedScore = adjustScoreByGuard(decision, score);
        reinforceWithSnippet(sessionId, query, snippet, sourceTag, adjustedScore);
    }

    public Map<String, Object> getReinforcementStats() {
        LinkedHashMap<String, Object> stats = new LinkedHashMap<>();
        stats.put("memoryEnabled", memoryEnabled);
        stats.put("progressiveMemoryMode", progressiveMemoryMode);
        stats.put("reinforcementMode", reinforcementMode == null ? "CONSERVATIVE" : reinforcementMode);
        stats.put("citationGateMode", citationGateMode == null ? "SOFT" : citationGateMode);
        stats.put("lowScoreCutoff", lowScoreCutoff);
        stats.put("minContentLength", minContentLength);
        stats.put("maxContentLength", maxContentLength);
        stats.put("autolearnMemoryReinforcementEnabled", autolearnMemoryReinforcementEnabled);
        stats.put("saveWeakAsPending", saveWeakAsPending);
        stats.put("pendingMinScore", pendingMinScore);
        return stats;
    }
    /*
     * =========================================================
     * 이하, 기존 서비스 내부 유틸/메서드 유지
     * - normalizeSessionId(...)
     * - isStableSid(...)
     * - storageHashFromSnippet(...)
     * - upsertViaRepository(...)
     * - reward(...)
     * - etc.
     * =========================================================
     */

    private boolean isSubcultureContent(String query, String content) {
        String s = ((query == null ? "" : query) + " " + (content == null ? "" : content))
                .toLowerCase(java.util.Locale.ROOT);
        return s.matches(".*(원신|genshin|마비카|푸리나|스타레일|붕괴|게임|애니|만화|캐릭터|공략).*");
    }
}
