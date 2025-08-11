package com.example.lms.service;
import java.util.regex.Pattern;
import com.example.lms.service.VectorStoreService; // 실제 위치가 다르면 패키지 경로만 맞춰주세요
import java.util.regex.Pattern;
import com.example.lms.service.VectorStoreService; // 실제 패키지 맞게 유지

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.reinforcement.RewardScoringEngine;
import com.example.lms.service.reinforcement.SnippetPruner;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import java.lang.reflect.Method;                 // NEW
import java.nio.charset.StandardCharsets;       // NEW
import java.security.MessageDigest;             // NEW
import java.util.List;

@Slf4j
@Service
@Transactional
public class MemoryReinforcementService {

    // ===== [볼츠만/담금질 상수] =====
    private static final double W_SIM  = 1.0;
    private static final double W_Q    = 0.25;
    private static final double W_SR   = 0.50;
    private static final double W_EXPL = 0.10;
    private static final double T0     = 1.0;

    private static final int STATUS_ACTIVE = 1;

    /* ────── 점수 가중치/컷오프 ────── */
    @Value("${memory.reinforce.score.low-quality-threshold:0.3}")
    private double lowScoreCutoff;

    /* ─────────────── DI ─────────────── */
    private final TranslationMemoryRepository memoryRepository;
    private final RewardScoringEngine rewardEngine = RewardScoringEngine.DEFAULT;
    private final VectorStoreService vectorStoreService;
    private final SnippetPruner snippetPruner;

    private LoadingCache<String, Boolean> recentSnippetCache;

    public MemoryReinforcementService(TranslationMemoryRepository memoryRepository,
                                      VectorStoreService vectorStoreService,
                                      SnippetPruner snippetPruner) {
        this.memoryRepository   = memoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.snippetPruner      = snippetPruner;
    }

    @PostConstruct
    private void initRecentSnippetCache() {
        // ... (기존 초기화 로직 유지)
        // 예시) recentSnippetCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).build(k -> Boolean.TRUE);
    }
    /** 0~1 보상값 클램프 */
    private double reward(double base) {
        if (Double.isNaN(base) || Double.isInfinite(base)) return 0.0;
        return Math.max(0.0, Math.min(1.0, base));
    }

    /** 간단 UPSERT: source_hash 기준으로 존재하면 갱신, 없으면 신규 생성 */
    private void upsertViaRepository(String sid,
                                     String query,
                                     String payload,
                                     String sourceTag,
                                     double score,
                                     String hash) {

        TranslationMemory tm = memoryRepository.findBySourceHash(hash)
                .orElseGet(TranslationMemory::new);

        if (tm.getId() == null) {
            tm.setSourceHash(hash);
            tm.setSessionId((sid == null || sid.isBlank()) ? "*" : sid);
            // 존재하는 수치 필드만 안전하게 초기화
            tm.setHitCount(0);
            tm.setSuccessCount(0);
            tm.setFailureCount(0);
            tm.setCosineSimilarity(0.0);
            tm.setQValue(0.0);
        }


        // 관측 1회
        tm.setHitCount(tm.getHitCount() + 1);
        if (score >= 0.5) tm.setSuccessCount(tm.getSuccessCount() + 1);
        else              tm.setFailureCount(tm.getFailureCount() + 1);

        // Q-value: 지수이동평균(EMA) 형태로 업데이트 (0.2 반영률)
        double prevQ = tm.getQValue();
        tm.setQValue(prevQ + 0.2 * (reward(score) - prevQ));

        // 에너지/온도 계산 및 반영
        double energy = computeBoltzmannEnergy(tm);
        double temp   = annealTemperature(tm.getHitCount());
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

    /* =========================================================
     *  볼츠만 에너지 & 담금질 (기존 시그니처 유지)
     * ========================================================= */

    private static double computeBoltzmannEnergy(TranslationMemory tm) {
            if (tm == null) return 0.0;

            double cosSim = (tm.getCosineSimilarity() == null ? 0.0 : tm.getCosineSimilarity());
            Double qObj   = tm.getQValue();                 // <= null-safe
            double qValue = (qObj == null ? 0.0 : qObj);

            int hit     = tm.getHitCount();
            int success = tm.getSuccessCount();
            int failure = tm.getFailureCount();

            double successRatio = (hit <= 0) ? 0.0 : (double) (success - failure) / hit;
            double exploreTerm  = 1.0 / Math.sqrt(hit + 1.0);

            return -(W_SIM * cosSim + W_Q * qValue + W_SR * successRatio) + W_EXPL * exploreTerm;
        }
    private static double annealTemperature(int hit) {
        return T0 / Math.sqrt(hit + 1.0);
    }

    /* =========================================================
     *  피드백 적용 (기존 코드 유지, 내부 호출에서 updateEnergyAndTemperature 사용)
     * ========================================================= */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = DataIntegrityViolationException.class)
    public int bumpOnly(String hash) {
        return memoryRepository.incrementHitCountBySourceHash(hash);
    }
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
            // (기존 긍/부정 점수 업데이트 로직)
            // ... upsertViaRepository(sid, ...), incrementHitCountBySourceHash(...) 등 기존 구현 유지 ...

            // 핵심: 에너지/온도 갱신
            updateEnergyAndTemperature(msgHash);

            // 2) 수정문 보강 (있을 때만)
            if (StringUtils.hasText(correctedText) && !correctedText.equals(messageContent)) {
                String refined = correctedText.trim();
                if (refined.length() > 4000) refined = refined.substring(0, 4000);

                String corrHash   = storageHashFromSnippet(refined);
                double sCorr      = reward(0.98); // 수정문 강한 보상
                String payloadCorr = "[SRC:USER_CORRECTION] " + refined;
                upsertViaRepository(sid, /*query*/ null, payloadCorr, "USER_CORRECTION", sCorr, corrHash);

                updateEnergyAndTemperature(corrHash);

                try { vectorStoreService.enqueue(sid, refined); } catch (Exception ignore) {}
            }

            log.debug("[Feedback] applied (sid={}, pos={}, msgHash={})", sid, positive, safeHash(msgHash));
        } catch (Exception e) {
            log.error("[Feedback] applyFeedback failed", e);
            throw e;
        }
    }

    /**
     * 해시를 기반으로 메모리를 찾아 에너지/온도를 계산 후 DB에 반영.
     */
    private void updateEnergyAndTemperature(String sourceHash) {
        memoryRepository.findBySourceHash(sourceHash).ifPresent(tm -> {
            double energy = computeBoltzmannEnergy(tm);
            double temp   = annealTemperature(tm.getHitCount());

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

    /* =========================================================
     *  ▼▼▼ Backward-Compat Adapter API (호환 레이어) ▼▼▼
     *  기존 호출부가 참조하는 시그니처를 그대로 제공
     * ========================================================= */

    /**
     * 기존 호출부:
     * reinforceWithSnippet(sessionId, query, snippet, sourceTag, score)
     */
    @Transactional
    public void reinforceWithSnippet(String sessionId,
                                     String query,
                                     String snippet,
                                     String sourceTag,
                                     double score) {
        if (!StringUtils.hasText(snippet)) return;

        String sid  = StringUtils.hasText(sessionId) ? sessionId : "*";
        String hash = sha1(snippet);

        // 기존 레코드 조회 or 새로 생성
        TranslationMemory tm = memoryRepository.findBySourceHash(hash)
                .orElseGet(TranslationMemory::new);

        if (tm.getId() == null) {
            tm.setSourceHash(hash);
            tm.setSessionId(sid);
            tm.setHitCount(0);
            tm.setSuccessCount(0);
            tm.setFailureCount(0);
            tm.setQValue(0.0);
            tm.setCosineSimilarity(0.0);
        }

        // 간단 규칙: score 기준 성공/실패 카운트
        tm.setHitCount((tm.getHitCount() == null ? 0 : tm.getHitCount()) + 1);
        boolean success = score >= 0.5;
        if (success) {
            tm.setSuccessCount(tm.getSuccessCount() + 1);
        } else {
            tm.setFailureCount(tm.getFailureCount() + 1);
        }

        // Q-value 업데이트(0~1로 클램프)
        double q = Math.max(0.0, Math.min(1.0, score));
        tm.setQValue(q);

        // 에너지/온도 계산
        double energy = computeBoltzmannEnergy(tm);
        double temp   = annealTemperature(tm.getHitCount());
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
    }

    /**
     * 기존 호출부: loadContext(sessionId)
     *  → 상위 저에너지 10개를 합쳐 문자열 컨텍스트 반환
     */
    @Transactional(readOnly = true)
    public String loadContext(String sessionId) {
        try {
            List<TranslationMemory> list =
                    memoryRepository.findTop10BySessionIdAndEnergyNotNullOrderByEnergyAsc(sessionId);
            if (list == null || list.isEmpty()) {
                list = memoryRepository.findTop10ByEnergyNotNullOrderByEnergyAsc();
            }
            StringBuilder sb = new StringBuilder();
            for (TranslationMemory tm : list) {
                String txt = extractTextViaReflection(tm);
                if (StringUtils.hasText(txt)) {
                    if (sb.length() > 0) sb.append("\n\n---\n\n");
                    sb.append(txt);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 안전하게 빈 컨텍스트 반환
            return "";
        }
    }

    /**
     * 기존 호출부: reinforceMemoryWithText(text)
     */
    public void reinforceMemoryWithText(String text) {
        if (!StringUtils.hasText(text)) return;
        // 세션 미상 → 공용("*")으로 적재, 보수적 점수 0.5
        reinforceWithSnippet("*", "", text, "TEXT", 0.5);
    }

    /* ────────────── 호환 유틸 ────────────── */

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            // 예외 시 fallback 해시
            return Integer.toHexString(s.hashCode());
        }
    }

    /** TranslationMemory 안의 텍스트 필드명을 모를 때 안전 추출 */
    private static String extractTextViaReflection(TranslationMemory tm) {
        String[] candidates = {"getText", "getContent", "getTargetText", "getSourceText", "getValue", "toString"};
        for (String m : candidates) {
            try {
                Method method = tm.getClass().getMethod(m);
                Object v = method.invoke(tm);
                if (v != null) {
                    String s = v.toString();
                    if (StringUtils.hasText(s)) return s;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
    /* ====================== Missing helpers (added) ====================== */

    /** 세션키 정규화: 숫자면 chat- 접두, 없으면 "*" */
    private static String normalizeSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return "*";
        String s = sessionId.trim();
        if (s.startsWith("chat-")) return s;
        if (s.matches("\\d+")) return "chat-" + s;
        return s;
    }

    /** “안정적인” 세션키 판단: 공용(*) | chat- 접두 | 6자 이상 영숫자/대시 */
    private static boolean isStableSid(String sid) {
        if (!StringUtils.hasText(sid)) return false;
        if ("*".equals(sid)) return true;
        if (sid.startsWith("chat-")) return true;
        return Pattern.compile("^[A-Za-z0-9\\-]{6,}$").matcher(sid).matches();
    }

    /** 스니펫을 저장용 해시로 변환 (현재는 SHA-1 사용) */
    private static String storageHashFromSnippet(String s) {
        if (s == null) return null;
        return sha1(s.trim());
    }

/** 보상**

    /* =========================================================
     *  이하, 기존 서비스 내부 유틸/메서드 유지
     *  - normalizeSessionId(...)
     *  - isStableSid(...)
     *  - storageHashFromSnippet(...)
     *  - upsertViaRepository(...)
     *  - reward(...)
     *  - etc.
     * ========================================================= */
}