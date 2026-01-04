package com.example.lms.search.extract;

import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.service.QueryAugmentationService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.transform.QueryTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;



/**
 * A hybrid keyword extractor that can operate in rule based, LLM based or
 * combined modes. In AUTO mode it determines which strategy to use based
 * on simple heuristics such as query length, the presence of question
 * patterns and punctuation suggesting multiple clauses. The extractor
 * returns a sanitised list of search queries suitable for downstream
 * retrieval components.
 */
@Component
public class HybridKeywordExtractor {

    private final QueryAugmentationService augmentationService;
    private final QueryTransformer transformer;
    private final SubjectResolver subjectResolver;
    private final KnowledgeBaseService knowledgeBase;

    /**
     * Holds the most recent set of LLM proposed queries.  This list is
     * populated during the {@link #extract(String, String, String, String, int, double)}
     * call when the LLM backend is used.  Callers can obtain the proposals
     * via {@link #getLastProposed()}.  When no LLM call occurs or when the
     * call fails, this list is cleared or reflects an empty list.
     */
    private List<String> lastProposed = java.util.Collections.emptyList();

    /**
     * Backend used for LLM keyword extraction.  Accepts values such as
     * {@code lc4j} (default) or {@code gemini}.  When set to {@code gemini}
     * and a {@link com.example.lms.client.GeminiClient} bean is available,
     * the extractor will invoke Gemini for keyword generation instead of
     * using the default LangChain4j transformer.  Any other value falls
     * back to the existing transformer behaviour.
     */
    @Value("${search.llm.backend:lc4j}")
    private String llmBackend;

    /** Optional Gemini client.  Injected when the gemini backend is
     * configured and the bean is present in the application context.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.learning.gemini.GeminiClient geminiClient;

    /**
     * User configurable extraction mode. When not AUTO this setting
     * overrides the heuristic decision logic.
     */
    @Value("${search.extractor.mode:AUTO}")
    private String extractorMode;

    /**
     * Minimum number of characters to consider a query as long for auto gating.
     */
    @Value("${search.extractor.auto.min-chars:12}")
    private int autoMinChars;

    /**
     * Construct a new hybrid extractor.
     *
     * @param augmentationService rule based augmentation service
     * @param transformer         LLM based query transformer
     * @param subjectResolver     resolves subject anchors
     * @param knowledgeBase       domain inference service
     */
    public HybridKeywordExtractor(QueryAugmentationService augmentationService,
                                  @Qualifier("queryTransformer") QueryTransformer transformer,
                                  SubjectResolver subjectResolver,
                                  KnowledgeBaseService knowledgeBase) {
        this.augmentationService = augmentationService;
        this.transformer = transformer;
        this.subjectResolver = subjectResolver;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * Extract a list of keyword style search queries based on the given
     * parameters. The extractor will decide whether to use rule based
     * expansion, LLM based transformation or a hybrid of both depending on
     * the configured extraction mode and the heuristics applied in AUTO mode.
     *
     * @param userPrompt      the original user prompt
     * @param assistantDraft  an optional assistant draft answer
     * @param subject         an optional subject anchor; if {@code null}
     *                        the extractor will attempt to resolve it
     * @param domain          inferred domain for the prompt (e.g. GENERAL)
     * @param cap             maximum number of queries to return
     * @param jaccard         similarity threshold for deduplication
     * @return a list of sanitised queries
     */
    public List<String> extract(String userPrompt,
                                @Nullable String assistantDraft,
                                @Nullable String subject,
                                String domain,
                                int cap,
                                double jaccard) {
        // Reset last proposed list at the start of extraction.  It will be
        // repopulated when the LLM path is taken below.
        lastProposed = java.util.Collections.emptyList();
        // Determine the subject if not provided
        String anchor = subject;
        if (anchor == null || anchor.isBlank()) {
            anchor = subjectResolver.resolve(userPrompt, domain).orElse(null);
        }
        // Resolve extraction mode based on configuration and heuristics
        QueryExtractionMode mode = resolveMode(userPrompt, assistantDraft);

        List<String> ruleResults = Collections.emptyList();
        List<String> llmResults = Collections.emptyList();
        // RULE path
        if (mode == QueryExtractionMode.RULE || mode == QueryExtractionMode.HYBRID) {
            try {
                ruleResults = augmentationService.augment(userPrompt);
            } catch (Exception ignored) {
                ruleResults = Collections.emptyList();
            }
        }
        // LLM path
        if (mode == QueryExtractionMode.LLM || mode == QueryExtractionMode.HYBRID) {
            try {
                // Choose backend for keyword generation.  When the llmBackend
                // property is set to 'gemini' and the Gemini client is
                // available, delegate to the client for keyword variants.
                // Otherwise fall back to the default transformer.
                if ("gemini".equalsIgnoreCase(Objects.toString(llmBackend, "lc4j"))
                        && geminiClient != null) {
                    llmResults = geminiClient.keywordVariants(
                            Objects.toString(userPrompt, ""),
                            anchor,
                            cap
                    );
                } else {
                    llmResults = transformer.transformEnhanced(
                            Objects.toString(userPrompt, ""),
                            assistantDraft,
                            anchor);
                }
                // Store the raw LLM results for traceability.  Even when
                // sanitisation occurs later, callers can access the original
                // proposals via getLastProposed().
                if (llmResults != null) {
                    lastProposed = new ArrayList<>(llmResults);
                } else {
                    lastProposed = java.util.Collections.emptyList();
                }
            } catch (Exception e) {
                llmResults = Collections.emptyList();
            }
            // If LLM returned nothing in pure LLM mode, fallback to RULE
            if (mode == QueryExtractionMode.LLM && (llmResults == null || llmResults.isEmpty())) {
                mode = QueryExtractionMode.RULE;
                try {
                    ruleResults = augmentationService.augment(userPrompt);
                } catch (Exception ignored) {
                    ruleResults = Collections.emptyList();
                }
            }
        }
        // Merge results depending on mode
        List<String> merged = new ArrayList<>();
        switch (mode) {
            case RULE -> merged.addAll(ruleResults);
            case LLM -> merged.addAll(llmResults);
            case HYBRID -> {
                if (ruleResults != null) merged.addAll(ruleResults);
                if (llmResults != null) merged.addAll(llmResults);
            }
            default -> {
                if (llmResults != null && !llmResults.isEmpty()) merged.addAll(llmResults);
                else if (ruleResults != null) merged.addAll(ruleResults);
            }
        }
        if (merged.isEmpty()) {
            return merged;
        }
        // Sanitise and apply subject anchoring when appropriate
        if (mode == QueryExtractionMode.HYBRID && anchor != null && !anchor.isBlank()) {
            return QueryHygieneFilter.sanitizeAnchored(merged, cap, jaccard, anchor, null);
        } else {
            return QueryHygieneFilter.sanitize(merged, cap, jaccard);
        }
    }

    /**
     * Return the raw list of proposals generated by the most recent LLM
     * invocation.  This list is reset at the beginning of each call to
     * {@link #extract(String, String, String, String, int, double)}.  When
     * the LLM path is not executed or when no proposals are returned, an
     * empty list is returned.
     *
     * @return a copy of the last LLM proposed queries, never {@code null}
     */
    public List<String> getLastProposed() {
        return lastProposed != null ? new java.util.ArrayList<>(lastProposed) : java.util.Collections.emptyList();
    }

    /**
     * Resolve the effective extraction mode. If the configured mode is not
     * AUTO it is used directly; otherwise simple heuristics are applied.
     *
     * @param prompt         the user prompt
     * @param assistantDraft optional assistant draft
     * @return the decided extraction mode
     */
    private QueryExtractionMode resolveMode(String prompt, @Nullable String assistantDraft) {
        QueryExtractionMode configured;
        try {
            configured = QueryExtractionMode.valueOf(
                    Objects.toString(extractorMode, "AUTO").trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            configured = QueryExtractionMode.AUTO;
        }
        if (configured != QueryExtractionMode.AUTO) {
            return configured;
        }
        // AUTO heuristics
        int score = 0;
        String p = Objects.toString(prompt, "");
        int len = p.length();
        if (len >= autoMinChars) {
            score += 2;
        }
        // Punctuation suggesting multiple clauses or list formatting
        if (p.contains(",") || p.contains(";") || p.contains("·") || p.contains("•")) {
            score += 1;
        }
        // Question words or question mark indicates a complex/intent seeking query
        String lower = p.toLowerCase(Locale.ROOT);
        if (p.contains("?") ||
                lower.contains("어떻게") || lower.contains("왜") || lower.contains("무엇") ||
                lower.contains("which") || lower.contains("how") || lower.contains("why") ||
                lower.contains("where") || lower.contains("when")) {
            score += 1;
        }
        // If there is an assistant draft hint we lean towards LLM or hybrid
        if (assistantDraft != null && !assistantDraft.isBlank()) {
            score += 1;
        }
        // Decide based on accumulated score
        if (score <= 1) {
            return QueryExtractionMode.RULE;
        } else if (score >= 3) {
            return QueryExtractionMode.LLM;
        } else {
            return QueryExtractionMode.HYBRID;
        }
    }
}