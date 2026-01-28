package com.example.lms.prompt;

import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.transform.QueryTransformer.QueryIntent;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;



/**
 * Centralised prompt builder for keyword extraction tasks.  All prompt
 * construction logic related to query correction, keyword generation and
 * cognitive variants resides in this class to ensure consistent
 * formatting and avoid ad hoc string concatenation in call sites.
 */
@Component
public class QueryKeywordPromptBuilder {

    /**
     * Build a prompt that instructs the LLM to correct the supplied sentence.
     *
     * <p>The model should return exactly one line: the corrected sentence
     * when the input contains mistakes, or the original sentence unchanged
     * when it is already correct.  No explanations or extra text should be
     * included in the response.</p>
     *
     * @param userPrompt the raw user sentence
     * @return a formatted prompt string
     */
    public String buildCorrectionPrompt(String userPrompt) {
        return String.format("""
                다음 문장의 맞춤법을 확인하고
                ✱ 틀렸으면 **교정된 문장만 한 줄**,
                ✱ 맞으면 **입력 문장을 그대로** 한 줄로 반환하세요.
                설명(예: "틀렸습니다.", "올바른 표기:")은 절대로 넣지 마세요.
                문장: "%s"
                """, userPrompt);
    }

    /**
     * Build a prompt instructing the LLM to generate keyword style search
     * variants.  An anchor subject may be supplied to steer the model
     * towards a particular topic; when no subject is known the model is
     * reminded not to guess one.  The prompt requests exactly {@code n}
     * variants, one per line, without bullet symbols or explanations.
     *
     * @param cleaned  the cleaned version of the original query
     * @param subject  the anchor subject, or {@code null} when unknown
     * @param n        the number of keyword variants to request
     * @return a formatted prompt string
     */
    public String buildKeywordVariantsPrompt(String cleaned, @Nullable String subject, int n) {
        String anchor = (subject == null || subject.isBlank())
                ? "the user's main topic (do NOT guess)"
                : subject;
        return String.format("""
                You are a Korean RAG query generator.
                Create exactly **%d** concise, keyword-style search queries (one per line).

                RULES:
                - **Anchor Focus**: Stay strictly on this subject → "%s".
                - **No Acronym Expansion** without explicit evidence (e.g., 'DW' ≠ 'Deutsche Welle').
                - **Do NOT invent attributes** (element, weapon, job, organization, background, abilities) for people/characters/places/game entities if they are not clearly stated in the original text.
                - **If uncertain** about attributes (e.g., elemental type, job class, affiliation), omit them entirely and only suggest neutral, search-friendly keywords (e.g., "설명", "정보", "능력치", "성능").
                - **Never fabricate** organization names, secret societies, or fictional settings not present in the original query.
                - Prefer terms relevant to the subject's likely domain (e.g., for academies: 수강후기, 커리큘럼, 위치, 등록).
                - Output **only** queries, one per line. No bullets or explanations.

                Original: "%s"
                """, n, anchor, cleaned);
    }

    /**
     * Build a prompt requesting additional keywords that may improve search
     * accuracy based on the user's intent.  The returned keywords should be
     * in Korean, one per line, without extraneous punctuation.
     *
     * @param base              the base query string
     * @param intent            the classified intent category
     * @param maxDynamicBuffs   maximum number of additional keywords to request
     * @return a formatted prompt
     */
    public String buildIntentBuffPrompt(String base, QueryIntent intent, int maxDynamicBuffs) {
        return String.format("""
                사용자가 "%s" 라는 주제로 검색하려고 합니다.
                의도 카테고리: %s
                검색 정확도를 높일 **추가 키워드**를 %d개까지 한국어로 제안해 주세요.
                - 설명 없이 한 줄에 하나씩만 출력
                - 불필요한 특수문자는 제외
                """, base, intent, maxDynamicBuffs);
    }

    /**
     * Build a prompt that asks the LLM to classify a user question into one of
     * the supported intent categories.  The expected output should be a
     * category name from the set {PRODUCT_SPEC, LOCATION_RECOMMEND,
     * TECHNICAL_HOW_TO, PERSON_LOOKUP, GENERAL_KNOWLEDGE}.
     *
     * @param query the user query
     * @return a formatted prompt
     */
    public String buildIntentClassificationPrompt(String query) {
        return String.format("""
                다음 사용자 질문을 아래 카테고리 중 하나로 분류해줘.
                [PRODUCT_SPEC, LOCATION_RECOMMEND, TECHNICAL_HOW_TO, PERSON_LOOKUP, GENERAL_KNOWLEDGE]
                질문: "%s"
                카테고리:""", query);
    }

    /**
     * Build a prompt instructing the LLM to decompose a complex question into
     * several specific exploratory questions.  The model should output each
     * sub-question on its own line without additional commentary.
     *
     * @param question the complex input question
     * @return a formatted prompt
     */
    public String buildSubQueriesPrompt(String question) {
        return String.format("""
                다음 복합 질문을 3개의 구체적인 탐색 질문으로 분해해서
                한 줄에 하나씩만 출력해 줘. 설명은 넣지 마.
                질문: "%s"
                세부 질문:
                """, question);
    }

    /**
     * Build a prompt leveraging the cognitive state parameters to generate
     * sub-queries.  This is used when the system has additional context
     * regarding abstraction level, temporal sensitivity and evidence types.
     *
     * @param cs        the cognitive state
     * @param subject   the known subject or {@code null} if unknown
     * @param baseQuery the original user query
     * @param n         the number of variants to request
     * @return a formatted prompt
     */
    public String buildCognitiveVariantsPrompt(CognitiveState cs, @Nullable String subject, String baseQuery, int n) {
        String anchor = (subject == null || subject.isBlank())
                ? "사용자 주제(추측 금지)"
                : subject;
        return String.format("""
                당신은 한국어 RAG 서브쿼리 생성기입니다.
                다음 제약에 따라 **%d개**의 키워드형 서브쿼리를 한 줄에 하나씩 만드세요.
                [추상도=%s, 시간민감도=%s, 증거유형=%s, 복잡도=%s]
                - 주제(anchor): "%s"
                - 원문: "%s"
                - **고유명사(캐릭터, 인물, 장소, 작품 등)에 대해 확실하지 않은 속성(원소, 무기, 직업, 조직 등)은 절대 새로 만들지 마세요.**
                - **모를 경우**, 원문 고유명사를 그대로 유지하고 "설명", "정보", "성능", "비교", "평가" 같은 일반 키워드만 덧붙이세요.
                - **존재하지 않는 조직명/세계관 설정을 창작하지 마세요.**
                - 불필요한 설명/기호 금지, 쿼리만 출력
                """,
                n,
                cs.abstractionLevel(),
                cs.temporalSensitivity(),
                String.join("/", cs.evidenceTypes()),
                cs.complexityBudget(),
                anchor,
                baseQuery);
    }

    /**
     * Build a prompt instructing the LLM to select search vocabulary from the
     * conversation.  The model should respond with a JSON object containing
     * arrays for {@code exact}, {@code must}, {@code should}, {@code maybe},
     * {@code negative}, {@code domains} and {@code aliases}.  No extra
     * narration or explanation should be included.
     *
     * @param conversation the complete conversation history (never null)
     * @param domainProfile the inferred domain profile used to filter noise
     * @param maxMust the maximum number of must-have keywords to include
     * @return a formatted prompt
     */
    public String buildSelectedTermsJsonPrompt(String conversation, String domainProfile, int maxMust) {
        return String.format("""
        ### ROLE
        너는 '웹 검색어 선별기'야. 아래 대화 전체를 요약하지 말고
        검색 엔진에 넣을 **'단어장(JSON)'**만 만들어.

        ### RULES
        - JSON만 출력(서문/설명 금지)
        - 필드: exact, must, should, maybe, negative, domains, aliases, domainProfile
        - exact: 따옴표 검색할 고유명(회사/작품/인물/용어)
        - must: 핵심 키워드(최대 %d개)
        - should: 보조 키워드
        - maybe: 있어도 되고 없어도 되는 후보 키워드
        - negative: 숙소/여행/광고/토렌트/rom/crack 등 '절사'해야 할 단어
        - domains: 신뢰 도메인(e.g. wikipedia.org, archive.org, 공식사)
        - aliases: 영문/약칭/로마자
        - domainProfile: 문자열 하나 (예: "tech", "shopping", "general") — 모르면 "general"
        - domainProfile=%s 를 참고해 잡음(지도/숙소/상거래/불법유도)을 거부
        - 한국어/영어 혼용 허용, 불필요한 특수문자 금지

        ### CONVERSATION
        %s
        """, maxMust, domainProfile, conversation);
    }
}