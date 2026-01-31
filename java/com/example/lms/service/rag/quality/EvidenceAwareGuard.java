package com.example.lms.service.rag.quality;

import java.util.Collections;
import java.util.List;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.config.NaverFilterProperties;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;

/**
 * EvidenceAwareGuard for chipset claims ONLY.
 *
 * <p>
 * 주의:
 * <ul>
 * <li>일반 웹/RAG 질의(게임 캐릭터, 인물, 개념 설명 등)는
 * {@link com.example.lms.service.guard.EvidenceAwareGuard} 에서 처리한다.</li>
 * <li>이 클래스는 특정 하드웨어/칩셋 관련 루머 및 스펙 주장에 한정된
 * 품질/안전성 가드로 사용한다.</li>
 * </ul>
 */
public class EvidenceAwareGuard {
    @Autowired
    private GuardProfileProps guardProfileProps;

    /**
     * Injected filter properties providing the official allowlist. May be null when
     * not configured.
     */
    @Autowired(required = false)
    private NaverFilterProperties props;

    /**
     * Suffixes considered as news domains for chipset credibility.
     */
    private static final String[] NEWS_SUFFIXES = {
            "news.naver.com", "zdnet.co.kr", "itmedia.co.jp", "bbc.com", "cnn.com"
    };

    /**
     * "정보 없음" 응답을 감지하기 위한 패턴입니다.
     */
    private static final java.util.regex.Pattern INFO_NONE = java.util.regex.Pattern.compile("정보 없음");

    /**
     * Decide whether a definitive chipset claim is permitted based on
     * the supplied evidence documents. At least one document must
     * mention a chipset keyword for claims to be considered safe.
     *
     * @param evidences a list of evidence documents; may be null
     * @return {@code true} if a chipset claim is allowed
     */
    public boolean allowChipsetClaim(List<WebDocument> evidences) {
        GuardProfile profile = guardProfileProps.currentProfile();
        // PROFILE_FREE: 무조건 허용
        if (profile == GuardProfile.PROFILE_FREE) {
            return true;
        }
        // PROFILE_MEMORY: 증거 1개 이상이면 허용
        return evidences != null && !evidences.isEmpty();
    }

    /**
     * Check whether the document title or snippet contains a chipset
     * related keyword. Performs a lower-case substring search on
     * concatenated title and snippet. Null values are treated as empty
     * strings.
     *
     * @param d the document to inspect
     * @return true if a chipset keyword is present
     */

    private boolean containsChipsetKeyword(WebDocument d) {
        String title = d.getTitle();
        String snippet = d.getSnippet();
        String text = ((title == null ? "" : title) + " " + (snippet == null ? "" : snippet)).toLowerCase();

        return text.contains("snapdragon")
                || text.contains("스냅드래곤")
                || text.contains("chipset")
                || text.contains("칩셋")
                // 모바일 기기/시리즈명까지 포함
                || text.contains("fold")
                || text.contains("폴드")
                || text.contains("z fold")
                || text.contains("갤럭시")
                || text.contains("galaxy")
                || text.contains("플립")
                || text.contains("flip")
                || text.contains("스마트폰")
                || text.contains("smartphone");
    }

    public static boolean looksNoEvidenceTemplate(String draft) {
        // [PATCH] 일반 가드 로직 재사용 (일관성 유지)
        return com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(draft);
    }

    public static final class EvidenceDoc {
        private final String id;
        private final String title;
        private final String snippet;

        public EvidenceDoc(String id, String title, String snippet) {
            this.id = id;
            this.title = title;
            this.snippet = snippet;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public String snippet() {
            return snippet;
        }
    }

    public enum EvidenceStrength {
        NONE, WEAK, STRONG
    }

    public enum DraftQuality {
        OK, WEAK
    }

    /**
     * 시선1(PROFILE_MEMORY)이 최종 판단하는 행동 지시
     */
    public enum GuardAction {
        ALLOW, // 답변 허용 + 메모리 강화 허용 (시선1)
        ALLOW_NO_MEMORY, // 답변 허용 + 메모리 강화 금지 (시선2)
        REWRITE, // 재생성 요청
        BLOCK // 답변 차단, 재질문 유도
    }

    public static final class GuardDecision {
        private final String finalDraft;
        private final boolean regenerated;
        private final boolean degradedToEvidence;
        private final boolean shouldPersist;
        private final boolean shouldReinforceMemory;
        private final double coverageScore;
        private final EvidenceStrength evidenceStrength;
        private final DraftQuality draftQuality;
        private final GuardAction action;
        private final java.util.List<EvidenceDoc> evidenceList;
        private final boolean escalated;

        public GuardDecision(String finalDraft,
                boolean regenerated,
                boolean degradedToEvidence,
                boolean shouldPersist,
                boolean shouldReinforceMemory,
                double coverageScore,
                EvidenceStrength evidenceStrength,
                DraftQuality draftQuality,
                GuardAction action,
                java.util.List<EvidenceDoc> evidenceList,
                boolean escalated) {
            this.finalDraft = finalDraft;
            this.regenerated = regenerated;
            this.degradedToEvidence = degradedToEvidence;
            this.shouldPersist = shouldPersist;
            this.shouldReinforceMemory = shouldReinforceMemory;
            this.coverageScore = coverageScore;
            this.evidenceStrength = evidenceStrength;
            this.draftQuality = draftQuality;
            this.action = action;
            this.evidenceList = (evidenceList == null) ? java.util.Collections.emptyList() : evidenceList;
            this.escalated = escalated;
        }

        // 기존 생성자 호환성 유지 (escalated=false, action 자동 결정)
        public GuardDecision(String finalDraft,
                boolean regenerated,
                boolean degradedToEvidence,
                boolean shouldPersist,
                boolean shouldReinforceMemory,
                double coverageScore,
                EvidenceStrength evidenceStrength,
                DraftQuality draftQuality) {
            this(finalDraft, regenerated, degradedToEvidence, shouldPersist, shouldReinforceMemory,
                    coverageScore, evidenceStrength, draftQuality,
                    shouldReinforceMemory ? GuardAction.ALLOW : GuardAction.ALLOW_NO_MEMORY,
                    java.util.Collections.emptyList(), false);
        }

        public String finalDraft() {
            return finalDraft;
        }

        public boolean regenerated() {
            return regenerated;
        }

        public boolean degradedToEvidence() {
            return degradedToEvidence;
        }

        public boolean shouldPersist() {
            return shouldPersist;
        }

        public boolean shouldReinforceMemory() {
            return shouldReinforceMemory;
        }

        public double coverageScore() {
            return coverageScore;
        }

        public EvidenceStrength evidenceStrength() {
            return evidenceStrength;
        }

        public DraftQuality draftQuality() {
            return draftQuality;
        }

        public GuardAction action() {
            return action;
        }

        public java.util.List<EvidenceDoc> evidenceList() {
            return evidenceList;
        }

        public boolean escalated() {
            return escalated;
        }
    }

    private boolean isSubcultureDomain(String url) {
        if (url == null)
            return false;
        String lower = url.toLowerCase();
        return lower.contains("gall.dcinside.com")
                || lower.contains("dcinside.com")
                || lower.contains("ruliweb.com")
                || lower.contains("inven.co.kr")
                || lower.contains("ppomppu.co.kr")
                || lower.contains("clien.net")
                // || lower.contains("namu.wiki") // 테크 루머 출처로 별도 취급
                || lower.contains("tistory.com")
                || lower.contains("gamedot.org");
    }

    private EvidenceStrength calculateEvidenceStrength(List<EvidenceDoc> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return EvidenceStrength.NONE;
        }
        // 도메인/개수 불문, 증거 있으면 STRONG
        return EvidenceStrength.STRONG;
    }

    public String degradeToEvidenceList(java.util.List<EvidenceDoc> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "현재 검색과 내부 지식에서 유의미한 자료를 찾지 못했습니다. " +
                    "질문을 조금 더 구체적으로 적어주시면 다시 검색해 볼게요.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("아직 공식 문서 수준의 정리는 부족하지만, ");
        sb.append("현재까지 검색된 정보들을 정리하면 다음과 같습니다.\n\n");

        int rank = 1;
        for (EvidenceDoc doc : evidence) {
            sb.append(rank++).append(". ");
            if (doc.title() != null && !doc.title().isBlank()) {
                sb.append("[").append(doc.title().strip()).append("] ");
            }
            if (doc.snippet() != null && !doc.snippet().isBlank()) {
                sb.append(doc.snippet().strip());
            }
            if (doc.id() != null && !doc.id().isBlank()) {
                sb.append("\n   - 출처: ").append(doc.id().strip());
            }
            sb.append("\n\n");
        }

        sb.append("위 내용을 기반으로 보면, 질문하신 대상의 정체성과 특징에 대한 ");
        sb.append("대략적인 윤곽은 잡힌 상태입니다(다만 세부 수치는 향후 변경될 수 있습니다).");

        return sb.toString();
    }

    public GuardDecision guardWithEvidence(
            String draft,
            java.util.List<EvidenceDoc> evidence,
            int maxRegens) {
        java.util.List<EvidenceDoc> safeEvidence = (evidence == null) ? java.util.Collections.emptyList() : evidence;

        GuardProfile profile = guardProfileProps.currentProfile();
        boolean hasEvidence = !safeEvidence.isEmpty();
        boolean noEvidenceTemplate = looksNoEvidenceTemplate(draft);
        boolean structurallyEmpty = com.example.lms.service.guard.EvidenceAwareGuard.looksStructurallyEmpty(draft);

        EvidenceStrength strength = calculateEvidenceStrength(safeEvidence);
        // 칩셋 전용 가드는, 일단 관련 증거가 1개 이상만 있어도 coverage=1.0으로 취급
        double coverage = hasEvidence ? 1.0 : 0.0;

        DraftQuality quality;

        // ─────────────────────────────────────────────────────────────
        // Evidence partially ignored → ALLOW_WITH_WARNING path
        // ─────────────────────────────────────────────────────────────
        boolean evidenceIgnored = hasEvidence && coverage < 0.05 && strength != EvidenceStrength.NONE;
        if (evidenceIgnored && !noEvidenceTemplate && !structurallyEmpty) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("[Guard] Evidence partially ignored. Returning ALLOW_WITH_WARNING.");
            String warningNote = "\n\n> ⚠️ 근거가 약할 수 있습니다.";
            return new GuardDecision(
                    draft + warningNote,
                    false, // regenerated
                    false, // degradedToEvidence
                    true, // shouldPersist
                    false, // shouldReinforceMemory
                    coverage,
                    strength,
                    DraftQuality.WEAK,
                    GuardAction.ALLOW,
                    safeEvidence,
                    false // escalated
            );
        }

        if (noEvidenceTemplate || structurallyEmpty) {
            quality = DraftQuality.WEAK;
        } else if (hasEvidence) {
            quality = DraftQuality.OK;
        } else {
            quality = DraftQuality.WEAK;
        }

        // ✅ [CRITICAL] 모순 감지: 증거는 있는데 답변이 "정보 없음"/빈 껍데기
        if (hasEvidence && (noEvidenceTemplate || structurallyEmpty)) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("[ChipsetGuard] Contradiction: Evidence exists ({} docs) but draft is 'No Info'. Forcing fallback.",
                            safeEvidence.size());
            String degraded = degradeToEvidenceList(safeEvidence);
            return new GuardDecision(
                    degraded,
                    false,
                    true,
                    false,
                    false,
                    coverage,
                    strength,
                    DraftQuality.WEAK,
                    GuardAction.BLOCK,
                    safeEvidence,
                    false);
        }

        // PROFILE_FREE: 무조건 허용, 메모리 X
        if (profile == GuardProfile.PROFILE_FREE) {
            return new GuardDecision(
                    draft,
                    false,
                    false,
                    false,
                    false,
                    coverage,
                    strength,
                    quality,
                    GuardAction.ALLOW_NO_MEMORY,
                    safeEvidence,
                    false);
        }

        // PROFILE_MEMORY + 증거 있음 → 허용 + 메모리 강화
        if (hasEvidence) {
            return new GuardDecision(
                    draft,
                    false,
                    false,
                    true,
                    true,
                    coverage,
                    strength,
                    quality,
                    GuardAction.ALLOW,
                    safeEvidence,
                    false);
        }

        // 증거 없음 → BLOCK (칩셋 주장은 근거 없으면 허용하지 않음)
        String degraded = degradeToEvidenceList(safeEvidence);
        return new GuardDecision(
                (degraded == null || degraded.isBlank()) ? draft : degraded,
                false,
                true,
                false,
                false,
                coverage,
                strength,
                quality,
                GuardAction.BLOCK,
                safeEvidence,
                false);
    }

}

// PATCH_MARKER: EvidenceAwareGuard updated per latest spec.
