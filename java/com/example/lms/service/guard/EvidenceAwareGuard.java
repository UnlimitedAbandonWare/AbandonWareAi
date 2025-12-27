
package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;
import java.util.Collections;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.guard.GuardProfile;
import com.example.lms.util.FutureTechDetector;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.guard.GuardProfileProps;

public class EvidenceAwareGuard {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAwareGuard.class);

    @Autowired(required = false)
    private GuardProfileProps guardProfileProps;

    /**
     * Lightweight context describing how aggressively the guard should behave.
     * When {@code isAggressiveMode} is true (e.g. Brave/Hypernova plans),
     * we relax some quality checks and rely more on downstream scoring.
     */
    /**
     * Lightweight context describing how aggressively the guard should behave.
     * Profile and plan-based knobs are exposed so that Jammini / projection
     * modes can softly relax quality checks without disabling safety.
     */

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
                    // 출시 전/공식 발표 없음 류 (FutureTech 포함)
                    "공식.{0,10}(정보|발표).{0,10}없" +
                    "|" +
                    "아직.{0,20}발표.{0,20}되지" +
                    "|" +
                    ".{0,20}까지만.{0,20}출시" +
                    "|" +
                    "(확인된|공개된).{0,10}정보.{0,10}없" +
                    "|" +
                    // 영어 회피 템플릿
                    "insufficient\s*(evidence|information|data)" +
                    "|" +
                    "no\s*relevant\s*(information|data)" +
                    ")",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TITLE_TOKENS = Pattern.compile("\\s+|[\\u3000-\\u303F\\p{Punct}]");

    /**
     * 한국어 질의 키워드 정규화 유틸리티.
     * 조사(particles)와 질문어(question words)를 제거하여 순수 명사/엔티티만 추출한다.
     */
    private static class KoreanQueryNormalizer {

        // 질문어 패턴 (answer에 거의 나타나지 않음)
        private static final java.util.Set<String> QUESTION_WORDS = java.util.Set.of(
                "뭐야", "뭐냐", "뭐지", "뭔가", "뭘까",
                "누구야", "누구냐", "누구지", "누군가",
                "무엇", "무엇이야", "어떤", "어떤거야", "어디");

        // 조사 패턴 (suffix removal)
        private static final java.util.List<String> JOSA_SUFFIXES = java.util.List.of(
                "에서", "에게", "으로", "로서", "라고", "이라고",
                "은", "는", "이", "가", "을", "를",
                "과", "와", "도", "만", "부터", "까지",
                "의", "에", "께서");

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
                        break; // 한 토큰당 하나만 제거
                    }
                }

                // 5) 길이 필터 (1글자 이하 제거)
                if (normalized.length() >= 2 || normalized.matches(".*[0-9a-zA-Z].*")) {
                    result.add(normalized);
                }
            }

            // 6) 중복 제거 (순서 유지)
            return new java.util.ArrayList<>(result);
        }
    }

    public record EvidenceDoc(String id, String title, String snippet) {
    }

    public static class Result {
        public final String answer;
        public final boolean escalated;

        public Result(String answer, boolean escalated) {
            this.answer = answer;
            this.escalated = escalated;
        }

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
            score += 0.6; // 🔥 0.4 → 0.6
        } else if (count >= 1) {
            score += 0.4; // 🔥 0.2 → 0.4 (1개만 있어도 유의미)
        }

        // 서브컬처/위키/커뮤니티 도메인 가중치 (기존 유지)
        long subcultureCount = docs.stream()
                .filter(d -> isSubcultureDomain(d.id()))
                .count();
        if (subcultureCount > 0) {
            score += 0.5; // 나무위키/게임닷 있으면 +0.5
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

        List<EvidenceDoc> safeEvidence = (topDocs == null) ? Collections.emptyList() : topDocs;

        if (draft == null) {
            draft = "";
        }

        int coverage = estimateCoverage(draft, safeEvidence);
        boolean infoNone = INFO_NONE.matcher(draft).find();
        double evidenceQuality = computeEvidenceQuality(safeEvidence);

        int evidenceCount = safeEvidence.size();
        boolean hasEvidence = evidenceCount > 0;

        // Definitional questions (e.g. "X가 뭐야?/X는 누구야?") have relaxed coverage
        // requirements
        // because 핵심 엔티티 하나만 제대로 커버되면 충분한 경우가 많다.
        boolean isDefinitional = draft.matches("(?i).*(뭐야|뭐냐|누구야|누구냐|무엇|what\\s+is|who\\s+is).*");
        int threshold = isDefinitional
                ? Math.max(1, minEntitiesCovered / 2)
                : minEntitiesCovered;

        boolean coverageWeak = coverage < threshold;
        boolean weakDraft = coverageWeak || infoNone;
        boolean inconsistentTemplate = hasEvidence && infoNone;

        // 정의형 질의이고 증거가 1개 이상 있으며, 최소한 일부 엔티티는 커버된 경우에는
        // coverage threshold를 만족하지 않아도 에스컬레이션을 생략하고 통과시킨다.
        boolean definitionalMinimalCoverage = isDefinitional && hasEvidence && coverage >= 1;
        if (definitionalMinimalCoverage) {
            log.debug(
                    "[guard] definitional question with minimal coverage → allow without escalation (coverage={}, threshold={}, evidenceCount={})",
                    coverage, threshold, evidenceCount);
        }

        // [NEW] 증거는 충분한데, 답변에 증거가 전혀 반영되지 않은 케이스까지 감지
        boolean strongEvidenceIgnored = (evidenceQuality >= 0.5) && (coverage == 0);

        // IMPORTANT:
        // Early Fallback으로 draft를 치환하면 guardWithEvidence()가 원래 draft를 보지 못해
        // Contradiction→REWRITE 경로가 작동하지 않음. 따라서 로그/메트릭만 남기고 draft는 유지.
        if (inconsistentTemplate) {
            try {
                TraceStore.put("guard.inconsistentTemplate", true);
                TraceStore.put("guard.inconsistentTemplate.coverage", coverage);
                TraceStore.put("guard.inconsistentTemplate.quality", evidenceQuality);
            } catch (Exception ignore) {
            }
            log.warn(
                    "[guard] Inconsistent template: evidence exists but draft looks 'No Info'. quality={}, coverage={}, evidenceCount={}.",
                    evidenceQuality, coverage, evidenceCount);
        }

        // 2) 그 외(coverage 부족이거나 정보 없음 템플릿, 혹은 증거 무시)에는 기존 에스컬레이션 로직 사용
        // 2) 그 외(coverage 부족이거나 정보 없음 템플릿, 혹은 증거 무시)에는 기존 에스컬레이션 로직 사용
        // [수정] strongEvidenceIgnored도 에스컬레이션 대상에 추가
        boolean needsEscalation = !definitionalMinimalCoverage
                && (weakDraft || strongEvidenceIgnored || inconsistentTemplate)
                && evidenceQuality >= 0.4;
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
            String title = (d == null || d.title() == null) ? "" : com.example.lms.util.HtmlTextUtil.stripAndCollapse(d.title());
            String snippet = (d == null || d.snippet() == null) ? "" : com.example.lms.util.HtmlTextUtil.stripAndCollapse(d.snippet());
            String id = (d == null || d.id() == null) ? "" : com.example.lms.util.HtmlTextUtil.normalizeUrl(d.id().strip());

            // 소스 라벨링: WEB vs RAG
            String srcLabel = inferSourceLabel(id);
            if (srcLabel != null && !title.contains("[SRC:")) {
                sb.append("**[SRC:").append(srcLabel).append("]** ");
            }
            if (!title.isBlank()) {
                sb.append("**[").append(title).append("]** ");
            }
            if (!snippet.isBlank()) {
                sb.append(snippet);
            }
            if (!id.isBlank()) {
                sb.append(" (").append(id).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n(위 내용은 검색 엔진 및 커뮤니티 데이터를 바탕으로 한 것으로, ");
        sb.append("최신 업데이트와 다를 수 있습니다.)");
        return sb.toString();
    }

    private static String inferSourceLabel(String id) {
        if (id == null || id.isBlank()) {
            return "WEB";
        }
        String s = id.toLowerCase(Locale.ROOT);
        if (s.startsWith("vector:") || s.contains("upstash") || s.contains("redis") || s.contains("pgvector")) {
            return "RAG";
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return "WEB";
        }
        return "WEB";
    }

    /** 외부에서 약한 초안(정보 없음 등) 판별에 사용 */

    /** 외부에서 약한 초안(정보 없음 등) 판별에 사용한다. */
    public static boolean looksWeak(String draft) {
        // ✅ GuardContextHolder와 실제로 접목 (ThreadLocal 컨텍스트를 우선 사용)
        GuardContext ctx = GuardContextHolder.get();
        if (ctx == null)
            ctx = GuardContext.defaultContext();
        return looksWeak(draft, ctx);
    }

    /**
     * Jammini Projection v2: 모드 + 점수 기반 약한 초안 판별.
     * - 공격적인 프로필(BRAVE/ZERO_BREAK/WILD)이나 guardLevel=LOW 이면
     * 말투가 다소 조심스러워도 통과시키고, 완전히 비어 있는 경우만 막는다.
     * - fusionScore가 0.90 이상이면 자신감 있는 답변으로 보고 weak 플래그를 내린다.
     * - 그 외에는 기존 정규식 기반 약함 판정을 유지한다.
     */
    public static boolean looksWeak(String draft, GuardContext ctx) {
        if (draft == null) {
            return true;
        }
        if (ctx == null) {
            ctx = GuardContext.defaultContext();
        }

        // 1) [WILD / BRAVE 모드] 거의 막지 않고, 빈 문자열만 거부
        if (ctx.isAggressivePlan() || "WILD".equalsIgnoreCase(ctx.profile())) {
            return draft.trim().isEmpty();
        }

        // 2) [HIGH FUSION SCORE] 0.90 이상이면 신뢰
        if (ctx.fusionScore() >= 0.90) {
            return false;
        }

        // 3) [SUBCULTURE 도메인 감지] 게임/서브컬처 응답은 Balanced 모드에서 완화
        String lower = draft.toLowerCase(java.util.Locale.ROOT);
        boolean isSubculture = lower.matches(
                ".*(원신|genshin|마비카|푸리나|게임|애니|만화|캐릭터|hoyo|fandom|나무위키).*");
        if (isSubculture && "BALANCED".equalsIgnoreCase(ctx.profile())) {
            // 서브컬처 + Balanced 모드 → 내용이 극단적으로 짧지만 않으면 허용
            return draft.trim().length() < 20;
        }

        // 4) [기본 안전 모드] 기존 패턴 검사 유지
        if (INFO_NONE.matcher(draft).find()) {
            return true;
        }
        return looksStructurallyEmpty(draft) || looksNoEvidenceTemplate(draft);
    }

    private int estimateCoverage(String draft, List<EvidenceDoc> topDocs) {
        if (draft == null || draft.isBlank() || topDocs == null || topDocs.isEmpty()) {
            return 0;
        }

        int covered = 0;
        String draftLower = draft.toLowerCase();

        for (EvidenceDoc d : topDocs) {
            if (d == null) {
                continue;
            }

            // [Fix] Use snippet as fallback when title is generic/too short.
            // Guard coverage should not collapse to 0 just because the title is
            // "web"/"rag".
            String basis = d.title();
            if (basis == null
                    || basis.isBlank()
                    || basis.equalsIgnoreCase("web")
                    || basis.equalsIgnoreCase("rag")
                    || basis.length() < 6) {
                basis = d.snippet();
            }
            if (basis == null || basis.isBlank()) {
                continue;
            }

            // 제목/스니펫을 정규화하여 순수 키워드 추출
            java.util.List<String> titleKeywords = KoreanQueryNormalizer.normalize(basis);

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

    /**
     * [PATCH v3.1] 회피성 템플릿 감지 로직 강화
     *
     * 변경 사항:
     * 1. "충분한 증거를 찾지 못했습니다" 문구 → 무조건 true
     * 2. INFO_NONE 패턴이 앞부분(80자 이내)에 등장 → 뒤 길이 무관하게 true
     * 3. 길이 임계값 300 → 400 상향
     *
     * @param draft LLM 응답 초안
     * @return true면 "정보 없음 템플릿", false면 정상 답변
     */
    /**
     * [PATCH v3.1] 회피성 템플릿 감지 로직 강화
     *
     * 변경 사항:
     * 1. "충분한 증거를 찾지 못했습니다" 문구 → 무조건 true
     * 2. INFO_NONE 패턴이 앞부분(80자 이내)에 등장 → 뒤 길이 무관하게 true
     * 3. 길이 임계값 300 → 400 상향
     *
     * @param draft LLM 응답 초안
     * @return true면 "정보 없음 템플릿", false면 정상 답변
     */
    public static boolean looksNoEvidenceTemplate(String draft) {
        if (draft == null || draft.isBlank()) {
            return true;
        }

        String normalized = draft.replaceAll("\\s+", " ").trim();

        // 특정 회피성 문구는 즉시 '정보 없음' 템플릿으로 간주
        if (normalized.contains("충분한 증거를 찾지 못했습니다")) {
            return true;
        }

        boolean matchesTemplate = INFO_NONE.matcher(normalized).find();
        if (!matchesTemplate) {
            return false;
        }

        // 1. 매우 짧고 템플릿이면 거의 정보 없음
        if (normalized.length() < 100) {
            return true;
        }

        // 2. 실질적인 내용(예시/목차/길이 등)이 있는지 체크
        boolean hasSubstance = normalized.contains("예를 들어") ||
                normalized.contains("첫째") ||
                normalized.contains("•") ||
                normalized.length() > 300;

        return !hasSubstance;
    }

    public enum EvidenceStrength {
        NONE, WEAK, MODERATE, STRONG
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
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        // namu.wiki는 테크/루머 출처로 별도 취급 (isRumorFriendlyDomain)
        return /* lower.contains("namu.wiki") || */
        lower.contains("tistory.com")
                || lower.contains("gamedot.org")
                || lower.contains("fandom.com")
                || lower.contains("hoyolab.com")
                || lower.contains("arca.live")
                || lower.contains("inven.co.kr")
                || lower.contains("ruliweb.com")
                || lower.contains("dcinside.com")
                || lower.contains("naver.com");
    }

    /**
     * 루머/커뮤니티 친화 도메인 - 테크 루머/스펙 출처로 인정.
     * EvidenceStrength 계산 시 별도 카운트로 사용된다.
     */
    private boolean isRumorFriendlyDomain(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("namu.wiki")
                || lower.contains("gsmarena.com")
                || lower.contains("xda-developers.com")
                || lower.contains("sammobile.com")
                || lower.contains("91mobiles.com");
    }

    private EvidenceStrength calculateEvidenceStrength(java.util.List<EvidenceDoc> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return EvidenceStrength.NONE;
        }
        long subcultureSources = evidence.stream()
                .filter(e -> isSubcultureDomain(e.id()))
                .count();
        long rumorSources = evidence.stream()
                .filter(e -> isRumorFriendlyDomain(e.id()))
                .count();

        long effectiveSources = Math.max(subcultureSources, rumorSources);

        if (effectiveSources >= 5) {
            return EvidenceStrength.STRONG;
        }
        if (effectiveSources >= 3) {
            return EvidenceStrength.MODERATE;
        }
        if (effectiveSources >= 1) {
            return EvidenceStrength.WEAK;
        }
        return EvidenceStrength.WEAK;
    }

    /**
     * [PATCH v3.1] Evidence 기반 가드 로직 재설계
     *
     * 핵심 변경:
     * 1. coverage 계산 후, coverage < 0.05 && 증거 있음 → 강제 회피 템플릿 플래그
     * 2. 증거 있음 + (회피 템플릿 OR 구조적 빈 답변) → LLM 답변 차단, Evidence List 반환
     * 3. 정상 케이스는 기존 로직 유지
     */
    /**
     * [PATCH v3.1] Evidence 기반 가드 로직 재설계
     *
     * 핵심 변경:
     * 1. coverage 계산 후, coverage < 0.05 && 증거 있음 → 강제 회피 템플릿 플래그
     * 2. 증거 있음 + (회피 템플릿 OR 구조적 빈 답변) → LLM 답변 차단, Evidence List 반환
     * 3. 정상 케이스는 기존 로직 유지
     */
    /**
     * [PATCH v3.1] Evidence 기반 가드 로직 재설계
     *
     * 핵심 변경:
     * 1. coverage 계산 후, coverage < 0.05 && 증거 있음 → 강제 회피 템플릿 플래그
     * 2. 증거 있음 + (회피 템플릿 OR 구조적 빈 답변) → LLM 답변 차단, Evidence List 반환
     * 3. 정상 케이스는 기존 로직 유지
     */
    /**
     * VisionMode 인자를 받는 오버로드.
     *
     * FREE 모드에서는 가드를 완화하여, 증거가 없어도 답변을 허용하되
     * 메모리 저장은 피할 수 있도록 GuardAction.ALLOW_NO_MEMORY 를 사용한다.
     */
    public GuardDecision guardWithEvidence(
            String draft,
            java.util.List<EvidenceDoc> evidence,
            int maxRegens,
            VisionMode visionMode) {
        if (visionMode == VisionMode.FREE) {
            // FREE 모드에서는 가드를 완화
            if (evidence != null && !evidence.isEmpty()) {
                return new GuardDecision(
                        draft, false, false, false, false,
                        1.0, EvidenceStrength.STRONG, DraftQuality.OK,
                        GuardAction.ALLOW_NO_MEMORY, evidence, false);
            }
            // 증거 없어도 답변 허용 (단, 메모리 저장 안 함)
            return new GuardDecision(
                    draft, false, false, false, false,
                    0.0, EvidenceStrength.NONE, DraftQuality.OK,
                    GuardAction.ALLOW_NO_MEMORY, java.util.Collections.emptyList(), false);
        }
        // STRICT/HYBRID는 기존 로직 사용
        return guardWithEvidence(draft, evidence, maxRegens);
    }

    public GuardDecision guardWithEvidence(
            String draft,
            java.util.List<EvidenceDoc> evidence,
            int maxRegens) {
        java.util.List<EvidenceDoc> safeEvidence = (evidence == null) ? java.util.Collections.emptyList() : evidence;
        // GuardProfileProps 주입 누락된 테스트 환경 대비 방어
        GuardProfile profile = (guardProfileProps != null)
                ? guardProfileProps.currentProfile()
                : GuardProfile.PROFILE_MEMORY;
        boolean hasEvidence = !safeEvidence.isEmpty();
        boolean noEvidenceTemplate = looksNoEvidenceTemplate(draft);
        boolean structurallyEmpty = looksStructurallyEmpty(draft);
        // ========== [PATCH] Coverage 계산 ==========
        double coverage = 0.0;
        if (hasEvidence) {
            int covered = estimateCoverage(draft, safeEvidence);
            if (!safeEvidence.isEmpty()) {
                coverage = (double) covered / (double) safeEvidence.size();
            }
        }
        EvidenceStrength strength = calculateEvidenceStrength(safeEvidence);

        // plan-driven min citations (GuardContext override)
        GuardContext ctx = GuardContextHolder.get();
        Integer minCitations = (ctx != null ? ctx.getMinCitations() : null);
        if (minCitations != null && minCitations > 0 && safeEvidence.size() < minCitations) {
            TraceStore.put("guard.minCitations.required", minCitations);
            TraceStore.put("guard.minCitations.actual", safeEvidence.size());

            String msg = "근거 출처가 부족합니다 (필요: " + minCitations + ", 확보: " + safeEvidence.size() + ").";
            String degraded = degradeToEvidenceList(safeEvidence);
            return new GuardDecision(
                    msg + "\n\n" + degraded,
                    false, // regenerated
                    true, // degradedToEvidence
                    false, // shouldPersist
                    false, // shouldReinforceMemory
                    coverage,
                    strength,
                    DraftQuality.WEAK,
                    GuardAction.BLOCK,
                    safeEvidence,
                    false);
        }
        DraftQuality quality = (noEvidenceTemplate || structurallyEmpty)
                ? DraftQuality.WEAK
                : DraftQuality.OK;

        // ========== [핵심 수정 1] Evidence 무시 감지 (모드 기반 완화) ==========
        // 증거는 충분한데(Strength != NONE), 답변이 증거를 전혀 반영하지 못함(Coverage < 0.05)
        // 기본적으로는 회피 템플릿으로 보지만,
        // RUMOR_FRIENDLY / NORMAL 모드 + STRONG evidence 인 경우에는 차단하지 않는다.
        GuardMode mode = resolveMode(draft, safeEvidence);
        // [FUTURE_TECH FIX] Treat rumor-friendly mode as FutureTech: allow rumor
        // summary but NEVER persist into memory
        boolean futureTech = FutureTechDetector.isFutureTechQuery(draft);
        boolean strongEvidence = (strength == EvidenceStrength.STRONG || safeEvidence.size() >= 3);
        if (hasEvidence && coverage < 0.05 && strength != EvidenceStrength.NONE) {
            if (strongEvidence && mode != GuardMode.STRICT) {
                log.info("[Guard] Low coverage({}) but evidence STRONG({} docs) & mode={} – not forcing fallback.",
                        coverage, safeEvidence.size(), mode);
                // noEvidenceTemplate 플래그를 건드리지 않고 그대로 진행
            } else {
                log.warn(
                        "[Guard] Evidence ignored: {} docs exist (strength={}) but coverage={}. Forcing fallback.",
                        safeEvidence.size(), strength, coverage);
                noEvidenceTemplate = true; // ← 강제 플래그
            }
        }

        // ========== [CRITICAL] 모순 감지: 증거 있음 + "정보 없음"/빈 답변 ==========
        if (hasEvidence && (noEvidenceTemplate || structurallyEmpty)) {
            // mode is already declared above at line 791
            log.warn(
                    "[Guard] Contradiction detected: {} evidence docs exist but draft looks 'No Info' (mode={}).",
                    safeEvidence.size(), mode);

            // [FUTURE_TECH FIX] Evidence exists but the draft tries to escape with 'no
            // info' -> force evidence-only rewrite
            if (futureTech) {
                String degraded = degradeToEvidenceList(safeEvidence);
                return new GuardDecision(
                        degraded,
                        false, // regenerated
                        true, // degradedToEvidence
                        false, // shouldPersist
                        false, // shouldReinforceMemory
                        coverage,
                        strength,
                        DraftQuality.WEAK,
                        GuardAction.REWRITE,
                        safeEvidence,
                        false // escalated
                );
            }

            if (mode == GuardMode.STRICT) {
                // STRICT 모드: 증거만을 기반으로 재생성하도록 ChatService에 위임
                String degraded = degradeToEvidenceList(safeEvidence);
                return new GuardDecision(
                        degraded,
                        false, // regenerated
                        true, // degradedToEvidence
                        false, // shouldPersist
                        false, // shouldReinforceMemory
                        coverage,
                        strength,
                        DraftQuality.WEAK,
                        GuardAction.REWRITE,
                        safeEvidence,
                        false // escalated
                );
            } else {
                // RUMOR_FRIENDLY / NORMAL 모드: 초안을 루머 기반 답변으로 허용하되 메모리 강화는 막는다.
                return new GuardDecision(
                        draft,
                        false, // regenerated
                        false, // degradedToEvidence
                        false, // shouldPersist
                        false, // shouldReinforceMemory
                        coverage,
                        strength,
                        DraftQuality.WEAK,
                        GuardAction.ALLOW_NO_MEMORY,
                        safeEvidence,
                        false // escalated
                );
            }
        }

        // 증거는 있지만 초안이 구조적으로 빈약한 경우: 증거 목록으로 degrade
        if (hasEvidence && structurallyEmpty) {
            log.warn("[Guard] Evidence exists ({}) but draft is structurally empty. Degrading to evidence list.",
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
        // ========== 정상 케이스: 증거 있고 답변도 정상 ==========
        if (hasEvidence) {
            // [FUTURE_TECH FIX] Never persist rumor/unreleased-product answers into memory
            // (avoid contamination)
            if (futureTech) {
                return new GuardDecision(
                        draft,
                        false,
                        false,
                        false, // shouldPersist
                        false, // shouldReinforceMemory
                        coverage,
                        strength,
                        quality,
                        GuardAction.ALLOW_NO_MEMORY,
                        safeEvidence,
                        false);
            }

            switch (profile) {
                case PROFILE_FREE -> {
                    // Vision 2: 자유 모드 - 답변은 통과시키되 메모리는 강화하지 않음
                    return new GuardDecision(
                            draft,
                            false,
                            false,
                            false, // shouldPersist
                            false, // shouldReinforceMemory
                            coverage,
                            strength,
                            quality,
                            GuardAction.ALLOW_NO_MEMORY,
                            safeEvidence,
                            false);
                }
                case PROFILE_MEMORY -> {
                    // Vision 1: 엄격 모드 - 근거가 있는 강한 답변만 메모리 강화
                    boolean reinforce = (quality == DraftQuality.OK);
                    return new GuardDecision(
                            draft,
                            false,
                            false,
                            true, // shouldPersist
                            reinforce,
                            coverage,
                            strength,
                            quality,
                            GuardAction.ALLOW,
                            safeEvidence,
                            false);
                }
                case PROFILE_HEX -> {
                    // Vision 3: 중재 모드 - coverage/품질에 따라 메모리 강화 여부 결정
                    boolean reinforce = (quality == DraftQuality.OK && coverage >= 0.5);
                    return new GuardDecision(
                            draft,
                            false,
                            false,
                            reinforce,
                            reinforce,
                            coverage,
                            strength,
                            quality,
                            GuardAction.ALLOW,
                            safeEvidence,
                            false);
                }
                default -> {
                    // 기존 프로파일(SAFE/STRICT/NORMAL/SUBCULTURE 등)은 보수적으로 처리
                    boolean persist = profile.isMemoryReinforcementEnabled();
                    boolean reinforce = persist && quality == DraftQuality.OK;
                    GuardAction action = persist ? GuardAction.ALLOW : GuardAction.ALLOW_NO_MEMORY;
                    return new GuardDecision(
                            draft,
                            false,
                            false,
                            persist,
                            reinforce,
                            coverage,
                            strength,
                            quality,
                            action,
                            safeEvidence,
                            false);
                }
            }
        }
        // ========== 증거 없음 + 정보 없음 템플릿 (정상적 "정보 부족" 응답) ==========
        if (noEvidenceTemplate) {
            return new GuardDecision(
                    draft,
                    false,
                    false,
                    false,
                    false,
                    0.0,
                    EvidenceStrength.NONE,
                    DraftQuality.WEAK,
                    GuardAction.ALLOW_NO_MEMORY,
                    safeEvidence,
                    false);
        }
        // ========== 증거 없는데 답변 시도 (환각 위험) → 차단 또는 degrade ==========
        String degraded = degradeToEvidenceList(safeEvidence);
        return new GuardDecision(
                (degraded == null || degraded.isBlank()) ? draft : degraded,
                false,
                true,
                false,
                false,
                0.0,
                EvidenceStrength.NONE,
                DraftQuality.WEAK,
                GuardAction.BLOCK,
                safeEvidence,
                false);
    }

    private enum GuardMode {
        STRICT,
        NORMAL,
        RUMOR_FRIENDLY
    }

    private GuardMode resolveMode(String draft, java.util.List<EvidenceDoc> safeEvidence) {
        StringBuilder sb = new StringBuilder();
        if (draft != null) {
            sb.append(draft.toLowerCase());
        }
        if (safeEvidence != null) {
            for (EvidenceDoc doc : safeEvidence) {
                if (doc.title() != null) {
                    sb.append(' ').append(doc.title().toLowerCase());
                }
                if (doc.snippet() != null) {
                    sb.append(' ').append(doc.snippet().toLowerCase());
                }
            }
        }
        String q = sb.toString();
        // [FUTURE_TECH FIX] Unreleased/next-gen device queries -> rumor-friendly mode
        // (no memory persistence)
        boolean isDeviceRumor = FutureTechDetector.isFutureTechQuery(q);
        boolean isSensitive = q.contains("주식") || q.contains("투자")
                || q.contains("대출") || q.contains("의료")
                || q.contains("선거");
        if (isSensitive) {
            return GuardMode.STRICT;
        }
        if (isDeviceRumor) {
            return GuardMode.RUMOR_FRIENDLY;
        }
        return GuardMode.NORMAL;
    }

    private boolean isCommunitySource(String url) {
        if (url == null)
            return false;
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
