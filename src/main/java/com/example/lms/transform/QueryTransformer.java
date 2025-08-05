package com.example.lms.transform;

import java.util.*;
import java.util.regex.Pattern;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.lang.Nullable;
import java.util.regex.Matcher;
import java.time.Duration;
import java.util.regex.Pattern;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * 쿼리 오타를 교정해 주는 Transformer
 */
public class QueryTransformer {



    /** LLM 제안·힌트 개수 상한 */
    private static final int MAX_VARIANTS = 3;   // generateVariantsWithLLM() 한도
    private static final int MAX_HINTS    = 5;   // transformEnhanced() 힌트 한도

    /* ────────────────────────────────────────
     * 0.  “원소 감지”  ― 쿼리 Intent Enum
     * ────────────────────────────────────────*/
    public enum QueryIntent {
        PRODUCT_SPEC,        // 제품‧스펙‧가격
        LOCATION_RECOMMEND,  // 맛집‧여행지
        TECHNICAL_HOW_TO,    // 코딩·설정 방법
        PERSON_LOOKUP,       // 인물 정보
        GENERAL_KNOWLEDGE    // 그 외
    }
// QueryTransformer.java  ─ 클래스 필드 영역에 삽입
    /** ───── cleanUp()용 정규식 ───── */
    private static final Pattern CLEANUP_PREFIX_NUM    = Pattern.compile("^[0-9]+[\\.:\\)]\\s*");
    private static final Pattern CLEANUP_PREFIX_BULLET = Pattern.compile("^[\\-*•·]\\s*");
    private static final Pattern CLEANUP_META          = Pattern.compile("^(틀렸.*?[:：]\\s*|올바른\\s*(표기|표현)[:：]\\s*)");
    private static final Pattern CLEANUP_SPACES        = Pattern.compile("[\\p{Z}\\s]{2,}");
    private static final Pattern CLEANUP_QUOTES        = Pattern.compile("[\"“”'’`]+");

    /* (선택) 프로젝트에서 유지할 소규모 오타 사전 – 빈맵이면 사용 안 함 */
    private final Map<String,String> dict;

    private final OpenAiChatModel llm;
    private final HintExtractor hintExtractor;
    /** LLM 호출 결과를 캐시하여 동일한 요청에 대한 비용과 지연을 줄인다. */
    private final LoadingCache<String, String> llmCache;


    /* LLM이 생성할 동적 버프 1회 한도 */
    private static final int MAX_DYNAMIC_BUFFS = 4;

    public QueryTransformer(OpenAiChatModel llm) {
        this(llm, Map.of(), null);
    }
    public QueryTransformer(OpenAiChatModel llm,
                            Map<String,String> customDict,
                            @Nullable HintExtractor hintExtractor) {
        this.llm           = llm;
        this.dict          = (customDict != null) ? customDict : Map.of();
        this.hintExtractor = (hintExtractor != null) ? hintExtractor : new RegexHintExtractor();
        // 캐시는 5분 동안 결과를 보존하며 최대 1000개의 프롬프트를 저장한다.
        this.llmCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(1000)
                .build(prompt -> {
                    try {
                        return llm.chat(prompt);
                    } catch (Exception e) {
                        // 캐시 로딩 실패 시 빈 문자열 반환
                        return "";
                    }
                });
    }

    public List<String> transform(String context, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of(normalizedQuery);
        }

        String q = dict.getOrDefault(normalizedQuery.trim(), normalizedQuery.trim());
        /* ① LLM 맞춤법 교정 */
        q = correctWithLLM(context, q);

        /* ② LLM 다중-제안(최대 3개) */
        List<String> variants = generateVariantsWithLLM(q);

        /* ③ 원본·교정·변형 합치기 */
        return Stream.concat(Stream.of(normalizedQuery, q), variants.stream())
                .map(this::cleanUp)
                .filter(s -> s!=null && !s.isBlank())
                .distinct()
                .toList();
    }

    /** LLM 한 번 호출해 맞춤법을 교정한다 */
    private String correctWithLLM(String ctx, String q) {
        try {
            /* + 프롬프트를 강화해 **교정된 문장만** 반환하도록 명시 */
            String prompt = """
                다음 문장의 맞춤법을 확인하고
                ✱ 틀렸으면 **교정된 문장만 한 줄**,
                ✱ 맞으면 **입력 문장을 그대로** 한 줄로 반환하세요.
                설명(예: "틀렸습니다.", "올바른 표기:")은 절대로 넣지 마세요.
                문장: "%s"
                """.formatted(q);

            // 캐시를 먼저 조회하고 없으면 채운다.
            String ans = llmCache.get(prompt);
            ans = cleanUp(ans);               //  불필요 토큰 제거

            /*  +콜론/화살표 구분이 여전히 남아 있으면 오른쪽만 취함 */
            if (ans.matches(".*[:：→>-].+")) {
                ans = ans.replaceFirst(".*[:：→>-]\\s*", "");
            }
            return (ans != null && !ans.isBlank()) ? ans : q;
        } catch (Exception e) {
            return q;  // 실패 시 원본 유지
        }
    }

    /** LLM이 제시한 추가 검색어(최대 3개)를 반환 – 실패 시 빈 리스트 */
    private List<String> generateVariantsWithLLM(String q) {
        try {
            String prompt = """
            아래 문장을 한국어 맞춤법에 맞게 교정‧변형하여
            검색 엔진에 넣기 좋은 **3가지** 버전으로 제안해 주세요.
            - 불필요한 설명 없이 각 안을 한 줄씩만 출력
            문장: "%s"
            """.formatted(q);
            String ans = llmCache.get(prompt);
            if (ans == null || ans.isBlank()) return List.of();
            return Arrays.stream(ans.split("\\r?\\n"))
                    .map(this::cleanUp)                       // 앞머리 제거·트림
                    .filter(s -> s != null && !s.isBlank())
                    .limit(MAX_VARIANTS)                      // 안전 상한
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 새 API: 사용자 질의 + GPT 답변에서 힌트를 섞어 검색용 다중 쿼리 생성
    // ─────────────────────────────────────────────────────────────
    public List<String> transformEnhanced(String userPrompt, @Nullable String assistantAnswer) {
        /* ① “원소 감지” – Intent 분류 */
        QueryIntent intent = classifyIntent(userPrompt);

        /* ② 사용자 문장 기반 교정(기존 로직 재사용)  동적 버프 */
        List<String> base = this.transform("", defaultString(userPrompt)).stream()
                .map(q -> boostWithIntent(q, intent))
                .toList();
        /* ③ 복합 질문이면 “개화” – 세부 쿼리 분해 */
        List<String> subQs = isComplex(userPrompt) ? generateSubQueries(userPrompt) : List.of();
        /* ④ GPT 답변에서 힌트 추출 → Intent 버프 붙여 정규화 */
        List<String> boosted =
                (assistantAnswer == null ? Stream.<String>empty()
                        : hintExtractor.extractHints(assistantAnswer).stream())
                        .limit(MAX_HINTS)
                        .map(this::cleanUp)
                        .map(h -> boostWithIntent(h, intent))
                        .collect(Collectors.toList());

        /* ⑤ base  subQs  boosted 합치고 중복 제거 */
        return Stream.of(base, subQs, boosted)
                .flatMap(Collection::stream)
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }

    private String cleanUp(String s) {
        if (s == null) return null;
        String t = s;
        t = CLEANUP_PREFIX_NUM.matcher(t).replaceFirst("");
        t = CLEANUP_PREFIX_BULLET.matcher(t).replaceFirst("");
        t = CLEANUP_META.matcher(t).replaceFirst("");
        t = CLEANUP_SPACES.matcher(t).replaceAll(" ");
        t = CLEANUP_QUOTES.matcher(t).replaceAll("");
        return t.trim();
    }

    /* ────────────────────────────────────────
     * 2.  Intent-aware 키워드 버프
     * ────────────────────────────────────────*/
    /* ─────────────────────────────────────────
     * 동적 버프 생성 - intent  문맥을 LLM에 질문
     * ─────────────────────────────────────────*/
    private String boostWithIntent(String q, QueryIntent intent) {
        List<String> buffs = generateDynamicBuffs(q, intent);
        return buffs.isEmpty() ? q : (q + " " + String.join(" ", buffs));
    }

    private List<String> generateDynamicBuffs(String base, QueryIntent intent) {
        String prompt = """
            사용자가 "%s" 라는 주제로 검색하려고 합니다.
            의도 카테고리: %s
            검색 정확도를 높일 **추가 키워드**를 %d개까지 한국어로 제안해 주세요.
            - 설명 없이 한 줄에 하나씩만 출력
            - 불필요한 특수문자는 제외
            """.formatted(base, intent, MAX_DYNAMIC_BUFFS);

        String ans = llmCache.get(prompt);
        if (ans == null || ans.isBlank()) return List.of();

        return Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_DYNAMIC_BUFFS)
                .toList();
    }

    /* ────────────────────────────────────────
     * 3.  Intent 분류 LLM 호출
     * ────────────────────────────────────────*/
    private QueryIntent classifyIntent(String query) {
        if (query == null || query.isBlank()) return QueryIntent.GENERAL_KNOWLEDGE;
        String prompt = String.format("""
            다음 사용자 질문을 아래 카테고리 중 하나로 분류해줘.
            [PRODUCT_SPEC, LOCATION_RECOMMEND, TECHNICAL_HOW_TO, PERSON_LOOKUP, GENERAL_KNOWLEDGE]
            질문: "%s"
            카테고리:""", query);
        String result = llmCache.get(prompt);
        if (result == null || result.isBlank()) return QueryIntent.GENERAL_KNOWLEDGE;
        try {
            return QueryIntent.valueOf(result.trim()
                    .replaceAll("[^A-Za-z_]", "")
                    .toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return QueryIntent.GENERAL_KNOWLEDGE; // fallback
        }
    }

    /* ────────────────────────────────────────
     * 4.  복합 질문 감지 & 세부 쿼리 분해
     * ────────────────────────────────────────*/
    private boolean isComplex(String q) {
        if (q == null) return false;
        // 쉼표·그리고·및 등으로 두 토픽 이상이면 복합
        return q.split("(,|그리고|및)").length >= 2 || q.length() > 40;
    }

    private List<String> generateSubQueries(String question) {
        String prompt = """
            다음 복합 질문을 3개의 구체적인 탐색 질문으로 분해해서
            한 줄에 하나씩만 출력해 줘. 설명은 넣지 마.
            질문: "%s"
            세부 질문:
            """.formatted(question);
        String ans = llmCache.get(prompt);
        if (ans == null || ans.isBlank()) return List.of();
        return Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .toList();
    }

    private static String defaultString(String s) {
        return (s == null) ? "" : s;
    }

    // ─────────────────────────────────────────────────────────────
    // 경량 힌트 추출기(내장). 필요 시 바깥에서 교체 주입 가능.
    // ─────────────────────────────────────────────────────────────
    public interface HintExtractor {
        List<String> extractHints(String assistantAnswer);
    }

    public static class RegexHintExtractor implements HintExtractor {
        // 따옴표 안/고유명사 비슷한 조각/ ~전생 패턴
        private final Pattern p = Pattern.compile("[\"“](.+?)[\"”]|([A-Za-z가-힣0-9 ]전생)");
        @Override
        public List<String> extractHints(String text) {
            if (text == null) return List.of();
            Matcher m = p.matcher(text);
            List<String> out = new ArrayList<>();
            while (m.find()) {
                String g1 = m.group(1);
                String g2 = m.group(2);
                out.add(g1 != null ? g1 : (g2 != null ? g2 : ""));
            }
            return out.stream().filter(s -> s != null && !s.isBlank())
                    .distinct()
                                    /* base(≤4) + boosted(≤MAX_HINTS) 의 총합 제한 */
                                   .limit(MAX_VARIANTS + MAX_HINTS + 2)
                    .toList();
        }
    }

}
