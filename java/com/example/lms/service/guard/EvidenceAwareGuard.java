
        package com.example.lms.service.guard;

import java.util.Collections;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class EvidenceAwareGuard {

    /**
     * Lightweight context describing how aggressively the guard should behave.
     * When {@code isAggressiveMode} is true (e.g. Brave/Hypernova plans),
     * we relax some quality checks and rely more on downstream scoring.
     */
    public record GuardContext(String planId, boolean isAggressiveMode) { }


    private static final Logger log = LoggerFactory.getLogger(EvidenceAwareGuard.class);


    public static final Pattern INFO_NONE = Pattern.compile(
        "(" +
                // 한글 회피 템플릿 (확장)
                "(충분|명확|신뢰)\s*한?\s*(증거|자료|정보|근거|내용).{0,20}(찾지\s*못했|확보\s*하지\s*못했|부족|없습)" +
                "|" +
                "(자료|정보|근거|내용).{0,15}(부족|없[음다]?|부재).{0,15}(답변|분석|파악|확인|제공).{0,15}(어렵|불가|힘들|수\s*없|할\s*수\s*없)" +
                "|" +
                "명확한\s*결론을\s*내리기\s*(어렵|힘들)" +
                "|" +
                "제공된\s*문서에서\s*.{0,20}찾을\s*수\s*없" +
                "|" +
                // 영어 회피 템플릿
                "insufficient\s*(evidence|information|data)" +
                "|" +
                "no\s*relevant\s*(information|data)" +
                ")",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
);

    private static final Pattern TITLE_TOKENS =
            Pattern.compile("\\s+|[\\u3000-\\u303F\\p{Punct}]");


    /**
     * 한국어 질의 키워드 정규화 유틸리티.
     * 조사(particles)와 질문어(question words)를 제거하여 순수 명사/엔티티만 추출한다.
     */
    private static class KoreanQueryNormalizer {

        // 질문어 패턴 (answer에 거의 나타나지 않음)
        private static final java.util.Set<String> QUESTION_WORDS = java.util.Set.of(
                "뭐야", "뭐냐", "뭐지", "뭔가", "뭘까",
                "누구야", "누구냐", "누구지", "누군가",
                "무엇", "무엇이야", "어떤", "어떤거야", "어디"
        );

        // 조사 패턴 (suffix removal)
        private static final java.util.List<String> JOSA_SUFFIXES = java.util.List.of(
                "에서", "에게", "으로", "로서", "라고", "이라고",
                "은", "는", "이", "가", "을", "를",
                "과", "와", "도", "만", "부터", "까지",
                "의", "에", "께서"
        );

        /**
         * 쿼리를 정규화된 키워드 리스트로 변환한다.
         * 예: "원신에서 푸리나가 뭐야?" → ["원신", "푸리나"]
         */
        public static java.util.List<String> normalize(String query) {
            if (query == null || query.isBlank()) {
                return java.util.Collections.emptyList();
            }

            // 1) 기본 전처리: 공백 정규화, 문장부호 제거
            String cleaned = query.replaceAll("[?!.,;:]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleaned.isEmpty()) {
                return java.util.Collections.emptyList();
            }

            // 2) 공백 기준 토큰 분리
            String[] tokens = cleaned.split("\\s+");
            java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();

            for (String tok : tokens) {
                if (tok == null || tok.isEmpty()) {
                    continue;
                }

                // 3) 질문어 제거
                if (QUESTION_WORDS.contains(tok)) {
                    continue;
                }

                // 4) 조사 제거 (suffix matching)
                String normalized = tok;
                for (String josa : JOSA_SUFFIXES) {
                    if (normalized.endsWith(josa) && normalized.length() > josa.length()) {
                        normalized = normalized.substring(0, normalized.length() - josa.length());
                        break;  // 한 토큰당 하나만 제거
                    }
                }

                // 5) 길이 필터 (1글자 이하 제거)
                if (normalized.length() >= 2) {
                    result.add(normalized);
                }
            }

            // 6) 중복 제거 (순서 유지)
            return new java.util.ArrayList<>(result);
        }
    }

    public record EvidenceDoc(String id, String title, String snippet) {}

    public static class Result {
        public final String answer;
        public final boolean escalated;
        public Result(String answer, boolean escalated) { this.answer = answer; this.escalated = escalated; }

        // 👇 [변경] ChatService에서 호출할 접근자 메서드 추가
        public boolean escalated() {
            return this.escalated;
        }

        public String regeneratedText() {
            // 호환성을 위해 answer를 반환 (필요시 재생성 로직 추가 가능)
            return this.answer;
        }
    }


private double computeEvidenceQuality(java.util.List<EvidenceDoc> docs) {
    if (docs == null || docs.isEmpty()) {
        return 0.0;
    }
    double score = 0.0;
    int count = docs.size();

    // 🔥 [PATCH] 기본 개수 기반 점수 (가중치 상향)
    if (count >= 3) {
        score += 0.6;  // 🔥 0.4 → 0.6
    } else if (count >= 1) {
        score += 0.4;  // 🔥 0.2 → 0.4 (1개만 있어도 유의미)
    }

    // 서브컬처/위키/커뮤니티 도메인 가중치 (기존 유지)
    long subcultureCount = docs.stream()
            .filter(d -> isSubcultureDomain(d.id()))
            .count();
    if (subcultureCount > 0) {
        score += 0.5;  // 나무위키/게임닷 있으면 +0.5
    }

    // 스니펫 길이 기반 보정 (기존 유지)
    int totalChars = docs.stream()
            .mapToInt(d -> d.snippet() == null ? 0 : d.snippet().length())
            .sum();
    if (totalChars > 200) {
        score += 0.2;
    }

    return Math.min(1.0, Math.max(0.0, score));
}



    public Result ensureCoverage(
            String draft,
            List<EvidenceDoc> topDocs,
            java.util.function.Function<RouteSignal, ChatModel> escalateFn,
            RouteSignal signal,
            int minEntitiesCovered) {

        List<EvidenceDoc> safeEvidence =
                (topDocs == null) ? Collections.emptyList() : topDocs;

        if (draft == null) {
            draft = "";
        }

        int coverage = estimateCoverage(draft, safeEvidence);
        boolean infoNone = INFO_NONE.matcher(draft).find();
        double evidenceQuality = computeEvidenceQuality(safeEvidence);

        int evidenceCount = safeEvidence.size();
        boolean hasEvidence = evidenceCount > 0;

        // Definitional questions (e.g. "X가 뭐야?/X는 누구야?") have relaxed coverage requirements
        // because 핵심 엔티티 하나만 제대로 커버되면 충분한 경우가 많다.
        boolean isDefinitional = draft.matches("(?i).*(뭐야|뭐냐|누구야|누구냐|무엇|what\\s+is|who\\s+is).*");
        int threshold = isDefinitional
                ? Math.max(1, minEntitiesCovered / 2)
                : minEntitiesCovered;

        boolean coverageWeak = coverage < threshold;
        boolean weakDraft = coverageWeak || infoNone;
        boolean inconsistentTemplate = hasEvidence && infoNone;

        // [NEW] 증거는 충분한데, 답변에 증거가 전혀 반영되지 않은 케이스까지 감지
        boolean strongEvidenceIgnored = (evidenceQuality >= 0.5) && (coverage == 0);

        // [수정] strongEvidenceIgnored는 Fallback이 아닌 Escalation 트리거로만 사용
        boolean shouldFallback = inconsistentTemplate;

        if (shouldFallback) {
            log.warn("[guard] Early Fallback: quality={}, coverage={}, infoNone={}, inconsistentTemplate={}",
                    evidenceQuality, coverage, infoNone, inconsistentTemplate);
            String fallback = degradeToEvidenceList(safeEvidence);
            // escalated=false로 설정하여 상위 모델 재호출 루프를 끊음
            return new Result(fallback, false);
        }

        // 품질이 낮은 inconsistentTemplate 경고 (fallback 미적용)
        if (inconsistentTemplate) {
            log.warn("[guard] Inconsistent state: 'no-evidence' draft detected (count={}, coverage={}) - quality low, proceeding to standard logic.",
                    evidenceCount, coverage);
        }


// 2) 그 외(coverage 부족이거나 정보 없음 템플릿, 혹은 증거 무시)에는 기존 에스컬레이션 로직 사용
        // 2) 그 외(coverage 부족이거나 정보 없음 템플릿, 혹은 증거 무시)에는 기존 에스컬레이션 로직 사용
        // [수정] strongEvidenceIgnored도 에스컬레이션 대상에 추가
        boolean needsEscalation = (weakDraft || strongEvidenceIgnored || inconsistentTemplate) && evidenceQuality >= 0.4;
        if (needsEscalation) {
            try {
                ChatModel escalatedModel = (escalateFn != null && signal != null)
                        ? escalateFn.apply(signal)
                        : null;

                String escalatedName = (escalatedModel != null)
                        ? escalatedModel.getClass().getSimpleName()
                        : "null";

                // strongEvidenceIgnored 여부를 로그에 명시 (디버깅용)
                log.debug("[guard] Escalation: Ev.Q={} (strongIgnored={}, inconsistentTemplate={}) → model={}",
                        evidenceQuality, strongEvidenceIgnored, inconsistentTemplate, escalatedName);
            } catch (Exception e) {
                log.warn("[guard] escalation failed, falling back to original draft: {}", e.toString());
            }
            return new Result(draft, true);
        }

        // 3) 증거 충분 + 초안도 괜찮은 경우
        log.debug("[guard] Evidence quality={} → no escalation", evidenceQuality);
        return new Result(draft, false);
    }



    public String degradeToEvidenceList(List<EvidenceDoc> topDocs) {
        if (topDocs == null || topDocs.isEmpty()) {
            return "검색 결과가 존재하지 않습니다. 다른 키워드로 다시 질문해 보시겠어요?";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 검색 결과 요약\n");
        sb.append("상세한 답변을 만들 만큼 **공식 문서가 많지는 않지만**, ");
        sb.append("관련된 위키 및 커뮤니티 자료는 확보되었습니다.\n");
        sb.append("아래 검색된 내용을 참고해 주세요.\n\n");

        int rank = 1;
        for (EvidenceDoc d : topDocs) {
            sb.append(rank++).append(". ");
            if (d.title() != null && !d.title().isBlank()) {
                sb.append("**[").append(d.title().strip()).append("]** ");
            }
            if (d.snippet() != null && !d.snippet().isBlank()) {
                sb.append(d.snippet().strip());
            }
            if (d.id() != null && !d.id().isBlank()) {
                sb.append(" (").append(d.id().strip()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n(위 내용은 검색 엔진 및 커뮤니티 데이터를 바탕으로 한 것으로, ");
        sb.append("최신 업데이트와 다를 수 있습니다.)");
        return sb.toString();
    }


    
    /** 외부에서 약한 초안(정보 없음 등) 판별에 사용 */
    
public static boolean looksWeak(String draft) {
    return looksWeak(draft, null);
}

public static boolean looksWeak(String draft, GuardContext ctx) {
    if (draft == null) {
        return true;
    }
    // In aggressive modes (e.g. Brave/Hypernova/ZeroBreak), allow cautious wording
    // as long as the draft is not structurally empty. We still rely on downstream
    // scoring and safety guards to filter out bad answers.
    if (ctx != null && ctx.isAggressiveMode()) {
        return looksStructurallyEmpty(draft);
    }
    // Default behaviour: also treat "정보 없음" style templates as weak.
    return looksStructurallyEmpty(draft) || looksNoEvidenceTemplate(draft);
}

    
    private int estimateCoverage(String draft, List<EvidenceDoc> topDocs) {
        if (draft == null || draft.isBlank() || topDocs == null || topDocs.isEmpty()) {
            return 0;
        }

        int covered = 0;
        String draftLower = draft.toLowerCase();

        for (EvidenceDoc d : topDocs) {
            if (d.title() == null || d.title().isBlank()) {
                continue;
            }

            // 제목을 정규화하여 순수 키워드 추출
            java.util.List<String> titleKeywords = KoreanQueryNormalizer.normalize(d.title());

            boolean matched = false;
            for (String keyword : titleKeywords) {
                if (keyword.length() < 2) {
                    continue;
                }

                // 부분 일치 허용 (substring match)
                // "푸리나는", "푸리나가", "푸리나" 모두 "푸리나" 키워드와 매칭됨
                if (draftLower.contains(keyword.toLowerCase())) {
                    matched = true;
                    break;
                }
            }

            if (matched) {
                covered++;
            }
        }

        return covered;
    }


public static boolean looksStructurallyEmpty(String draft) {
    if (draft == null) {
        return true;
    }
    String trimmed = draft.trim();
    if (trimmed.isEmpty()) {
        return true;
    }
    return trimmed.length() < 24;
}

public static boolean looksNoEvidenceTemplate(String draft) {
    if (draft == null) {
        return false;
    }
    return INFO_NONE.matcher(draft).find();
}



public enum EvidenceStrength { NONE, WEAK, MODERATE, STRONG }
public enum DraftQuality { OK, WEAK }

public static final class GuardDecision {
    private final String finalDraft;
    private final boolean regenerated;
    private final boolean degradedToEvidence;
    private final boolean shouldPersist;
    private final boolean shouldReinforceMemory;
    private final double coverageScore;
    private final EvidenceStrength evidenceStrength;
    private final DraftQuality draftQuality;

    public GuardDecision(String finalDraft,
                         boolean regenerated,
                         boolean degradedToEvidence,
                         boolean shouldPersist,
                         boolean shouldReinforceMemory,
                         double coverageScore,
                         EvidenceStrength evidenceStrength,
                         DraftQuality draftQuality) {
        this.finalDraft = finalDraft;
        this.regenerated = regenerated;
        this.degradedToEvidence = degradedToEvidence;
        this.shouldPersist = shouldPersist;
        this.shouldReinforceMemory = shouldReinforceMemory;
        this.coverageScore = coverageScore;
        this.evidenceStrength = evidenceStrength;
        this.draftQuality = draftQuality;
    }

    public String finalDraft() { return finalDraft; }
    public boolean regenerated() { return regenerated; }
    public boolean degradedToEvidence() { return degradedToEvidence; }
    public boolean shouldPersist() { return shouldPersist; }
    public boolean shouldReinforceMemory() { return shouldReinforceMemory; }
    public double coverageScore() { return coverageScore; }
    public EvidenceStrength evidenceStrength() { return evidenceStrength; }
    public DraftQuality draftQuality() { return draftQuality; }
}



private boolean isSubcultureDomain(String url) {
    if (url == null) {
        return false;
    }
    String lower = url.toLowerCase();
    return lower.contains("namu.wiki")
        || lower.contains("tistory.com")
        || lower.contains("gamedot.org")
        || lower.contains("fandom.com")
        || lower.contains("hoyolab.com")
        || lower.contains("arca.live")
        || lower.contains("inven.co.kr")
        || lower.contains("ruliweb.com")
        || lower.contains("dcinside.com")
        || lower.contains("naver.com");
}



private EvidenceStrength calculateEvidenceStrength(java.util.List<EvidenceDoc> evidence) {
    if (evidence == null || evidence.isEmpty()) {
        return EvidenceStrength.NONE;
    }
    long subcultureSources = evidence.stream()
            .filter(e -> isSubcultureDomain(e.id()))
            .count();
    if (subcultureSources >= 3) {
        return EvidenceStrength.STRONG;
    }
    if (subcultureSources == 2) {
        return EvidenceStrength.MODERATE;
    }
    if (subcultureSources >= 1) {
        return EvidenceStrength.WEAK;
    }
    return EvidenceStrength.WEAK;
}




public GuardDecision guardWithEvidence(
        String draft,
        java.util.List<EvidenceDoc> evidence,
        int maxRegens
) {
    java.util.List<EvidenceDoc> safeEvidence =
            (evidence == null) ? java.util.Collections.emptyList() : evidence;
    boolean hasEvidence = !safeEvidence.isEmpty();

    // 🔥 [시선1 패치] 증거 1개라도 있으면 무조건 STRONG + 메모리 저장/강화 허용
    if (hasEvidence) {
        return new GuardDecision(
                draft,
                false,                     // regenerated: 재생성 안 함
                false,                     // degraded: 원문 그대로
                true,                      // 🔥 persist: 메모리 저장 허용
                true,                      // 🔥 reinforce: 자기 학습 허용
                1.0,                       // 🔥 coverageScore: 강제 만점
                EvidenceStrength.STRONG,   // 🔥 STRONG으로 고정
                DraftQuality.OK
        );
    }

    // 증거가 아예 없을 때만 기존 보수적 로직 사용
    boolean structurallyEmpty = looksStructurallyEmpty(draft);
    boolean noEvidenceTemplate = looksNoEvidenceTemplate(draft);
    EvidenceStrength strength = calculateEvidenceStrength(safeEvidence);
    DraftQuality quality = (structurallyEmpty || noEvidenceTemplate)
            ? DraftQuality.WEAK
            : DraftQuality.OK;
    String finalDraft = draft;
    boolean degraded = false;
    if (structurallyEmpty || noEvidenceTemplate) {
        finalDraft = degradeToEvidenceList(safeEvidence);
        degraded = true;
    }
    return new GuardDecision(
            finalDraft,
            false,
            degraded,
            false,      // persist X
            false,      // reinforce X
            0.0,
            strength,
            quality
    );
}



    private boolean isCommunitySource(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("namu.wiki")
                || u.contains("fandom.com")
                || u.contains("wiki")
                || u.contains("blog")
                || u.contains("reddit")
                || u.contains("dcinside")
                || u.contains("arca.live")
                || u.contains("inven.co.kr")
                || u.contains("ruliweb.com");
    }

}

// PATCH_MARKER: EvidenceAwareGuard updated per latest spec.
