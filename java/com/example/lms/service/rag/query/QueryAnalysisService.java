package com.example.lms.service.rag.query;

import com.example.lms.service.rag.query.QueryAnalysisResult.QueryIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 기반 형태소 분석 및 쿼리 파라메터 추출 서비스
 * 
 * <p>
 * 사용자 쿼리를 LLM에게 전송하여 구조화된 검색 파라메터를 추출합니다.
 * 의도(Intent), 엔티티(Entities), 시간성(Temporal), 확장 키워드 등을 분석합니다.
 * 
 * <p>
 * 모든 품목에 대해 동작하며, 하드코딩된 키워드 필터 없이 범용적으로 사용됩니다.
 */
@Service
@RequiredArgsConstructor
public class QueryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisService.class);

    /**
     * Prefer a fast/utility model for query analysis.
     * Fail-soft: if no fast model exists, this field stays null and we fall back to heuristics.
     */
    @Autowired(required = false)
    @Qualifier("fastChatModel")
    private ChatModel chatModel;

    @Autowired
    @Qualifier("llmFastExecutor")
    private ExecutorService llmFastExecutor;

    @Value("${query-analysis.enabled:true}")
    private boolean enabled;

    @Value("${query-analysis.timeout-ms:3000}")
    private long timeoutMs;

    @Value("${query-analysis.fallback-to-exploration:true}")
    private boolean fallbackToExploration;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 빠른 폴백을 위한 기본 패턴 (LLM 호출 전 프리필터)
    private static final Pattern EXPLORATION_PATTERN = Pattern.compile(
            "(?i)(찾아|검색|조회|알려|어디|뭐야|알아봐|살펴|정보)");

    private static final Pattern FRESH_PATTERN = Pattern.compile(
            "(?i)(출시|발표|루머|소문|스펙|신제품|최신|동향|근황|뉴스|업데이트|패치|공지|일정)");

    
    private static final String ANALYSIS_PROMPT = """
            당신은 한국어 쿼리 분석 전문가입니다.
            사용자 쿼리를 분석하여 아래 JSON 형식으로만 응답하세요. JSON 외의 다른 텍스트는 출력하지 마세요.

            {
              "intent": "SEARCH|INFO|COMPARE|TRENDING|GENERAL",
              "entities": ["핵심 엔티티(인물/제품/장소)"],
              "expandedKeywords": ["관련 속성 키워드"],
              "wantsFresh": true또는false,
              "isExploration": true또는false,
              "searchQueries": ["검색 쿼리1", "검색 쿼리2"],
              "confidenceScore": 0.0~1.0,
              "expectedDomain": "쿼리가 속한 도메인 (예: 의학, 스마트폰, 영화)",
              "contextHints": ["엔티티를 특정하는 힌트 키워드 (소속, 제조사 등)"],
              "noiseDomains": ["제외해야 할 동음이의어 도메인"]
            }

            분석 규칙:
            - "찾아봐", "검색해줘", "조회해줘", "알아봐" → isExploration=true, intent=SEARCH
            - "~에 대해", "~관해", "~알려줘", "~뭐야" → intent=INFO
            - "비교", "뭐가 나아", "차이점" → intent=COMPARE
            - "출시", "발표", "루머", "소문", "스펙", "동향", "근황" → wantsFresh=true, intent=TRENDING
            - entities: 제품명, 브랜드, 인물, 장소 등 모든 고유명사 (제한 없음)
            - expandedKeywords: 관련 속성 (가격, 스펙, 출시일, 특징 등)
            - searchQueries: 엔티티 + 키워드 조합으로 3~5개 검색 쿼리 생성
            - confidenceScore: 분석 확신도 (0.0~1.0)
            - expectedDomain: 쿼리의 주요 도메인 또는 분야 (예: "의학", "스마트폰")
            - contextHints: 엔티티를 특정하는 소속/브랜드/지역 정보 (예: "을지대", "대전을지대학교병원")
            - noiseDomains: 동명이인/동명 제품에서 제외해야 할 도메인 (예: "경제학", "정치인")

            사용자 쿼리: %s
            """;

    /**
     * 쿼리 분석 수행
     * 
     * <p>
     * LLM을 사용하여 쿼리를 분석하고, 실패 시 폴백 로직을 적용합니다.
     * 
     * @param userQuery 사용자 원본 쿼리
     * @return 분석 결과 (실패 시 폴백 결과 반환)
     */
    public QueryAnalysisResult analyze(String userQuery) {
        if (!enabled || userQuery == null || userQuery.isBlank()) {
            log.debug("[QueryAnalysis] Analysis disabled or empty query");
            return QueryAnalysisResult.empty(userQuery);
        }

        // 빠른 휴리스틱 체크 (LLM 호출 전)
        boolean quickExploration = EXPLORATION_PATTERN.matcher(userQuery).find();
        boolean quickFresh = FRESH_PATTERN.matcher(userQuery).find();

        // LLM이 없으면 휴리스틱 기반 폴백
        if (chatModel == null) {
            log.warn("[QueryAnalysis] ChatModel not available, using heuristic fallback");
            return createHeuristicResult(userQuery, quickExploration, quickFresh);
        }

        Future<QueryAnalysisResult> future = null;
        try {
            // LLM 분석 수행 (Hard timeout 적용)
            future = llmFastExecutor.submit(() -> {
                try {
                    String prompt = String.format(ANALYSIS_PROMPT, userQuery);
                    String response = "";
                    try {
                        var resp = chatModel.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(prompt)));
                        if (resp != null && resp.aiMessage() != null && resp.aiMessage().text() != null) {
                            response = resp.aiMessage().text();
                        }
                    } catch (Exception ignore) {
                        // fail-soft: keep empty response
                    }
                    return parseJsonResponse(userQuery, response, quickExploration, quickFresh);
                } catch (Exception e) {
                    log.warn("[QueryAnalysis] LLM analysis failed: {}", e.getMessage());
                    return null;
                }
            });

            QueryAnalysisResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result != null) {
                log.info("[QueryAnalysis] Analysis complete: intent={}, entities={}, exploration={}, fresh={}",
                        result.intent(), result.entities(), result.isExploration(), result.wantsFresh());
                return result;
            }

        } catch (TimeoutException e) {
            // Interrupt Hygiene: never interrupt worker threads on timeout.
            // Best-effort cancel(false) only (do not propagate cancellation toxicity).
            if (future != null) future.cancel(false);
            log.warn("[QueryAnalysis] Analysis timed out ({}ms), task cancelled (no interrupt)", timeoutMs);
        } catch (InterruptedException ie) {
            // Interrupt Hygiene: consume the interrupt flag (parry) and fail-soft.
            if (future != null) future.cancel(false);
            Thread.interrupted();
            log.warn("[QueryAnalysis] Interrupted while analyzing query (interrupt consumed)");
        } catch (Exception e) {
            log.warn("[QueryAnalysis] Analysis failed: {}", e.getMessage());
        }

        // 폴백 처리
        if (fallbackToExploration) {
            log.info("[QueryAnalysis] Using exploration fallback for query: {}", userQuery);
            return QueryAnalysisResult.explorationFallback(userQuery);
        }

        return createHeuristicResult(userQuery, quickExploration, quickFresh);
    }

    /**
     * 휴리스틱 기반 분석 결과 생성 (LLM 없이)
     */
    private QueryAnalysisResult createHeuristicResult(String query, boolean isExploration, boolean wantsFresh) {
        QueryIntent intent = QueryIntent.GENERAL;
        if (isExploration)
            intent = QueryIntent.SEARCH;
        else if (wantsFresh)
            intent = QueryIntent.TRENDING;

        // 간단한 엔티티 추출 (공백으로 분리된 토큰 중 2글자 이상)
        List<String> entities = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            String cleaned = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
            if (cleaned.length() >= 2) {
                entities.add(cleaned);
            }
        }

        return new QueryAnalysisResult(
                query,
                intent,
                entities,
                Collections.emptyList(),
                wantsFresh,
                isExploration,
                List.of(query),
                0.5,
                null,
                Collections.emptyList(),
                Collections.emptyList());
    }

    /**
     * LLM JSON 응답 파싱
     */
    private QueryAnalysisResult parseJsonResponse(String original, String response,
            boolean heuristicExploration, boolean heuristicFresh) {
        try {
            // JSON 부분만 추출 (LLM이 추가 텍스트를 출력할 수 있음)
            String json = extractJson(response);
            if (json == null) {
                log.warn("[QueryAnalysis] No valid JSON found in response");
                return createHeuristicResult(original, heuristicExploration, heuristicFresh);
            }

            JsonNode root = objectMapper.readTree(json);

            String intentStr = getTextOrDefault(root, "intent", "GENERAL");
            QueryIntent intent = parseIntent(intentStr);

            List<String> entities = getStringList(root, "entities");
            List<String> expandedKeywords = getStringList(root, "expandedKeywords");
            List<String> searchQueries = getStringList(root, "searchQueries");

            // [NEW] 엔티티 도메인/컨텍스트/노이즈 필드 파싱
            String expectedDomain = getTextOrDefault(root, "expectedDomain", null);
            List<String> contextHints = getStringList(root, "contextHints");
            List<String> noiseDomains = getStringList(root, "noiseDomains");

            boolean wantsFresh = getBooleanOrDefault(root, "wantsFresh", heuristicFresh);
            boolean isExploration = getBooleanOrDefault(root, "isExploration", heuristicExploration);
            double confidence = getDoubleOrDefault(root, "confidenceScore", 0.7);

            return new QueryAnalysisResult(
                    original,
                    intent,
                    entities,
                    expandedKeywords,
                    wantsFresh,
                    isExploration,
                    searchQueries,
                    confidence,
                    expectedDomain,
                    contextHints,
                    noiseDomains);        } catch (Exception e) {
            log.warn("[QueryAnalysis] JSON parsing failed: {}", e.getMessage());
            return createHeuristicResult(original, heuristicExploration, heuristicFresh);
        }
    }

    private String extractJson(String text) {
        if (text == null)
            return null;

        // JSON 블록 추출 시도
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private QueryIntent parseIntent(String intentStr) {
        try {
            return QueryIntent.valueOf(intentStr.toUpperCase());
        } catch (Exception e) {
            return QueryIntent.GENERAL;
        }
    }

    private String getTextOrDefault(JsonNode node, String field, String defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : defaultVal;
    }

    private boolean getBooleanOrDefault(JsonNode node, String field, boolean defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.asBoolean() : defaultVal;
    }

    private double getDoubleOrDefault(JsonNode node, String field, double defaultVal) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asDouble() : defaultVal;
    }

    private List<String> getStringList(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : child) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return result;
    }
}
