package com.example.lms.service.rag.handler;

import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 엔티티 모호성을 제거하고, 필요에 따라 Query를 재작성하는 핸들러입니다.
 *
 * <p>QueryDisambiguationService를 호출하여 모호하거나 오타가 있는 용어를 정정합니다.
 * 현재 구현에서는 재작성에 실패하거나 API 시그니처를 찾지 못할 경우 원본 Query를 그대로 반환합니다.
 * 상위 체인에서 doHandle 이전에 이 핸들러를 통해 Query를 업데이트하도록 사용합니다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class EntityDisambiguationHandler {
    private static final Logger log = LoggerFactory.getLogger(EntityDisambiguationHandler.class);

    private final QueryDisambiguationService disambiguationService;

    /**
     * Confidence threshold for rewriting queries.  When the disambiguation
     * service returns a numeric confidence (e.g. 0.0-1.0) the rewritten
     * query will only be used if the confidence exceeds this value.  If the
     * service instead returns textual values like "high" or "low" the
     * semantics default to a simple check where only "high" passes.  The
     * default threshold of 0.6 can be tuned via configuration key
     * {@code disambiguation.confidence.threshold}.
     */
    @org.springframework.beans.factory.annotation.Value("${disambiguation.confidence.threshold:0.6}")
    private double confidenceThreshold;

    /**
     * 주어진 Query의 텍스트를 DisambiguationService로 정제합니다.
     *
     * @param query   원본 Query
     * @param history 최근 대화 이력 (선택, null 가능)
     * @return 재작성된 Query 객체 (실패 시 원본 반환)
     */
    public Query disambiguate(Query query, List<String> history) {
        if (query == null || query.text() == null) {
            return query;
        }
        try {
            DisambiguationResult result = disambiguationService.clarify(query.text(), history);
            if (result == null) return query;
            String confidence = result.getConfidence();
            String rewritten = result.getRewrittenQuery();
            boolean goodConfidence = false;
            if (confidence != null) {
                // Attempt to parse numeric confidence first
                try {
                    double cf = Double.parseDouble(confidence.trim().replace(",", "."));
                    goodConfidence = cf >= confidenceThreshold;
                } catch (NumberFormatException nfe) {
                    // Fallback to high/low semantics
                    goodConfidence = confidence.equalsIgnoreCase("high");
                }
            }
            if (rewritten != null && !rewritten.isBlank() && goodConfidence) {
                log.debug("[EntityDisambiguation] rewrite accepted: '{}' -> '{}'", query.text(), rewritten);
                try {
                    return new Query(rewritten, query != null ? query.metadata() : null);
                } catch (Exception e) {
                    log.debug("[EntityDisambiguation] rebuild failed, keep original: {}", e.toString());
                }
            }
            else {
                log.debug("[EntityDisambiguation] rewrite skipped (confidence or empty): conf='{}', rewritten='{}'",
                        confidence, rewritten);
            }

        } catch (Exception e) {
            log.debug("[EntityDisambiguation] disambiguate failed: {}", e.toString());
        }
        return query;
    }
}