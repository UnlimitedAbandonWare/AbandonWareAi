package com.example.lms.service.rag.pre;

import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.genshin.GenshinElementLexicon;   // + NEW
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guardrail 기반 전처리 구현체.
 * - 간단 오타/별칭 정규화
 * - 디버그 태그/제어문자/검색연산자 제거
 * - 공손어/불필요 꼬리표 제거
 * - 여분 공백/기호 정리 및 길이 제한
 * - 도메인/의도 감지
 * - (원신 질의 시) 원소 허용/비선호 정책 주입
 */
@Component("guardrailQueryPreprocessor")
@Primary // 다중 구현 시 기본값으로 사용
public class GuardrailQueryPreprocessor implements QueryContextPreprocessor {

    private final GameDomainDetector domainDetector;
    private final GenshinElementLexicon lexicon;        // + NEW
    public GuardrailQueryPreprocessor(GameDomainDetector detector,
                                      GenshinElementLexicon lexicon) { //  NEW
        this.domainDetector = detector;
        this.lexicon = lexicon;
    }
    // ── 간단 오타 사전(필요 시 Settings로 이관)
    private static final Map<String, String> TYPO = Map.of(
            "후리나", "푸리나",
            "푸르나", "푸리나"
    );

    // ── 보호(고유명사)는 교정 대상에서 제외
    private static final Set<String> PROTECT = Set.of(
            "푸리나", "호요버스", "HOYOVERSE", "Genshin", "원신",
            "Arlecchino", "아를레키노", "Escoffier", "에스코피에"
    );

    // ── 과한 공손어/불필요 접미(끝토막만 제거)
    private static final Pattern HONORIFICS =
            Pattern.compile("(님|해주세요|해 주세요|알려줘|정리|요약)$");

    /**
     * 원본 쿼리 문자열을 받아 정제/정규화하여 반환합니다.
     * @param original 사용자가 입력한 원본 쿼리
     * @return 정제 및 정규화가 완료된 쿼리 문자열
     */
    @Override
    public String enrich(String original) {
        if (!StringUtils.hasText(original)) {
            return "";
        }

        String s = original.trim();

        // 1) 디버그 태그/제어문자/검색 연산자 제거
        s = s.replaceAll("^\\[(?:mode|debug)=[^\\]]+\\]\\s*", "")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("(?i)\\bsite:[^\\s]+", "");

        // 2) 공손어/불필요 꼬리표 축소
        s = HONORIFICS.matcher(s).replaceAll("").trim();

        // 3) 토큰 단위 오타 교정(보호어는 그대로 유지)
        StringBuilder out = new StringBuilder();
        for (String tok : s.split("\\s+")) {
            String t = tok;
            if (!containsIgnoreCase(PROTECT, t)) {
                t = TYPO.getOrDefault(t, t);
            }
            out.append(t).append(' ');
        }
        s = out.toString().trim();

        // 4) 여분 공백/기호 정리
        s = s.replaceAll("\\s{2,}", " ")
                .replaceAll("[\"“”'`]+", "")
                .replaceAll("\\s*\\?+$", "")
                .trim();

        // 5) 길이 제한(QoS)
        if (s.length() > 120) {
            s = s.substring(0, 120);
        }

        // 6) 매우 짧은 단어가 아니면 소문자 통일(검색 일관성)
        return s.length() <= 2 ? s : s.toLowerCase(Locale.ROOT);
    }

    // ── 대소문자 무시 포함 여부 체크
    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) return false;
        for (String p : set) {
            if (p.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // ── 도메인 감지(원신/일반 등)
    @Override
    public String detectDomain(String q) {
        return domainDetector.detect(q);
    }

    // ── 의도 추정: 추천/일반
    @Override
    public String inferIntent(String q) {
        if (!StringUtils.hasText(q)) return "GENERAL";
        String s = q.toLowerCase(Locale.ROOT);
        // PAIRING(궁합/어울림/상성/조합/파티/시너지) 우선 분류
        if (s.matches(".*(잘\\s*어울리|어울리(?:는|다)?|궁합|상성|시너지|조합|파티).*")) {
            return "PAIRING";
        }
        if (s.matches(".*(추천|픽|티어|메타).*")) {
            return "RECOMMENDATION";
        }
        return "GENERAL";
    }

    // ── 허용 원소(원신) – 에스코피에 맥락 보수 정책
    @Override
    public Set<String> allowedElements(String q) {
        if (!"GENSHIN".equalsIgnoreCase(detectDomain(q))) return Set.of();
        return lexicon.policyForQuery(q).allowed();      //  Lexicon 기반
    }

    // ── 비선호 원소(원신) – Pyro/Dendro 보수 감점
    @Override
    public Set<String> discouragedElements(String q) {
        if (!"GENSHIN".equalsIgnoreCase(detectDomain(q))) return Set.of();
        return lexicon.policyForQuery(q).discouraged();  //  Lexicon 기반
    }
}