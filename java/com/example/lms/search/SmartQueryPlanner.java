// patched SmartQueryPlanner
package com.example.lms.search;

import com.example.search.plan.MultiQueryPlan;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.search.extract.HybridKeywordExtractor;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import com.example.lms.search.KeywordSelectionService;
import com.example.lms.search.terms.SelectedTerms;
import java.util.Objects;




// Keyword selection types

/**
 * 지능형 다중 쿼리 생성기.
 * QueryTransformer.transformEnhanced() 결과를 받아 위생 처리(Hygiene) 및 상한(Cap) 적용 후 반환합니다.
 */
@Component
public class SmartQueryPlanner {

    private final HybridKeywordExtractor extractor;
    private final QueryTransformer transformer;
    private final SubjectResolver subjectResolver;
    private final KnowledgeBaseService knowledgeBase;
    // Unified noise clipper used to normalise user prompts prior to
    // subject/domain inference and keyword extraction.  This ensures
    // consistent cleaning and prevents duplicated clipping logic across
    // retrievers and planners.
    private final com.example.lms.search.NoiseClipper noiseClipper;

    /**
     * Optional LLM driven keyword selection service.  When available this
     * service will be invoked before the hybrid keyword extractor to
     * dynamically assemble search vocabulary from the full conversation.
     * When not injected (e.g. in tests or when disabled via config) the
     * planner will fall back to the existing extraction logic.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KeywordSelectionService selector;

    /**
     * 의존성 주입을 위한 생성자.
     *
     * @param extractor     하이브리드 키워드 추출기
     * @param transformer   쿼리 변환을 수행할 트랜스포머 (앵커드 쿼리 플래너에서 사용)
     * @param subjectResolver SubjectResolver
     * @param knowledgeBase  KnowledgeBaseService
     */
    public SmartQueryPlanner(
            HybridKeywordExtractor extractor,
            @Qualifier("queryTransformer") QueryTransformer transformer,
            SubjectResolver subjectResolver,
            KnowledgeBaseService knowledgeBase,
            com.example.lms.search.NoiseClipper noiseClipper
    ) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.subjectResolver = subjectResolver;
        this.knowledgeBase = knowledgeBase;
        this.noiseClipper = noiseClipper;
    }

    /**
     * 사용자 질문(+선택적 초안)을 바탕으로 검색에 투입할 "핵심 쿼리" 목록을 생성합니다.
     * <ul>
     * <li><b>중앙 집중 생성</b>: 쿼리 생성 로직은 QueryTransformer로 중앙화합니다.</li>
     * <li><b>위생 및 정제</b>: QueryHygieneFilter를 통해 중복 제거, 빈 문자열 필터링, 길이 제한 등을 적용합니다.</li>
     * </ul>
     * @param userPrompt 사용자 원본 질문
     * @param assistantDraft (선택 사항) 모델이 생성한 1차 초안. 쿼리 확장 힌트로 사용될 수 있습니다.
     * @param maxQueries 반환할 최대 쿼리 개수 (1~4개로 제한)
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
        // Clear any existing trace data for the current request.  Without this
        // explicit clear, previous trace values could leak across threads.
        com.example.lms.search.TraceStore.clear();

        // 0) Clean the user prompt once at the entry point.  The NoiseClipper
        // removes polite suffixes, leading labels and collapses whitespace.  When
        // noiseClipper is not available (e.g. in tests) fall back to the raw
        // prompt.  An empty or null input yields an empty cleaned string.
        String cleaned = (noiseClipper != null)
                ? noiseClipper.clip(userPrompt)
                : Objects.toString(userPrompt, "").trim();

        // 1) Infer domain and subject based on the cleaned prompt.  Domain
        // inference determines the cap and deduplication thresholds; subject
        // resolution provides the anchor used in sanitisation.
        String domain = knowledgeBase.inferDomain(cleaned);
        String subject = subjectResolver.resolve(cleaned, domain).orElse(null);

        // 2) Attempt to select search vocabulary via LLM.  When a
        // KeywordSelectionService is present and returns a non-empty
        // result, bypass the hybrid keyword extractor and build queries
        // directly from the selected terms.  This branch returns
        // immediately after sanitisation.  Should the service be
        // unavailable or fail to parse the JSON, control falls back to
        // the existing extraction logic below.
        if (selector != null) {
            try {
                java.util.Optional<SelectedTerms> maybeTerms = selector.select(cleaned, domain, 3);
                if (maybeTerms.isPresent()) {
                    SelectedTerms selected = maybeTerms.get();
                    // assemble tokens: quoted exact phrases, must and should keywords
                    List<String> tokens = new ArrayList<>();
                    if (selected.getExact() != null) {
                        for (String e : selected.getExact()) {
                            if (e != null && !e.isBlank()) {
                                String trimmed = e.trim();
                                // wrap in quotes to anchor exact matches
                                tokens.add("\"" + trimmed + "\"");
                            }
                        }
                    }
                    if (selected.getMust() != null) {
                        for (String m : selected.getMust()) {
                            if (m != null && !m.isBlank()) {
                                tokens.add(m.trim());
                            }
                        }
                    }
                    if (selected.getShould() != null) {
                        for (String s : selected.getShould()) {
                            if (s != null && !s.isBlank()) {
                                tokens.add(s.trim());
                            }
                        }
                    }
                    // Deduplicate and limit the number of tokens to avoid overly long queries.
                    List<String> dedup = tokens.stream()
                            .filter(t -> t != null && !t.isBlank())
                            .distinct()
                            .limit(12)
                            .collect(Collectors.toList());
                    String q0 = String.join(" ", dedup);
                    // Record the selected terms for debugging; this will be consumed by WebSearchHandler.
                    com.example.lms.search.TraceStore.put("selectedTerms", selected);
                    // Compose one or two queries: base q0 and optionally prefix with subject
                    List<String> qs = new ArrayList<>();
                    qs.add(q0);
                    if (subject != null && !subject.isBlank()
                            && !q0.toLowerCase().contains(subject.toLowerCase())) {
                        qs.add((subject + " " + q0).trim());
                    }
                    // Apply hygiene filtering based on domain caps and thresholds
                    return QueryHygieneFilter.sanitizeForDomain(qs, domain);
                }
            } catch (Exception e) {
                // Defensive: log and proceed to hybrid extraction on any exception
                org.slf4j.LoggerFactory.getLogger(SmartQueryPlanner.class).warn("[SmartQueryPlanner] LLM keyword selection failed; falling back", e);
}
        }

        // 3) Determine cap and jaccard based on the domain.  GENERAL
        // allows more diversity (6-8 queries) with a lower deduplication
        // threshold; specialised domains cap at 4 queries with a higher
        // similarity threshold.
        boolean isGeneral = "GENERAL".equalsIgnoreCase(domain);
        int cap;
        double jaccard;
        if (isGeneral) {
            cap = Math.min(8, Math.max(6, maxQueries));
            jaccard = 0.60;
        } else {
            cap = Math.max(1, Math.min(4, maxQueries));
            jaccard = 0.80;
        }

        // 3) Use the hybrid keyword extractor to propose candidate queries from
        // the cleaned input.  The extractor will internally choose between
        // rule, LLM or hybrid strategies based on configuration and heuristics.
        List<String> cand = extractor.extract(cleaned, assistantDraft, subject, domain, cap, jaccard);
        // Retrieve the raw LLM proposals.  When the extractor did not invoke
        // the LLM this list may be empty.  This data can be used for trace
        // visualisation or debugging.
        List<String> llmProposed = extractor.getLastProposed();
        // 4) Apply hygiene filtering and mandatory subject anchoring.  This
        // step removes duplicates, enforces the cap and inserts the subject
        // anchor into queries that lack it.  Capture the kept list for trace.
        List<String> hygieneKept = QueryHygieneFilter.sanitizeAnchored(cand, cap, jaccard, subject, null);
        // In the current implementation the final used queries are identical
        // to the hygiene kept list.  If additional downstream filters are
        // introduced this variable can be updated accordingly.
        List<String> finalQs = hygieneKept;
        // Record trace information for downstream diagnostics.  The
        // TraceStore stores values on a per-thread basis and is cleared
        // automatically at the start of this method.
        com.example.lms.search.TraceStore.put("llmProposed", llmProposed);
        com.example.lms.search.TraceStore.put("hygieneKept", hygieneKept);
        com.example.lms.search.TraceStore.put("finalUsed", finalQs);
        return finalQs;
    }

    /**
     * assistantDraft 없이 최대 2개의 쿼리를 생성하는 편의 메서드입니다.
     * @param userPrompt 사용자 원본 질문
     * @return 정제된 쿼리 문자열 목록
     */
    public List<String> plan(String userPrompt) {
        return plan(userPrompt, null, 2);
    }

    /**
     * PAIRING 등에서 주어를 강제 포함시키는 쿼리 플래너.
     * 앵커 삽입 및 정제는 QueryHygieneFilter에 위임합니다.
     */
    public List<String> planAnchored(
            String userPrompt,
            String subjectPrimary,
            @Nullable String subjectAlias,
            @Nullable String assistantDraft,
            int maxQueries
    ) {
        int cap = Math.max(1, Math.min(4, maxQueries));

        // 1. QueryTransformer를 통해 원시 쿼리 목록을 생성합니다.
        List<String> raw = transformer.transformEnhanced(
                Objects.toString(userPrompt, ""),
                assistantDraft
        );

        // 2. [수정] 앵커링 및 정제 작업을 QueryHygieneFilter.sanitizeAnchored 메서드에 모두 위임합니다.
        //    - 이 클래스 내에서 앵커를 미리 추가하는 중복 로직을 제거했습니다.
        return QueryHygieneFilter.sanitizeAnchored(raw, cap, 0.80, subjectPrimary, subjectAlias);
    }

    /**
     * ✨ 새 오버로드: (주제 미지정) → SubjectResolver로 자동 추정 후 앵커 적용
     */
    public List<String> planAnchored(
            String userPrompt,
            @Nullable String assistantDraft,
            int maxQueries
    ) {
        int cap = Math.max(1, Math.min(4, maxQueries));
        String domain = knowledgeBase.inferDomain(userPrompt);
        String subject = subjectResolver.resolve(userPrompt, domain).orElse(null);
        List<String> raw = transformer.transformEnhanced(
                Objects.toString(userPrompt, ""),
                assistantDraft,
                subject
        );
        return QueryHygieneFilter.sanitizeAnchored(raw, cap, 0.80, subject, null);
    }

    // ⬅️ [제거] 이 메서드는 QueryHygieneFilter.sanitizeAnchored 내부 로직과 중복되므로 제거합니다.
    // private static String ensureSubjectAnchor(String q, String primary, String alias) { /* ... */ }
    // Added by patch: simple multi-query plan

    }