package com.example.lms.service.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Soft citation gate.
 * <p>
 * Previous versions used this gate to hard-block answers when the number of
 * evidence items was below a threshold. This caused otherwise useful answers
 * to be discarded when citations were missing or the retriever returned fewer
 * snippets than expected.
 * <p>
 * The new behaviour is:
 * - never block on missing/insufficient sources
 * - log a warning so operators can monitor coverage
 * This keeps the method signature intact while switching the policy to a
 * "soft pass" as required by the Availability First principle.
 */
public class CitationGate {

    private static final Logger log = LoggerFactory.getLogger(CitationGate.class);

    public boolean ok(List<String> sources, int minCount, double allowlistRatio) {
        if (sources == null || sources.isEmpty()) {
            log.warn("[CitationGate] No sources provided (minCount={}). Allowing answer (soft pass).", minCount);
            return true;
        }
        int n = sources.size();
        if (n < minCount) {
            log.debug("[CitationGate] Source count {} is below minCount {}. Allowing answer (soft pass).", n, minCount);
        }
        // allowlistRatio is kept for future use / compatibility
        return true;
    }

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(?:W\\d+|V\\d+|D\\d+|\\d+)]");

    // 한국어 이름 + 호칭 패턴 (예: 홍길동씨, 김철수 교수)
    private static final Pattern NAME_PATTERN = Pattern.compile("([가-힣]{2,4})(?:씨|님|교수|박사|선생|원장|의원|대표|사장)?",
            Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 주어진 텍스트 안에 URL, [W1]/[V2]/[D3] 스타일 인용 마커,
     * 또는 "출처:" / "source:" 텍스트가 있는지를 느슨하게 판단한다.
     */
    public boolean hasCitation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        if (lower.contains("http://") || lower.contains("https://")) {
            return true;
        }
        if (lower.contains("출처:") || lower.contains("source:")) {
            return true;
        }
        return CITATION_PATTERN.matcher(text).find();
    }

    /**
     * Memory reinforcement 단계에서 사용할 수 있는 헬퍼.
     * - 비 Assistant 발화는 그대로 허용
     * - Assistant/AI_GENERATED 발화는 hasCitation(text)를 사용해 느슨하게 검사
     * 실제 점수 감쇠 로직은 MemoryReinforcementService 에서 담당한다.
     */
    public boolean allowForReinforcement(String sourceTag, String snippet, double score) {
        if (sourceTag == null) {
            return true;
        }
        if (!"ASSISTANT".equals(sourceTag) && !"AI_GENERATED".equals(sourceTag)) {
            return true;
        }
        return hasCitation(snippet);
    }

    /**
     * 답변에 인용이 전혀 없고, 상위 레이어에서 URL 리스트를 알고 있을 때
     * 가장 높은 우선순위 URL 하나를 꼬리표로 붙여 준다.
     * - 예: "참고 자료: [[https://...]]"
     */
    public String validateAndFix(String answer, List<String> urls) {
        if (answer == null) {
            answer = "";
        }
        if (hasCitation(answer)) {
            return answer;
        }
        if (urls == null || urls.isEmpty()) {
            return answer;
        }
        String top = urls.get(0);
        if (top == null || top.isBlank()) {
            return answer;
        }
        String trimmed = answer.trim();
        StringBuilder sb = new StringBuilder(trimmed);
        if (!trimmed.endsWith("\n")) {
            sb.append("\n\n");
        } else {
            sb.append("\n");
        }
        sb.append("참고 자료: [[")
                .append(top)
                .append("]]");
        return sb.toString();
    }

    /**
     * Mode-aware citation check. This is deliberately simple and only looks at
     * the number of citations and the GuardContext mode. It is intended for
     * callers that already have their own gating logic and just need a quick
     * "enough citations?" helper.
     *
     * SAFE : 최소 3개
     * BRAVE : 최소 2개
     * ZERO_BREAK : 최소 1개
     * RULE_BREAK : 최소 1개
     */
    public boolean hasEnoughCitations(java.util.List<?> citations, GuardContext ctx) {
        int min = 3; // SAFE 기본
        if (ctx != null && ctx.getMode() != null) {
            String mode = ctx.getMode();
            switch (mode) {
                case "BRAVE" -> min = 2;
                case "ZERO_BREAK", "RULE_BREAK" -> min = 1;
                default -> min = 3;
            }
        }
        return citations != null && citations.size() >= min;
    }

    /**
     * 간단한 한글 이름 + 호칭 패턴을 이용해 질문에서 핵심 엔티티를 추출한다.
     */
    private java.util.List<String> extractKeyEntities(String question) {
        java.util.List<String> entities = new java.util.ArrayList<>();
        if (question == null) {
            return entities;
        }
        java.util.regex.Matcher matcher = NAME_PATTERN.matcher(question);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && name.length() >= 2) {
                entities.add(name);
            }
        }
        return entities;
    }

    /**
     * 질문/답변에 등장하는 인물명이 근거 텍스트에도 등장하는지 확인한다.
     * evidenceTexts 에 하나도 등장하지 않으면 환각 가능성이 높다고 보고 false 를 반환한다.
     */
    public boolean hasEntityAlignment(String question, String answer, java.util.List<String> evidenceTexts) {
        java.util.List<String> entities = extractKeyEntities(question);
        if (entities.isEmpty() || evidenceTexts == null || evidenceTexts.isEmpty()) {
            return true;
        }

        StringBuilder sb = new StringBuilder();
        for (String t : evidenceTexts) {
            if (t != null) {
                sb.append(t).append(' ');
            }
        }
        String allEvidence = sb.toString();

        for (String ent : entities) {
            if (answer != null && answer.contains(ent) && !allEvidence.contains(ent)) {
                log.warn(
                        "[CitationGate] Entity '{}' appears in question/answer but not in evidence. Potential hallucination.",
                        ent);
                return false;
            }
        }
        return true;
    }

}
