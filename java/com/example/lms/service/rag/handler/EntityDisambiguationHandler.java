package com.example.lms.service.rag.handler;

import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.rag.query.QueryAnalysisResult;
import com.example.lms.service.rag.query.QueryAnalysisService;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 엔티티 모호성을 제거하고, 필요에 따라 Query 를 재작성하는 핸들러입니다.
 *
 * <p>1차적으로 LLM 기반 QueryAnalysisService 를 사용하여 엔티티/도메인 구조를 분석합니다.
 * 엔티티 중심 쿼리라고 확신할 수 있는 경우, 컨텍스트 힌트 + 도메인 + 엔티티 순으로
 * 검색 친화적인 쿼리 문자열을 재구성하고 관련 메타데이터를 Query 에 주입합니다.
 *
 * <p>LLM 분석이 실패하거나 엔티티 확신도가 낮은 경우에는 기존 QueryDisambiguationService
 * 로직을 그대로 사용하는 폴백 경로를 유지합니다.
 */
@Component
@RequiredArgsConstructor
public class EntityDisambiguationHandler {

    private static final Logger log = LoggerFactory.getLogger(EntityDisambiguationHandler.class);

    private final QueryDisambiguationService disambiguationService;
    private final QueryAnalysisService queryAnalysisService;

    @Value("${disambiguation.confidence.threshold:0.6}")
    private double confidenceThreshold;

    @Value("${disambiguation.entity.confidence.threshold:0.7}")
    private double entityConfidenceThreshold;

    /**
     * 주어진 Query 에 대해 엔티티 모호성을 해소하고, 필요시 재작성된 Query 를 반환합니다.
     *
     * @param query   원본 쿼리
     * @param history 대화 히스토리 (레거시 디스앰비규에이션에서 사용)
     */
    public Query disambiguate(Query query, List<String> history) {
        if (query == null || query.text() == null) {
            return query;
        }

        String originalText = query.text();

        // 1. LLM 기반 쿼리 분석 시도
        QueryAnalysisResult analysis = null;
        try {
            analysis = queryAnalysisService.analyze(originalText);
        } catch (Exception e) {
            log.warn("[EntityDisambiguation] Query analysis failed, fallback to legacy: {}", e.getMessage());
        }

        // 2. 엔티티 쿼리로 확신이 있는 경우 → LLM 기반 재작성 우선 적용
        if (analysis != null && analysis.isEntityQuery() && analysis.confidenceScore() >= entityConfidenceThreshold) {
            String expanded = buildExpandedQuery(analysis);
            log.info("[EntityDisambiguation] Entity query rewrite: '{}' -> '{}' (domain={}, hints={}, noise={})",
                    originalText,
                    expanded,
                    analysis.expectedDomain(),
                    analysis.contextHints(),
                    analysis.noiseDomains());
            try {
                return Query.builder()
                        .text(expanded)
                        .metadata(query.metadata())
                        .metadata("originalQuery", originalText)
                        .metadata("isEntityQuery", Boolean.TRUE)
                        .metadata("expectedDomain", analysis.expectedDomain())
                        .metadata("noiseDomains", String.join(",", analysis.noiseDomains()))
                        .metadata("contextHints", String.join(",", analysis.contextHints()))
                        .metadata("entityConfidence", analysis.confidenceScore())
                        .build();
            } catch (Exception e) {
                log.debug("[EntityDisambiguation] rebuild failed, fallback to legacy disambiguation: {}", e.toString());
            }
        }

        // 3. 그 외 케이스 → 기존 DisambiguationService 로직 사용
        return performLegacyDisambiguation(query, history);
    }

    /**
     * 엔티티 분석 결과로 확장 쿼리 생성.
     * 순서: contextHints + expectedDomain + entities
     */
    private String buildExpandedQuery(QueryAnalysisResult a) {
        StringBuilder sb = new StringBuilder();

        // 힌트들 (소속/기관/브랜드 등)
        if (a.contextHints() != null) {
            a.contextHints().forEach(h -> {
                if (h != null && !h.isBlank()) {
                    sb.append(h).append(" ");
                }
            });
        }

        // 도메인 (예: "정신건강의학과", "스마트폰")
        if (a.expectedDomain() != null && !a.expectedDomain().isBlank()) {
            sb.append(a.expectedDomain()).append(" ");
        }

        // 엔티티(들)
        if (a.entities() != null) {
            a.entities().forEach(e -> {
                if (e != null && !e.isBlank()) {
                    sb.append(e).append(" ");
                }
            });
        }

        return sb.toString().trim();
    }

    /**
     * 기존 DisambiguationService 로직 (폴백용).
     */
    private Query performLegacyDisambiguation(Query query, List<String> history) {
        try {
            DisambiguationResult result = disambiguationService.clarify(query.text(), history);
            if (result == null) {
                return query;
            }

            String confidence = result.getConfidence();
            String rewritten = result.getRewrittenQuery();
            boolean goodConfidence = false;

            if (confidence != null) {
                try {
                    double cf = Double.parseDouble(confidence.trim().replace(",", "."));
                    goodConfidence = cf >= confidenceThreshold;
                } catch (NumberFormatException nfe) {
                    // 문자열 형식인 경우 "high" / "medium" 등을 사용할 수 있음
                    goodConfidence = "high".equalsIgnoreCase(confidence);
                }
            }

            if (rewritten != null && !rewritten.isBlank() && goodConfidence) {
                return Query.builder()
                        .text(rewritten)
                        .metadata(query.metadata())
                        .build();
            }
        } catch (Exception e) {
            log.debug("[EntityDisambiguation] legacy disambiguate failed: {}", e.toString());
        }

        return query;
    }
}
