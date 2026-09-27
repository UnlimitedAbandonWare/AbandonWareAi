package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;



/**
 * Simple decision engine for determining if and how to execute a web search.
 * A more sophisticated implementation would incorporate cognitive state
 * analysis, LLM self-reflection and risk estimation.  For the purposes
 * of this upgrade, we implement a deterministic policy based on the
 * requested mode and basic heuristics on the query string.
 */
public class SearchDecisionService {

    private static final Pattern SELF_CONTAINED_ARITHMETIC = Pattern.compile(
            "^[+-]?\\d{1,12}(?:\\.\\d{1,12})?\\s*(?:더하기|빼기|곱하기|나누기|[+*/x×÷−-])\\s*"
                    + "[+-]?\\d{1,12}(?:\\.\\d{1,12})?\\s*(?:은|는)?\\s*"
                    + "(?:(?:의\\s*)?(?:답|값|결과)(?:을|은|는|이)?\\s*)?"
                    + "(?:숫자\\s*(?:한\\s*개)?\\s*(?:로)?\\s*(?:만)?\\s*)?"
                    + "(?:(?:알려|답해|계산해)\\s*줘|(?:얼마|몇)(?:야|이야|인가요)?|=)?\\s*[?!.]*$");

    private static final Pattern ENGLISH_RECENCY_INTENT = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}\\p{Pc}])"
                    + "(?:latest|recent|update|release|news|current|today)"
                    + "(?![\\p{L}\\p{N}\\p{M}\\p{Pc}])");

    private static final Pattern LOCAL_UPDATE_COMMAND = Pattern.compile(
            "^\\s*(?:please\\s+)?update\\s+(?:"
                    + "(?:my|your)\\s+(?:profile|account|settings)\\b"
                    + "|(?:the\\s+)?(?:profile|account|settings)"
                    + "(?=\\s*(?:[.!?]|please|now)?\\s*$)"
                    + "|local\\s+(?:state|session|configuration|config|runtime)\\b)");

    private static final Pattern POSSESSIVE_UPDATE_CONTEXT = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}\\p{Pc}])"
                    + "update[\\t ]+(?:my|your)[\\t ]+(?:profile|account|settings)"
                    + "(?![\\p{L}\\p{N}\\p{M}\\p{Pc}])");

    private static final Pattern NOUN_UPDATE_COMMAND = Pattern.compile(
            "^\\s*(?:profile|account|settings)[\\t ]+update\\s*[.!?]?\\s*$");

    private static final Pattern LOCAL_STATE_CONTEXT = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}\\p{Pc}])"
                    + "local[\\t ]+(?:state|session|configuration|config|runtime)"
                    + "(?![\\p{L}\\p{N}\\p{M}\\p{Pc}])");

    private static final Pattern CURRENT_LOCAL_STATE_CONTEXT = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}\\p{Pc}])"
                    + "current[\\t ]+(?:profile|account|settings|state|session|configuration|config|runtime)"
                    + "(?![\\p{L}\\p{N}\\p{M}\\p{Pc}])");

    /**
     * Determine whether a web search should be executed.  When the mode is
     * OFF, searching is skipped entirely.  FORCE_LIGHT and FORCE_DEEP
     * unconditionally return a decision to search with the corresponding
     * depth.  In AUTO mode, a naive heuristic is used: questions ending
     * with a question mark or containing Korean question particles
     * (“무엇”, “어떻게”, etc.) trigger a LIGHT search; comparative or
     * factual keywords ("vs", "비교", "뭐가 더") trigger a DEEP search.
     * Explicit web lookup requests trigger LIGHT, or DEEP when they also ask
     * for fact checking or cross-validation. All other queries default to no
     * search. This heuristic is intentionally
     * conservative; real deployments should integrate a proper cognitive
     * state analyser and LLM tool call inspection.
     *
     * @param query The user’s natural language request (may be null)
     * @param mode The requested search mode
     * @param providerIds String identifiers of preferred providers
     * @param topK Desired number of results per provider (fallback to 5 when null)
     * @return A search decision record
     */
    public SearchDecision decide(String query, SearchMode mode, List<String> providerIds, Integer topK) {
        return decide(query, mode, providerIds, topK, true);
    }

    /** Short public hints can require evidence intent instead of treating every question as a lookup. */
    public SearchDecision decide(String query, SearchMode mode, List<String> providerIds, Integer topK,
                                 boolean inferSearchFromGeneralQuestion) {
        if (mode == null) mode = SearchMode.AUTO;
        int k = (topK == null || topK <= 0) ? 5 : topK;
        // Resolve providers
        List<ProviderId> providers = new ArrayList<>();
        if (providerIds != null && !providerIds.isEmpty()) {
            for (String id : providerIds) {
                try {
                    providers.add(ProviderId.valueOf(id.trim().toUpperCase(Locale.ROOT)));
                } catch (Exception ignore) {
                    traceSuppressed("searchDecision.providerId", ignore);
                    // skip unknown providers
                }
            }
        }
        if (providers.isEmpty()) {
            // Default provider order when none specified
            boolean english = looksEnglish(query);
            if (english) {
                // [Patch] 영어질의: GoogleCSE → Tavily → Naver (Bing 비활성화)
                providers.add(ProviderId.GOOGLECSE);
                providers.add(ProviderId.TAVILY);
                providers.add(ProviderId.NAVER);
            } else {
                // [Patch] 한글/기타: NAVER → Tavily (Bing 비활성화)
                providers.add(ProviderId.NAVER);
                providers.add(ProviderId.TAVILY);
            }
        }
        switch (mode) {
            case OFF:
                return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k, "Search disabled by user");
            case FORCE_LIGHT:
                return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k, "Forced light search");
            case FORCE_DEEP:
                return new SearchDecision(true, SearchDecision.Depth.DEEP, providers, k, "Forced deep search");
            case AUTO:
            default:
                String q = (query == null) ? "" : query.toLowerCase(Locale.ROOT);
                // Simple heuristics for demonstration
                boolean endsWithQuestion = q.trim().endsWith("?");
                boolean containsComparative = q.contains(" vs ") || q.contains("비교") || q.contains("뭐가 더");
                boolean explicitWebLookup = hasExplicitWebLookupIntent(q);
                boolean factVerification = hasFactVerificationIntent(q);
                boolean recencyIntent = hasRecencyIntent(q);
                if (explicitWebLookup && factVerification) {
                    return new SearchDecision(true, SearchDecision.Depth.DEEP, providers, k,
                            "Explicit web fact-check intent triggers deep search");
                } else if (containsComparative) {
                    return new SearchDecision(true, SearchDecision.Depth.DEEP, providers, k, "Comparative query triggers deep search");
                } else if (explicitWebLookup) {
                    return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k,
                            "Explicit web lookup intent triggers light search");
                } else if (recencyIntent) {
                    return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k,
                            "Explicit recency intent triggers light search");
                } else if (SELF_CONTAINED_ARITHMETIC.matcher(q.strip()).matches()) {
                    return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k,
                            "Self-contained arithmetic does not require web evidence");
                } else if (inferSearchFromGeneralQuestion
                        && (endsWithQuestion || q.contains("무엇") || q.contains("어떻게") || q.contains("왜"))) {
                    // 세션/대화 자체에 대한 후속 질문은 웹 근거가 아니라 대화 맥락으로
                    // 답해야 하므로 일반 질문 추론의 검색 발동만 억제한다.
                    // 명시적 웹검색/최신성 의도는 위 분기에서 먼저 처리되므로 영향 없다.
                    if (looksLikeSessionContextQuestion(q)) {
                        TraceStore.put("search.decision.sessionContextSuppressed", true);
                        return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k,
                                "Session-context follow-up does not need web evidence");
                    }
                    return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k, "Question detected triggers light search");
                } else {
                    return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k, "No search needed (heuristic)");
                }
        }
    }

    private static boolean hasExplicitWebLookupIntent(String query) {
        String q = query == null ? "" : query;
        boolean koreanWebLookup = q.contains("웹") && containsAny(q,
                "찾아", "찾아보", "검색해", "검색하", "가져오", "확인해", "확인하");
        return koreanWebLookup || containsAny(q,
                "search the web", "browse the web", "look up online", "lookup online",
                "find online", "web lookup");
    }

    private static boolean hasFactVerificationIntent(String query) {
        String q = query == null ? "" : query;
        return containsAny(q,
                "사실관계", "팩트체크", "팩트 체크", "교차 검증", "교차검증",
                "fact check", "fact-check", "cross-check", "cross check",
                "verify the facts", "verify facts");
    }

    private static boolean hasRecencyIntent(String query) {
        String q = query == null ? "" : query;
        boolean localUpdateContext = LOCAL_UPDATE_COMMAND.matcher(q).find()
                || POSSESSIVE_UPDATE_CONTEXT.matcher(q).find()
                || NOUN_UPDATE_COMMAND.matcher(q).find();
        if (localUpdateContext
                && !containsAny(q, "latest", "recent", "release", "news")) {
            return false;
        }
        if ((LOCAL_STATE_CONTEXT.matcher(q).find() || CURRENT_LOCAL_STATE_CONTEXT.matcher(q).find())
                && !containsAny(q, "latest", "recent", "release", "news", "today")) {
            return false;
        }
        return containsAny(q,
                "최신", "최근", "업데이트", "패치", "출시", "발표", "뉴스", "근황", "현재", "지금", "오늘")
                || ENGLISH_RECENCY_INTENT.matcher(q).find();
    }

    /**
     * 세션/대화 자체에 대한 후속 질문인지 결정론적으로 판별한다.
     * 대화 맥락 지시어(기억/세션/방금/직전/you said 등)와 함께 접속 후속 표지
     * (그럼/이제/계속/then 등) 또는 회상 패턴(뭐라고/말했/what did i 등)이
     * 나타나면 세션 맥락 질문으로 본다. 맥락 지시어만 있는 단독 질문
     * (예: "기억력 좋아지는 방법이 뭐야?")은 억제하지 않는다.
     */
    private static boolean looksLikeSessionContextQuestion(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        boolean metaRef = containsAny(q,
                "기억", "세션", "대화", "맥락", "방금", "아까", "직전", "이전", "앞서",
                "저장", "earlier", "previous message", "last message",
                "this session", "the session", "our conversation", "conversation",
                "you said", "i said", "we talked", "remember when", "chat history");
        if (!metaRef) {
            return false;
        }
        boolean followUp = containsAny(q,
                "그럼", "그러면", "그래서", "그런데", "이제", "계속", "그거", "그것",
                "다음", "다시", "then", "now what", "what now", "continue",
                "and then", "so what", "what should");
        boolean recall = containsAny(q,
                "뭐라고", "뭐라 했", "말했", "물어봤", "질문", "얘기", "말한",
                "did i say", "did i ask", "what i said", "what did i");
        return followUp || recall;
    }

    private static boolean containsAny(String query, String... markers) {
        if (query == null || query.isBlank() || markers == null) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && query.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Heuristic to determine if a query appears to be English.  Counts Latin
     * letters versus Hangul syllables; if at least one Latin letter is present
     * and no Hangul is detected we consider it English.
     *
     * @param s the input query
     * @return true if the query appears to be English, false otherwise
     */
    private static boolean looksEnglish(String s) {
        if (s == null) return false;
        int latin = 0, hangul = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                latin++;
            }
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            if (b == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || b == Character.UnicodeBlock.HANGUL_JAMO
                    || b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                hangul++;
            }
        }
        return latin > 0 && hangul == 0;
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = SafeRedactor.traceLabelOrFallback(
                ignored == null ? null : ignored.getClass().getSimpleName(), "unknown");
        TraceStore.inc("search.decision.suppressed.count");
        TraceStore.inc("search.decision.suppressed." + safeStage + ".count");
        TraceStore.put("search.decision.suppressed.stage", safeStage);
        TraceStore.put("search.decision.suppressed.errorType", errorType);
        TraceStore.put("search.decision.suppressed." + safeStage, true);
        TraceStore.put("search.decision.suppressed." + safeStage + ".errorType", errorType);
    }
}
