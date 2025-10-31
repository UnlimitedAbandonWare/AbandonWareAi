// src/main/java/com/example/lms/service/rag/pre/GuardrailQueryPreprocessor.java
package com.example.lms.service.rag.pre;

import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;




@Component("guardrailQueryPreprocessor")
public class GuardrailQueryPreprocessor implements QueryContextPreprocessor {

    private final GameDomainDetector domainDetector;
    private final KnowledgeBaseService knowledgeBase;
    private final SubjectResolver subjectResolver;
    private final CognitiveStateExtractor cognitiveStateExtractor;

    public GuardrailQueryPreprocessor(GameDomainDetector detector,
                                      KnowledgeBaseService knowledgeBase,
                                      SubjectResolver subjectResolver,
                                      CognitiveStateExtractor cognitiveStateExtractor) {
        this.domainDetector = detector;
        this.knowledgeBase = knowledgeBase;
        this.subjectResolver = subjectResolver;
        this.cognitiveStateExtractor = cognitiveStateExtractor;
    }

    private static final Map<String, String> TYPO = Map.of(
            "후리나", "푸리나",
            "푸르나", "푸리나"
    );

    private static final Set<String> PROTECT = Set.of(
            "푸리나", "호요버스", "HOYOVERSE", "Genshin", "원신",
            "Arlecchino", "아를레키노", "Escoffier", "에스코피에"
    );

    private static final Pattern HONORIFICS =
            Pattern.compile("(님|해주세요|해 주세요|알려줘|정리|요약)$");

    @Override
    public String enrich(String original) {
        if (!StringUtils.hasText(original)) return "";
        String s = original.trim();

        // 🔁 조건부 파이프라인: 교육 키워드 감지 시 벡터 검색 모드로 전환
        // CognitiveStateExtractor를 통해 ExecutionMode를 조회한다.  벡터 검색 모드에서는
        // 추가적인 전처리를 수행하지 않고 원문을 그대로 반환하여 쿼리 임베딩을 위한
        // 텍스트가 손상되지 않도록 한다.
        try {
            var cs = cognitiveStateExtractor.extract(original);
            if (cs != null && cs.executionMode() == CognitiveState.ExecutionMode.VECTOR_SEARCH) {
                // 원문에서 제어문자 제거 및 앞뒤 공백만 정리한다.
                return original.replaceAll("\\p{Cntrl}+", " ").trim();
            }
        } catch (Exception ignore) {
            // 실패 시 기존 로직을 계속 진행
        }

        s = s.replaceAll("^\\[(?:mode|debug)=[^\\]]+\\]\\s*", "")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("(?i)\\bsite:[^\\s]+", "");

        s = HONORIFICS.matcher(s).replaceAll("").trim();

        StringBuilder out = new StringBuilder();
        for (String tok : s.split("\\s+")) {
            String t = tok;
            if (!containsIgnoreCase(PROTECT, t)) {
                t = TYPO.getOrDefault(t, t);
            }
            out.append(t).append(' ');
        }
        s = out.toString().trim();

        s = s.replaceAll("\\s{2,}", " ")
                .replaceAll("[\"“”'`]+", "")
                .replaceAll("\\s*\\?+$", "")
                .trim();

        if (s.length() > 120) s = s.substring(0, 120);

        return s.length() <= 2 ? s : s.toLowerCase(Locale.ROOT);
    }

    public Optional<CognitiveState> extractCognitiveState(String q) {
        try { return Optional.ofNullable(cognitiveStateExtractor.extract(q)); }
        catch (Exception e) { return Optional.empty(); }
    }

    public boolean isFollowUpLike(String q) {
        if (!StringUtils.hasText(q)) return false;
        String s = q.trim();
        return s.matches("(?i)^(더\\s*자세히|그건\\?|그건요|그리고\\?|추가로|더 알려줘|detail|more).*");
    }

    public String enrichWithAnchor(String original, String lastSubject) {
        String e = enrich(original);
        if (!StringUtils.hasText(lastSubject)) return e;
        if (isFollowUpLike(original) && !e.contains(lastSubject)) {
            return lastSubject + " " + e;
        }
        return e;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) return false;
        for (String p : set) if (p.equalsIgnoreCase(value)) return true;
        return false;
    }

    @Override public String detectDomain(String q) { return domainDetector.detect(q); }

    @Override
    public String inferIntent(String q) {
        if (!StringUtils.hasText(q)) return "GENERAL";
        String s = q.toLowerCase(Locale.ROOT);
        if (s.matches(".*(잘\\s*어울리|어울리(?:는|다)?|궁합|상성|시너지|조합|파티).*")) return "PAIRING";
        if (s.matches(".*(추천|픽|티어|메타).*")) return "RECOMMENDATION";
        return "GENERAL";
    }

    public String inferVerbosityHint(String q) {
        if (!StringUtils.hasText(q)) return null;
        String s = q.toLowerCase(Locale.ROOT);
        if (s.matches(".*(아주\\s*자세|논문급|ultra).*")) return "ultra";
        if (s.matches(".*(상세히|자세히|깊게|deep|원리부터).*")) return "deep";
        return null;
    }

    @Override
    public Map<String, Set<String>> getInteractionRules(String q) {
        String domain = detectDomain(q);
        if (!StringUtils.hasText(domain)) return Map.of();
        String subject = subjectResolver.resolve(q, domain).orElse(null);
        if (!StringUtils.hasText(subject)) return Map.of();
        return knowledgeBase.getAllRelationships(domain, subject);
    }
}