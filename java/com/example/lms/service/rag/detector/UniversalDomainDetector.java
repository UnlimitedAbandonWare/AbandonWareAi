package com.example.lms.service.rag.detector;

import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * UniversalDomainDetector
 *
 * <p>질의 문자열 + LLM 기반 분석 결과(DisambiguationResult)를 조합하여
 * 최종 도메인 코드를 추정하는 범용 도메인 탐지기입니다.
 *
 * <p>기존 {@link GameDomainDetector} 는 문자열만 보고
 * "GENSHIN" / "EDUCATION" / "GENERAL" 을 반환했지만,
 * 이 구현은 GENSHIN, IT_KNOWLEDGE, TECH_DEVICE, LIVING_THING 등
 * 보다 폭넓은 도메인 코드를 사용할 수 있습니다.
 */
@Component
public class UniversalDomainDetector {

    private final UniversalContextLexicon lexicon;

    public UniversalDomainDetector(UniversalContextLexicon lexicon) {
        this.lexicon = lexicon;
    }

    /**
     * Lexicon + DisambiguationResult 를 활용한 도메인 추론.
     *
     * @param query  사용자 원문 질의
     * @param result LLM 기반 모호성 해소 결과 (없을 수 있음)
     * @return 도메인 코드 (예: GENSHIN, IT_KNOWLEDGE, TECH_DEVICE, GENERAL …)
     */
    public String detect(String query, DisambiguationResult result) {
        // ── 1단계: Lexicon 기반 Attribute Fast-Path ────────────────────────────────
        String attr = lexicon.inferAttribute(query);
        if (attr != null) {
            String upper = attr.toUpperCase(Locale.ROOT);

            if (upper.startsWith("GENSHIN_")) {
                return "GENSHIN";
            }
            if (upper.startsWith("TECH_")) {
                return "IT_KNOWLEDGE";
            }
            if (upper.startsWith("ELEC_")) {
                return "TECH_DEVICE";
            }
            if (upper.startsWith("COOKING_")) {
                return "COOKING";
            }
        }

        // ── 2단계: DisambiguationResult 기반 Slow-Path ──────────────────────────
        if (result != null && StringUtils.hasText(result.getDetectedCategory())) {
            String cat = result.getDetectedCategory().toUpperCase(Locale.ROOT);

            switch (cat) {
                case "SMARTPHONE":
                case "LAPTOP":
                case "TABLET":
                case "ELECTRONICS":
                    return "TECH_DEVICE";

                case "ANIMAL":
                case "PET":
                    return "LIVING_THING";

                case "DEV_TOPIC":
                case "PROGRAMMING":
                case "IT":
                case "SOFTWARE":
                    return "IT_KNOWLEDGE";

                case "GAME":
                case "GAMING":
                    return "GAME";

                case "EDUCATION":
                case "COURSE":
                case "TRAINING":
                    return "EDUCATION";

                default:
                    // fall-through to GENERAL
                    break;
            }
        }

        // ── 3단계: 추가 규칙이 없으면 GENERAL 로 분류 ─────────────────────────────
        return "GENERAL";
    }

    /**
     * 레거시 호환용: DisambiguationResult 없이도 호출 가능한 버전.
     */
    public String detect(String query) {
        return detect(query, null);
    }
}
