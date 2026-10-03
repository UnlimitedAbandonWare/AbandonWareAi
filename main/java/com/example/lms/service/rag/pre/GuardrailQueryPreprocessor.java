// src/main/java/com/example/lms/service/rag/pre/GuardrailQueryPreprocessor.java
package com.example.lms.service.rag.pre;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.trace.SafeRedactor;
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
            "푸르나", "푸리나",
            // [PATCH] ERRORS_A 실사례 타이포 보정: 잼미나이 → 제미나이/Gemini
            "잼민이", "제미나이",
            "잼미나이", "제미나이",
            "젬미나이", "제미나이",
            "제미니", "제미나이"
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
        String cognitiveSkip = cognitiveSkipReason();
        if (cognitiveSkip != null) {
            traceCognitiveSkipped("enrich", original, cognitiveSkip);
        } else try {
            var cs = cognitiveStateExtractor.extract(original);
            if (cs != null && cs.executionMode() == CognitiveState.ExecutionMode.VECTOR_SEARCH) {
                // 원문에서 제어문자 제거 및 앞뒤 공백만 정리한다.
                return original.replaceAll("\\p{Cntrl}+", " ").trim();
            }
        } catch (Exception stateEx) {
            String errorType = SafeRedactor.traceLabelOrFallback(stateEx.getClass().getSimpleName(), "unknown");
            TraceStore.put("query.guardrail.suppressed.stage", "cognitiveState");
            TraceStore.put("query.guardrail.suppressed.errorType", errorType);
            TraceStore.put("query.guardrail.suppressed.cognitiveState", true);
            TraceStore.put("query.guardrail.suppressed.cognitiveState.errorType", errorType);
            traceGuardrailFailure("cognitive_state", original, stateEx);
            // 실패 시 기존 로직을 계속 진행
        }

        s = s.replaceAll("^\\[(?:mode|debug)=[^\\]]+\\]\\s*", "")
                .replaceAll("\\p{Cntrl}+", " ")
                .replaceAll("(?i)\\bsite:[^\\s]+", "");

        s = HONORIFICS.matcher(s).replaceAll("").trim();

        // [PATCH] 공백 없이 붙는 타이포(예: "잼미나이api")도 커버하기 위해
        // 토큰 치환 이전에 substring 레벨 치환을 한 번 수행한다.
        try {
            for (Map.Entry<String, String> e : TYPO.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && s.contains(e.getKey())) {
                    s = s.replace(e.getKey(), e.getValue());
                }
            }
        } catch (Exception typoEx) {
            String errorType = SafeRedactor.traceLabelOrFallback(typoEx.getClass().getSimpleName(), "unknown");
            TraceStore.put("query.guardrail.suppressed.stage", "typoRewrite");
            TraceStore.put("query.guardrail.suppressed.errorType", errorType);
            TraceStore.put("query.guardrail.suppressed.typoRewrite", true);
            TraceStore.put("query.guardrail.suppressed.typoRewrite.errorType", errorType);
            traceGuardrailFailure("typo_rewrite", s, typoEx);
        }

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

        // [PATCH] 제미나이 → Gemini 동의어로 공식 문서 리콜 향상
        String lowerForCheck = s.toLowerCase(Locale.ROOT);
        if (s.contains("제미나이") && !lowerForCheck.contains("gemini")) {
            s = s + " gemini";
        }

        // Preserve appended synonym when truncating.
        final int MAX_LEN = 120;
        final String SUFFIX = " gemini";
        if (s.length() > MAX_LEN) {
            if (s.endsWith(SUFFIX) && MAX_LEN > SUFFIX.length()) {
                s = s.substring(0, MAX_LEN - SUFFIX.length()) + SUFFIX;
            } else {
                s = s.substring(0, MAX_LEN);
            }
        }

        return s.length() <= 2 ? s : s.toLowerCase(Locale.ROOT);
    }

    public Optional<CognitiveState> extractCognitiveState(String q) {
        String cognitiveSkip = cognitiveSkipReason();
        if (cognitiveSkip != null) {
            traceCognitiveSkipped("extractCognitiveState", q, cognitiveSkip);
            return Optional.empty();
        }
        try { return Optional.ofNullable(cognitiveStateExtractor.extract(q)); }
        catch (Exception stateEx) {
            String errorType = SafeRedactor.traceLabelOrFallback(stateEx.getClass().getSimpleName(), "unknown");
            TraceStore.put("query.guardrail.suppressed.stage", "extractCognitiveState");
            TraceStore.put("query.guardrail.suppressed.errorType", errorType);
            TraceStore.put("query.guardrail.suppressed.extractCognitiveState", true);
            TraceStore.put("query.guardrail.suppressed.extractCognitiveState.errorType", errorType);
            traceGuardrailFailure("extract_cognitive_state", q, stateEx);
            return Optional.empty();
        }
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

    /**
     * 인지 상태 추출(LLM 호출)을 이번 요청에서 생략할 이유. null 이면 정상 실행.
     * 저사양/고부하 로컬 레인의 다이나믹 fail-soft fast path:
     * - prior_failure: 같은 요청 안에서 extract 가 이미 실패/타임아웃됨 — 재호출
     *   시 같은 지연을 다시 지불하므로 건너뛰고 기존 로컬 파이프라인(타이포·정규화)만 수행.
     * - cheap_mode / bypass_mode: GuardContext가 경량 검색/바이패스 레인을 지정 — 모드
     *   분류용 무거운 추출을 생략하고 원문 전진. 추출 생략은 기존 catch 경로와 동일하게
     *   비벡터 모드로 해석되어 안전하다.
     */
    private static String cognitiveSkipReason() {
        if (Boolean.TRUE.equals(TraceStore.get("query.guardrail.suppressed.cognitiveState"))) {
            return "prior_failure";
        }
        GuardContext ctx = GuardContextHolder.get();
        if (ctx != null && ctx.isCheapSearchMode()) {
            return "cheap_mode";
        }
        if (ctx != null && ctx.isBypassMode()) {
            return "bypass_mode";
        }
        return null;
    }

    private static void traceCognitiveSkipped(String stage, String query, String reason) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.inc("query.guardrail.cognitiveSkipped.count");
        TraceStore.put("query.guardrail.cognitiveSkipped", true);
        TraceStore.put("query.guardrail.cognitiveSkipped.stage", safeStage);
        TraceStore.put("query.guardrail.cognitiveSkipped.reason", safeReason);
        TraceStore.put("query.guardrail.cognitiveSkipped.queryHash", SafeRedactor.hash12(query));
    }

    private static void traceGuardrailFailure(String stage, String query, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeReason = error == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
        TraceStore.inc("query.guardrail." + safeStage + ".failed");
        TraceStore.put("query.guardrail." + safeStage + ".failureReason", safeReason);
        TraceStore.put("query.guardrail." + safeStage + ".queryHash", SafeRedactor.hash12(query));
        TraceStore.put("query.guardrail." + safeStage + ".queryLength", query == null ? 0 : query.length());
        TraceStore.putIfAbsent("query.guardrail.failed", true);
        TraceStore.putIfAbsent("query.guardrail.failureStage", safeStage);
        TraceStore.putIfAbsent("query.guardrail.failureReason", safeReason);
        TraceStore.putIfAbsent("query.guardrail.queryHash", SafeRedactor.hash12(query));
        TraceStore.putIfAbsent("query.guardrail.queryLength", query == null ? 0 : query.length());
    }
}
